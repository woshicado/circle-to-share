package eu.woshicado.circletoshare.assist

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.animationSettleMs
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import eu.woshicado.circletoshare.crop.CropScreen
import eu.woshicado.circletoshare.share.ShareHelper
import java.io.IOException

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
    // The show (by generation) that has an accessibility capture out, if any.
    private var captureInFlightFor = NO_SHOW
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

    // A call coming in: its notification appears beneath this window, so the
    // phone would ring with nothing to see or answer. (Audible ringing only —
    // see the same listener in ScreenshotAccessibilityService.)
    private val audioModeListener = AudioManager.OnModeChangedListener { mode ->
        if (mode == AudioManager.MODE_RINGTONE) hide()
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

    // What a built CropScreen's sizes and texts depend on. The session — and
    // its content view with it — lives on between shows, through changes of
    // font size, display size and language that would recreate an activity;
    // nothing recreates this. A view's sizes are fixed in pixels, and its
    // texts chosen, when it is built, so it is rebuilt when these differ.
    private data class UiConfig(val fontScale: Float, val densityDpi: Int, val locales: LocaleList)

    private var cropScreenBuiltFor: UiConfig? = null

    private fun uiConfig(): UiConfig = context.resources.configuration.let {
        UiConfig(it.fontScale, it.densityDpi, it.locales)
    }

    private fun buildCropScreen(): CropScreen {
        cropScreenBuiltFor = uiConfig()
        return CropScreen(context).apply {
            callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(share: Boolean, rect: Rect?) = deliver(share, rect)
                override fun onCancel() = hide()
            }
        }
    }

    override fun onCreateContentView(): View {
        cropScreen = buildCropScreen()
        return cropScreen
    }

    // True for a show that found the lock screen up: held back, window and
    // all, until the lock screen is gone — or, if it stays, turned down.
    private var refusedShow = false

    // True while such a show is waiting to see whether the lock screen goes.
    private var awaitingUnlock = false

    // True once a show has failed and its window is only up to say so.
    private var messageShowing = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // The rule the bubble and the tile live by: never shoot, and never
        // present the crop tool, over a lock screen. The platform closes a
        // session when the lock screen appears over it, but one invoked while
        // the lock screen is already up would open as usual — over a picture
        // of the lock screen, with a Share that goes nowhere until the next
        // unlock. Keep the window from appearing at all (onShow says why).
        if (!visible && !awaitingUnlock) {
            refusedShow = keyguardLocked()
            setUiEnabled(!refusedShow)
        }
    }

    private fun keyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * A show that found the lock screen up. It may be on its way out: a
     * fingerprint reader in the power button unlocks during the very press
     * that then, held, calls the assistant — and the lock screen still counts
     * as up while it animates away (the tile waits out the same lag after
     * its unlock prompt). So give it that long to go, and only then say no.
     */
    private fun awaitUnlockThenOpen() {
        awaitingUnlock = true
        val gen = showGeneration
        val deadline = SystemClock.uptimeMillis() + animationSettleMs(
            context, baseMs = KEYGUARD_EXIT_GRACE_MS, offMs = KEYGUARD_EXIT_GRACE_OFF_MS
        ).coerceAtMost(KEYGUARD_EXIT_GRACE_MAX_MS)
        val check = object : Runnable {
            override fun run() {
                if (gen != showGeneration || !awaitingUnlock) return // hidden meanwhile
                if (!keyguardLocked()) {
                    awaitingUnlock = false
                    refusedShow = false
                    // Brings up the window this show was denied so far.
                    setUiEnabled(true)
                    openTool(afterUnlock = true)
                } else if (SystemClock.uptimeMillis() >= deadline) {
                    ScreenshotAccessibilityService.notify(context, R.string.error_tile_locked)
                    hide()
                } else {
                    ui.postDelayed(this, KEYGUARD_POLL_MS)
                }
            }
        }
        ui.postDelayed(check, KEYGUARD_POLL_MS)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Re-invoking the assistant while the overlay is already up re-fires
        // onShow with a fresh system screenshot — of our own overlay. Ignore it,
        // or each activation would bake another layer of dim/crop UI into the
        // frozen image.
        if (visible) return
        if (refusedShow) {
            if (!awaitingUnlock) awaitUnlockThenOpen()
            return
        }
        openTool(afterUnlock = false)
    }

    /**
     * Open the crop tool for this show. [afterUnlock]: the show began on a
     * lock screen that has since gone — whatever screenshot the system took
     * for it is a picture of that lock screen, and must not be used.
     */
    private fun openTool(afterUnlock: Boolean) {
        if (cropScreenBuiltFor != uiConfig()) {
            cropScreen = buildCropScreen()
            setContentView(cropScreen)
        }
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
            context.getSystemService(AudioManager::class.java)
                .addOnModeChangedListener(context.mainExecutor, audioModeListener)
            // The power menu opens beneath this window too. Only the
            // accessibility service can see it appear; if it is running, it
            // tells us.
            ScreenshotAccessibilityService.onYieldToSystemUi = { hide() }
        }
        // After an unlock the system's shot for this show is one of the lock
        // screen. It has usually come and gone by now (dropped, the show not
        // being visible yet) — but should it still be on its way, it is not
        // to be kept as a fallback either.
        handledSystemShot = afterUnlock
        window?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            clearSystemBarBackgrounds(w)
        }
        screenshot = null
        awaitingOwnCapture = false
        ShareHelper.cleanupCache(context)

        val service = ScreenshotAccessibilityService.instance
        if (afterUnlock && service == null) {
            // Nothing here can take a picture of what the unlock revealed.
            // Asking again, now that the device is unlocked, will get one
            // from the system — which is what to tell them, not "unlock
            // first": they just have.
            fail(R.string.error_called_while_unlocking)
            return
        }
        // Grab the clean shot / bubble state BEFORE setCapturing tears them down.
        val reuse = service?.openOverlayScreenshot()
        // Its snap bounds with it: the ones collected just now describe the
        // app as it is at this moment, which the frozen picture may no longer
        // match (moving content, or a rotation since).
        if (reuse != null) pendingSnap = service.openOverlaySnapBounds()
        // (A notice counts like the bubble: "Unlock the device first", then
        // unlocking and asking again, finds it still up.)
        val bubbleWasShowing =
            service?.isBubbleShowing() == true || service?.isNoticeShowing() == true
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
            // The bubble (or a notice) was on screen (implies service != null):
            // the system shot would contain it, so re-capture cleanly now that it's gone
            // (and the zoom animation has settled). Likewise after an unlock,
            // where the system's shot — long delivered, and dropped — showed
            // the lock screen.
            bubbleWasShowing || afterUnlock -> {
                awaitingOwnCapture = true
                scheduleSettledCapture()
            }
            // Normal path: use the system screenshot if it arrives (it's taken
            // at gesture time, pre-animation); otherwise fall back to our own
            // capture once the zoom animation has settled.
            else -> ui.postDelayed(screenshotTimeout, settleDelayMs().coerceAtLeast(450L))
        }
    }

    /**
     * The frozen screenshot already shows the system bars, so the session
     * window must add nothing over them. The platform's session theme asks
     * for "translucent" bars, which makes the system paint its own scrim on
     * the navigation strip: a visible band the bubble path's overlay doesn't
     * have, and — because our fallback capture is taken with this window
     * already up — one that gets baked into the screenshot as well.
     */
    @Suppress("DEPRECATION") // still what controls the bars below API 35
    private fun clearSystemBarBackgrounds(w: Window) {
        // Nor may it be kept out of the display cutout: in landscape that
        // makes the window narrower than the display, and the full-display
        // screenshot is then shown shrunk and shifted instead of exactly over
        // what it is a picture of.
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        w.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        w.isStatusBarContrastEnforced = false
        w.isNavigationBarContrastEnforced = false
    }

    override fun onHandleScreenshot(systemShot: Bitmap?) {
        // The shot is delivered separately from onShow and can land after the
        // session was hidden again (screen off, a quick Back). Loading it
        // then would leave a hidden session holding a capture that the next
        // show flashes before its own arrives — the very thing onHide's
        // clear() exists to prevent.
        if (!visible) return
        // Nor once this show has given up and is only saying why (see fail):
        // a shot that turned up now would bring the tool back just in time
        // for the message's timer to close it — and one from a re-invocation
        // would be a picture of the message.
        if (messageShowing) return
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
        if (refusedShow) {
            // Nothing was opened, so there is nothing to close — and the
            // capture latch released below was never this show's to release.
            refusedShow = false
            awaitingUnlock = false
            showGeneration++ // ends a wait for the unlock, if one is running
            super.onHide()
            return
        }
        visible = false
        messageShowing = false
        if (watchingScreen) {
            watchingScreen = false
            runCatching { context.unregisterReceiver(screenOffReceiver) }
            context.getSystemService(DisplayManager::class.java)
                .unregisterDisplayListener(displayListener)
            runCatching {
                context.getSystemService(AudioManager::class.java)
                    .removeOnModeChangedListener(audioModeListener)
            }
            ScreenshotAccessibilityService.onYieldToSystemUi = null
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
            captureFailed(R.string.error_no_capture)
            return
        }
        val gen = showGeneration
        // One capture per show at a time. A second request can come in while
        // the first is still out (the timeout's, then one for a late "no
        // screenshot" from the system); started, it would only run into the
        // platform's screenshot rate limit and report a failure for a show
        // whose capture is about to succeed.
        if (captureInFlightFor == gen) return
        captureInFlightFor = gen
        service.capture { bitmap ->
            ui.post {
                if (captureInFlightFor == gen) captureInFlightFor = NO_SHOW
                if (gen != showGeneration) return@post // session was hidden since
                if (bitmap != null) {
                    android.util.Log.d("CircleToShare", "assist source: accessibility")
                    onScreenshotReady(bitmap)
                } else {
                    captureFailed(R.string.error_capture_failed)
                }
            }
        }
    }

    private fun captureFailed(messageRes: Int) {
        // A failure only matters while the show is still waiting for its
        // screenshot.
        if (screenshot != null) return
        val fallback = fallbackSystemShot
        fallbackSystemShot = null
        if (fallback != null) onScreenshotReady(fallback) else fail(messageRes)
    }

    private fun onScreenshotReady(bitmap: Bitmap) {
        if (screenshot != null) return
        awaitingOwnCapture = false
        fallbackSystemShot = null
        // copy() reports failure (out of memory) by returning null or throwing.
        val software: Bitmap? = if (bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (t: Throwable) {
                null
            }
        } else {
            bitmap
        }
        if (software == null) {
            fail(R.string.error_capture_failed)
            return
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
        } catch (e: IOException) {
            fail(R.string.error_save_failed)
            return
        } catch (e: Exception) {
            fail(R.string.error_capture_failed)
            return
        }
        if (delivered) hide()
    }

    private fun fail(messageRes: Int) {
        if (ScreenshotAccessibilityService.instance != null) {
            ScreenshotAccessibilityService.notify(context, messageRes)
            hide()
            return
        }
        // No service to show the message, and a toast from here is one the
        // system drops (see notify). But this window is up: say it here, in
        // place of the tool, and close after a reading's time — or at a
        // touch, which the screen passes on as a cancel.
        if (messageShowing) return
        messageShowing = true
        cropScreen.showMessage(context.getString(messageRes))
        val gen = showGeneration
        ui.postDelayed({ if (gen == showGeneration && visible) hide() }, MESSAGE_MS)
    }

    private companion object {
        const val NO_SHOW = -1

        // How long a message shown in place of the tool stays up.
        const val MESSAGE_MS = 5000L

        // How long a lock screen that was up at the show gets to finish
        // going away (the tile's values for the same wait; scaled with the
        // system's animation speed like every other wait here), and how
        // often to look.
        const val KEYGUARD_EXIT_GRACE_MS = 400f
        const val KEYGUARD_EXIT_GRACE_OFF_MS = 100L
        const val KEYGUARD_EXIT_GRACE_MAX_MS = 1500L
        const val KEYGUARD_POLL_MS = 50L
    }
}
