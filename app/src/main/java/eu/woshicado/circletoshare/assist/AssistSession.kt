package eu.woshicado.circletoshare.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.Display
import android.view.View
import android.widget.Toast
import androidx.core.view.WindowCompat
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.animationSettleMs
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import eu.woshicado.circletoshare.crop.CropScreen
import eu.woshicado.circletoshare.share.ShareHelper

/**
 * Full-screen overlay shown when the assistant gesture fires: displays the
 * frozen screenshot, lets the user crop, then shares or copies the result
 * without ever writing to the gallery.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private val ui = Handler(Looper.getMainLooper())
    private var screenshot: Bitmap? = null
    private var awaitingOwnCapture = false
    private var visible = false
    private var handledSystemShot = false
    // Bumped on every hide so async work started for an earlier show (delayed
    // captures, accessibility callbacks) can tell it has been superseded and
    // must not deliver a stale screenshot into a later show.
    private var showGeneration = 0
    // System shot set aside on the bubble path — used only if the clean
    // re-capture fails (it shows the bubble, which beats failing outright).
    private var fallbackSystemShot: Bitmap? = null
    // Snap bounds collected at onShow — the accessibility framework hasn't
    // yet recomputed visibility for our (occluding) session window at that
    // moment, so the app's node tree is still fully visible. Collecting later
    // (when the screenshot arrives) yields a sparse, occlusion-clipped set.
    private var pendingSnap: eu.woshicado.circletoshare.crop.SnapBounds? = null
    // When the session was shown — anchor for the zoom-settle deadline below.
    private var showUptime = 0L

    /**
     * Invoking the assistant plays a system zoom-out animation on the app
     * behind us. An accessibility capture taken mid-animation bakes that zoom
     * into the screenshot (~2% scale), visibly misaligning it — and any snap
     * bounds — from the real layout. Measured on a Pixel at 1x animation
     * scale: still zoomed ~500ms after onShow, mostly settled ~700ms, and the
     * tail varies run to run — 800ms was reliably clean. So captures we take
     * ourselves wait this long after [showUptime]; scaled up if the user runs
     * slower animations, and skipped when animations are off.
     */
    // 150ms floor: the bubble teardown needs a frame or two before a clean
    // capture even with animations disabled.
    private fun settleDelayMs(): Long =
        animationSettleMs(context, baseMs = 800f, offMs = 150L)

    /** Run an accessibility capture once the zoom animation has settled. */
    private fun scheduleSettledCapture() {
        val gen = showGeneration
        val remaining =
            (showUptime + settleDelayMs() - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        ui.postDelayed({
            if (gen == showGeneration && screenshot == null) captureViaAccessibility()
        }, remaining)
    }
    private lateinit var cropScreen: CropScreen

    private val screenshotTimeout = Runnable {
        if (screenshot == null) captureViaAccessibility()
    }

    // The session window layers above the AOD dream, so when the screen times
    // out it would keep rendering on top of Always-on Display, forever. Close it
    // instead, so the normal timeout -> AOD flow is preserved. No wake lock is
    // held — the timeout itself already fires normally.
    //
    // DREAMING_STARTED covers the timeout path: AOD is a dream, and while it
    // runs the device is still "interactive" and SCREEN_OFF hasn't been sent.
    // SCREEN_OFF covers the power-button path, which skips straight to doze.
    private var watchingScreen = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = hide()
    }

    // Catches AOD/doze transitions, which don't reliably send ACTION_SCREEN_OFF.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (!context.getSystemService(PowerManager::class.java).isInteractive) hide()
        }
    }

    override fun onCreateContentView(): View {
        cropScreen = CropScreen(context).apply {
            callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(share: Boolean, rect: Rect?) = deliver(share, rect)
                override fun onCancel() = hide()
            }
        }
        return cropScreen
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Re-invoking the assistant while the overlay is already up re-fires
        // onShow with a fresh system screenshot — of our own overlay. Ignore it,
        // or each activation would bake another layer of dim/crop UI into the
        // frozen image.
        if (visible) return
        visible = true
        showUptime = SystemClock.uptimeMillis()
        // Grab the snap bounds NOW — before the accessibility framework
        // notices our window covering the app and marks its nodes invisible
        // (or drops the window from getWindows() entirely).
        pendingSnap = if (Prefs.isSnapEnabled(context)) {
            ScreenshotAccessibilityService.instance?.collectSnapBounds()
        } else {
            null
        }
        if (!watchingScreen) {
            watchingScreen = true
            context.registerReceiver(
                screenOffReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_DREAMING_STARTED)
                }
            )
            context.getSystemService(DisplayManager::class.java)
                .registerDisplayListener(displayListener, ui)
        }
        handledSystemShot = false
        window?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        screenshot = null
        awaitingOwnCapture = false
        ShareHelper.cleanupCache(context)

        val service = ScreenshotAccessibilityService.instance
        // Grab the clean shot / bubble state BEFORE setCapturing tears them down.
        val reuse = service?.openOverlayScreenshot()
        val bubbleWasShowing = service?.isBubbleShowing() == true
        // A tool is now open — hide the bubble and close any bubble-path overlay
        // so nothing lingers over or stacks on top of this one.
        service?.setCapturing(true)

        when {
            // The bubble had a crop overlay open: reuse its clean screenshot so
            // that overlay isn't baked into this one (no double dim / ghost bar).
            reuse != null -> {
                android.util.Log.d("CircleToShare", "assist source: bubble-reuse")
                onScreenshotReady(reuse)
            }
            // The bubble was on screen (implies service != null): the system
            // shot would contain it, so re-capture cleanly now that it's gone
            // (and the zoom animation has settled).
            bubbleWasShowing -> {
                awaitingOwnCapture = true
                scheduleSettledCapture()
            }
            // Normal path: use the system screenshot if it arrives (it's taken
            // at gesture time, pre-animation); otherwise fall back to our own
            // capture once the zoom animation has settled.
            else -> ui.postDelayed(screenshotTimeout, settleDelayMs().coerceAtLeast(450L))
        }
    }

    override fun onHandleScreenshot(systemShot: Bitmap?) {
        // A re-invocation blocked by the visible guard still delivers a system
        // shot — of our own overlay. Only the first delivery per show is clean.
        if (handledSystemShot) return
        handledSystemShot = true
        if (screenshot != null) return
        // When we're taking our own clean capture (bubble was involved), don't
        // use the system shot — it would include the bubble — but keep it as a
        // last resort in case our capture fails (e.g. OS rate limit).
        if (awaitingOwnCapture) {
            fallbackSystemShot = systemShot
            return
        }
        ui.removeCallbacks(screenshotTimeout)
        if (systemShot != null) {
            android.util.Log.d("CircleToShare", "assist source: system")
            onScreenshotReady(systemShot)
        } else {
            // An explicit "no shot for you" — capture ourselves, but not
            // before the zoom animation settles.
            scheduleSettledCapture()
        }
    }

    override fun onHide() {
        visible = false
        if (watchingScreen) {
            watchingScreen = false
            runCatching { context.unregisterReceiver(screenOffReceiver) }
            context.getSystemService(DisplayManager::class.java)
                .unregisterDisplayListener(displayListener)
        }
        showGeneration++
        ui.removeCallbacks(screenshotTimeout)
        screenshot = null
        fallbackSystemShot = null
        pendingSnap = null
        awaitingOwnCapture = false
        // Drop the frozen image now, so the next show doesn't flash this
        // session's (possibly sensitive) screenshot while the new capture is
        // still in flight.
        if (::cropScreen.isInitialized) cropScreen.clear()
        // Tool closed — allow the bubble back (if the user has it enabled).
        ScreenshotAccessibilityService.instance?.setCapturing(false)
        super.onHide()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentInsets.setEmpty()
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    private fun captureViaAccessibility() {
        val service = ScreenshotAccessibilityService.instance
        if (service == null) {
            captureFailed(context.getString(R.string.error_no_capture))
            return
        }
        val gen = showGeneration
        service.capture { bitmap ->
            ui.post {
                if (gen != showGeneration) return@post // session was hidden since
                if (bitmap != null) {
                    android.util.Log.d("CircleToShare", "assist source: accessibility")
                    onScreenshotReady(bitmap)
                } else {
                    captureFailed(context.getString(R.string.error_capture_failed))
                }
            }
        }
    }

    private fun captureFailed(message: String) {
        val fallback = fallbackSystemShot
        fallbackSystemShot = null
        if (fallback != null) onScreenshotReady(fallback) else fail(message)
    }

    private fun onScreenshotReady(bitmap: Bitmap) {
        if (screenshot != null) return
        awaitingOwnCapture = false
        fallbackSystemShot = null
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        screenshot = software
        android.util.Log.d(
            "CircleToShare",
            "assist shot ${software.width}x${software.height}, " +
                "snap rects: ${pendingSnap?.rects?.size ?: 0}"
        )
        cropScreen.setBitmap(software, pendingSnap)
    }

    private fun deliver(share: Boolean, rect: Rect?) {
        val bitmap = screenshot ?: return
        val delivered = try {
            ShareHelper.deliver(context, bitmap, rect, share)
        } catch (e: Exception) {
            fail(context.getString(R.string.error_capture_failed))
            return
        }
        if (delivered) hide()
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        hide()
    }
}
