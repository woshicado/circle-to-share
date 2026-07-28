package eu.woshicado.circletoshare.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.Toast
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.crop.CropScreen
import eu.woshicado.circletoshare.crop.SnapBounds
import eu.woshicado.circletoshare.share.ShareHelper
import kotlin.math.abs

/**
 * Two jobs:
 *  1. Fallback screen capture for the assistant session (via [capture]).
 *  2. Optional floating bubble that captures + shows the crop overlay from
 *     anywhere, independent of the launcher's gesture. Toggled from the app.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    /** Why a shade-initiated capture was turned down. */
    enum class Refusal {
        /** Accepted — the capture is under way. */
        NONE,

        /** Screen is locked, dozing, or dreaming. The caller may prompt for an
         *  unlock and retry; nothing else here can clear this. */
        LOCKED,

        /** A crop tool is already open or opening, from any path. */
        BUSY
    }

    companion object {
        var instance: ScreenshotAccessibilityService? = null
            private set

        private const val PREFS = "cts"
        private const val KEY_BUBBLE = "bubble_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val UNSET = Int.MIN_VALUE

        // Budget for the snap-bounds tree walk — node access is Binder IPC,
        // so keep the whole collection pass in the tens of milliseconds.
        private const val SNAP_MAX_NODES = 400
        private const val SNAP_MAX_DEPTH = 25

        // How long to wait after hiding the trigger before grabbing the screen.
        // The bubble is one removeView away; the Quick Settings shade has to
        // play its collapse animation first, or the shot is of the panel.
        private const val BUBBLE_HIDE_DELAY_MS = 80L
        // The shade path watches for the collapse instead of guessing at its
        // duration — see awaitShadeCollapsed. Measured on a Pixel (Android 17,
        // animation scale 0.5x): the shade window holds full height for ~465ms
        // and then snaps to the status-bar strip, consistently across runs.
        // A fixed wait long enough to cover that on every device spent ~340ms
        // of every capture doing nothing.
        private const val SHADE_POLL_MS = 20L
        // A couple of frames after the window resizes, for the last one to be
        // composited before we read the framebuffer.
        private const val SHADE_SETTLE_MS = 50L
        // Backstop if the shade never reports collapsed (OEM skin whose panel
        // isn't a full-height system window). Shooting a shade is better than
        // hanging forever with the latch held.
        private const val SHADE_WAIT_TIMEOUT_MS = 1200L

        fun isBubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BUBBLE, false)

        fun setBubblePref(context: Context, enabled: Boolean) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BUBBLE, enabled)
            if (enabled) {
                // Re-enabling from the app resets the position, so a bubble that
                // was dragged out of reach can always be recovered.
                editor.remove(KEY_BUBBLE_X).remove(KEY_BUBBLE_Y)
                instance?.resetBubblePosition()
            }
            editor.apply()
            instance?.applyBubbleState()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayView: CropScreen? = null
    private var overlayBitmap: Bitmap? = null

    // Last bubble position (persisted), so it reappears where the user left it.
    private var bubbleX = UNSET
    private var bubbleY = UNSET

    // True only while the user has actively dismissed the keyguard (ACTION_USER_
    // _PRESENT). Reset when the screen sleeps/dozes. This — unlike the keyguard
    // "locked" flag — stays false on a trusted lock screen / AOD (Extend Unlock).
    private var userPresent = false

    // True while a dream (AOD, screensaver) runs. Needed because the screen-
    // timeout path goes Awake -> Dreaming, where the device still counts as
    // "interactive" and no SCREEN_OFF is sent — without this the bubble would
    // stay visible on top of AOD until the lock screen appears.
    private var dreaming = false

    // True while the screenshot tool is open (or opening) via ANY path — the
    // bubble tap, the assistant session, etc. The bubble hides and stays hidden
    // while this is set, so it never lands in the shot or stacks over the tool.
    private var capturing = false

    // Bumped when the assistant takes over (setCapturing(true)) so an in-flight
    // bubble-tap capture knows it was superseded and must not present its
    // overlay on top of the assistant's.
    private var bubbleCaptureGen = 0

    // The generation that presented the currently open bubble overlay. Its
    // dismissal may only clear the `capturing` latch while still current — a
    // Cancel/Share click dispatched just as the assistant takes over must not
    // release the latch the assistant now owns (re-showing the bubble on top).
    private var overlayGen = -1

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    // Re-evaluate as the screen locks/unlocks/starts dreaming.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> userPresent = true
                Intent.ACTION_SCREEN_OFF -> userPresent = false
                // Lock screen "None" never sends ACTION_USER_PRESENT: with no
                // keyguard security, waking the screen IS user presence. Secure
                // devices (incl. Extend Unlock) still wait for USER_PRESENT.
                Intent.ACTION_SCREEN_ON ->
                    if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                        userPresent = true
                    }
                Intent.ACTION_DREAMING_STARTED -> {
                    dreaming = true
                    userPresent = false
                }
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    // Waking within the keyguard grace period never shows the
                    // lock screen, so no USER_PRESENT follows — returning to a
                    // still-unlocked screen IS presence.
                    if (isScreenInteractive() && !keyguardLocked()) userPresent = true
                }
            }
            refreshBubble()
        }
    }

    // Catches AOD/doze transitions, which don't reliably send ACTION_SCREEN_OFF.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (!isScreenInteractive()) userPresent = false
            // Rotation fires this too — keep an open overlay covering the
            // whole (re-oriented) screen.
            resizeOverlayToDisplay()
            refreshBubble()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        bubbleX = prefs.getInt(KEY_BUBBLE_X, UNSET)
        bubbleY = prefs.getInt(KEY_BUBBLE_Y, UNSET)
        // The service is enabled while the user is unlocked and using the phone.
        val keyguard = getSystemService(KeyguardManager::class.java)
        userPresent = isScreenInteractive() && !keyguard.isKeyguardLocked
        // Protected system broadcasts — no permission or exported flag needed.
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            }
        )
        displayManager.registerDisplayListener(displayListener, ui)
        refreshBubble()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        teardownOverlay()
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        teardownOverlay()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window transitions (e.g. the keyguard appearing over the crop overlay)
        // re-evaluate — this is what catches the lock screen while still awake.
        refreshBubble()
    }

    override fun onInterrupt() = Unit

    // --- Screen capture: bubble path + assistant fallback/clean re-capture --

    fun capture(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // Report failure rather than throw (e.g. OOM copying a
                    // large buffer) — an unreported failure would leave the
                    // `capturing` latch stuck and the tool dead until the
                    // service is toggled.
                    val result = try {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        runCatching { screenshot.hardwareBuffer.close() }
                    }
                    callback(result)
                }

                override fun onFailure(errorCode: Int) = callback(null)
            }
        )
    }

    // --- Snap-to-content bounds ----------------------------------------------

    /**
     * Bounds of the UI elements currently on screen, for snap-to-content
     * cropping. Read via the window/node tree at the moment of capture only —
     * never stored. Null when window introspection is unavailable (service
     * still connecting, flag not granted) or nothing useful is visible.
     *
     * Synchronous on the main thread, but capped (≤ [SNAP_MAX_NODES] nodes)
     * and always called AFTER the screenshot is taken, so it can never delay
     * the capture itself.
     */
    fun collectSnapBounds(): SnapBounds? {
        val bounds = windowManager.currentWindowMetrics.bounds
        val display = Rect(0, 0, bounds.width(), bounds.height())
        val minSide = dp(4)
        val rects = HashSet<Rect>()
        var budget = SNAP_MAX_NODES
        var appWindows = 0
        for (window in windows) {
            // Application windows only: skips our own bubble/crop overlay
            // (TYPE_ACCESSIBILITY_OVERLAY), the assist-session window (system
            // layer), status/nav bars, and the IME — and naturally includes
            // every app window in split-screen.
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (window.displayId != Display.DEFAULT_DISPLAY) continue
            val root = window.root ?: continue
            // Belt and suspenders in case an OEM types our overlay oddly.
            if (root.packageName == packageName) continue
            appWindows++
            budget = walkSnapNodes(root, 0, budget, display, minSide, rects)
            if (budget <= 0) break
        }
        android.util.Log.d(
            "CircleToShare",
            "snap collect: ${windows.size} windows ($appWindows app), " +
                "${rects.size} rects, ${SNAP_MAX_NODES - budget} nodes visited, " +
                "screen ${display.width()}x${display.height()}"
        )
        return if (rects.isEmpty()) null else {
            SnapBounds(rects.toList(), display.width(), display.height())
        }
    }

    /** DFS over [node], collecting visible non-trivial bounds into [out].
     *  Returns the remaining node budget. */
    private fun walkSnapNodes(
        node: AccessibilityNodeInfo,
        depth: Int,
        budgetIn: Int,
        display: Rect,
        minSide: Int,
        out: MutableSet<Rect>
    ): Int {
        var budget = budgetIn - 1
        if (budget < 0 || depth > SNAP_MAX_DEPTH) return 0
        if (node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            // Containers count too — cards and list rows are exactly the
            // boundaries users want. Only dust (dividers, zero-size nodes)
            // and off-screen bounds are dropped.
            if (r.width() >= minSide && r.height() >= minSide && Rect.intersects(r, display)) {
                out.add(r)
            }
        }
        for (i in 0 until node.childCount) {
            if (budget <= 0) return 0
            val child = node.getChild(i) ?: continue
            budget = walkSnapNodes(child, depth + 1, budget, display, minSide, out)
        }
        return budget
    }

    // --- Floating bubble ----------------------------------------------------

    /** Re-apply the pref (called when the toggle changes). */
    fun applyBubbleState() = refreshBubble()

    /** Forget the saved position so the bubble returns to its default spot. */
    fun resetBubblePosition() {
        bubbleX = UNSET
        bubbleY = UNSET
    }

    /** True when the screen is awake — but beware: dreaming (AOD after a screen
     *  timeout) still counts as interactive, hence the separate [dreaming] flag. */
    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** The screen is awake and unlocked — the tool/bubble may be shown. */
    private fun isScreenUsable(): Boolean =
        isScreenInteractive() && !dreaming && !keyguardLocked()

    /** Bubble is shown only when enabled, no tool open, the user is present,
     *  and the screen is usable — hidden on lock screen and AOD even when the
     *  device is trust-unlocked (Extend Unlock). */
    private fun shouldShowBubble(): Boolean =
        isBubbleEnabled(this) && !capturing && overlayView == null &&
            userPresent && isScreenUsable()

    private fun refreshBubble() {
        ui.post {
            // Close the crop tool if the screen went to sleep/AOD/lock while it
            // was open, so it isn't left interactive on the lock screen.
            if (overlayView != null && !isScreenUsable()) {
                teardownOverlay()
                if (overlayGen == bubbleCaptureGen) capturing = false
            }
            if (shouldShowBubble()) addBubble() else removeBubble()
        }
    }

    private fun addBubble() {
        if (bubbleView != null) return
        val size = dp(52)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_glyph)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 52, 43, 109))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }
        val metrics = resources.displayMetrics
        if (bubbleX == UNSET || bubbleY == UNSET) {
            // First run: default to the right edge, a third of the way down.
            bubbleX = metrics.widthPixels - size - dp(8)
            bubbleY = metrics.heightPixels / 3
        }
        // Keep it on-screen (e.g. after a rotation or resolution change).
        bubbleX = bubbleX.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        bubbleY = bubbleY.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
        attachBubbleTouch(icon, params)
        // The window token dies with the service: a refreshBubble posted just
        // before unbind can run after it, where addView throws BadTokenException.
        if (runCatching { windowManager.addView(icon, params) }.isFailure) return
        bubbleView = icon
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun attachBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX)
                    val dy = (event.rawY - startY)
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    params.x = originX + dx.toInt()
                    params.y = originY + dy.toInt()
                    bubbleX = params.x
                    bubbleY = params.y
                    runCatching { windowManager.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick(); triggerCapture()
                    } else {
                        persistBubblePosition()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // A drag can end in a cancel (a system gesture steals the
                    // pointer); the bubble already moved, so persist like UP.
                    if (moved) persistBubblePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistBubblePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_BUBBLE_X, bubbleX)
            .putInt(KEY_BUBBLE_Y, bubbleY)
            .apply()
    }

    /**
     * Capture from the Quick Settings tile: dismiss the shade first, then wait
     * out its collapse animation so the shot shows the app underneath rather
     * than the panel the user just tapped.
     *
     * Nothing is dismissed before a refusal is decided, so a refused tap leaves
     * the shade open and costs the user nothing. The caller needs the reason,
     * not just a boolean: [Refusal.LOCKED] is the one it can act on, by
     * prompting for an unlock and trying again. (A capture abandoned *after*
     * this returns [Refusal.NONE] — screen locked during the wait — does cost
     * the user their shade; see `abandonCapture`.)
     */
    fun triggerCaptureFromShade(): Refusal {
        // The same gate the bubble lives behind: never shoot, and never present
        // the crop tool, over a lock screen or AOD.
        //
        // The tile can't make this call for itself — TileService.isLocked is
        // isDeviceLocked, which a trust agent (Extend Unlock) clears while the
        // keyguard is still on screen, so it reads "unlocked" on a lock screen
        // we must not capture. isScreenUsable()'s isKeyguardLocked() is true
        // there. It's also the right gate for the unlockAndRun path: keying off
        // `userPresent` instead would race ACTION_USER_PRESENT, which can land
        // after the post-unlock runnable does.
        if (!isScreenUsable()) return Refusal.LOCKED
        if (capturing) return Refusal.BUSY // a tool is already open/opening

        // SystemUI registers this action only while the shade is expanded and
        // the keyguard is down, so `false` means "there was no shade to
        // dismiss" — the normal case on the post-unlock retry, where the
        // unlock already collapsed it — not "refused". Treating it as a
        // failure would silently drop the capture the user just authenticated
        // for. With no shade there is no collapse to outlast, so shoot on the
        // same short delay the bubble uses.
        val gen = beginCapture() ?: return Refusal.BUSY
        if (performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) {
            awaitShadeCollapsed(gen)
        } else {
            ui.postDelayed({ runCapture(gen) }, BUBBLE_HIDE_DELAY_MS)
        }
        return Refusal.NONE
    }

    /**
     * True while an expanded shade is on screen.
     *
     * SystemUI keeps the panel in one full-height system window for the whole
     * collapse and resizes it to the status-bar strip only at the very end, so
     * this flips exactly once — when the shade is really gone — rather than
     * tracking the animation partway. Measured on a Pixel: full height for
     * ~465ms, then 204px. On a skin that lays the panel out differently the
     * predicate simply never goes false and the timeout takes over.
     */
    private fun shadeExpanded(): Boolean {
        val half = windowManager.currentWindowMetrics.bounds.height() / 2
        return windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                Rect().also { w.getBoundsInScreen(it) }.height() > half
        }
    }

    /**
     * Shoot as soon as the shade is really gone, rather than waiting out a
     * guessed duration. The collapse is SystemUI's animation, not ours — it
     * varies by device, skin and animation scale, so any fixed number is either
     * too short somewhere (a shot of the shade) or wasteful everywhere. The
     * fixed 800ms this replaced spent ~340ms per capture doing nothing.
     */
    private fun awaitShadeCollapsed(gen: Int) {
        val deadline = SystemClock.uptimeMillis() + SHADE_WAIT_TIMEOUT_MS
        ui.post(object : Runnable {
            override fun run() {
                if (gen != bubbleCaptureGen) return // superseded
                if (shadeExpanded() && SystemClock.uptimeMillis() < deadline) {
                    ui.postDelayed(this, SHADE_POLL_MS)
                } else {
                    ui.postDelayed({ runCapture(gen) }, SHADE_SETTLE_MS)
                }
            }
        })
    }

    /**
     * Hide the bubble (so it isn't in the shot), then grab the screen and
     * show the crop overlay. Called when the bubble is tapped.
     *
     * [delayMs] is how long to wait before the shot — long enough for whatever
     * UI triggered it to be off the screen.
     */
    fun triggerCapture(delayMs: Long = BUBBLE_HIDE_DELAY_MS) {
        val gen = beginCapture() ?: return
        ui.postDelayed({ runCapture(gen) }, delayMs)
    }

    /** Claim the capture latch and hide the bubble, returning the generation
     *  that now owns it — or null if a tool is already open/opening. */
    private fun beginCapture(): Int? {
        if (capturing) return null
        capturing = true
        removeBubble()
        return ++bubbleCaptureGen
    }

    /** Take the shot and present the crop overlay, if [gen] still owns the
     *  capture and the screen is still usable. */
    private fun runCapture(gen: Int) {
        if (gen != bubbleCaptureGen) return // assistant took over
        // The screen can lock or doze off during the wait — a long one on the
        // shade path. Bail rather than shoot, or we'd present an interactive
        // crop tool holding a pre-lock shot over the keyguard.
        if (!isScreenUsable()) return abandonCapture()
        capture { bitmap ->
            ui.post {
                if (gen != bubbleCaptureGen) return@post // assistant took over
                if (!isScreenUsable()) return@post abandonCapture()
                if (bitmap != null) {
                    val snap = if (Prefs.isSnapEnabled(this)) collectSnapBounds() else null
                    presentOverlay(bitmap, snap)
                } else {
                    Toast.makeText(
                        this, R.string.error_capture_failed, Toast.LENGTH_LONG
                    ).show()
                    abandonCapture()
                }
            }
        }
    }

    /** Give up an in-flight capture and release the latch, so a screen that
     *  locked mid-capture doesn't leave the tool wedged shut. */
    private fun abandonCapture() {
        capturing = false
        refreshBubble()
    }

    /**
     * Called by the assistant session so the bubble hides — and stays hidden —
     * while its crop overlay is up, and can't be tapped to stack a second tool.
     */
    fun setCapturing(active: Boolean) {
        capturing = active
        if (active) {
            // Also close the bubble-path overlay — and cancel any bubble
            // capture still in flight — so the assistant never stacks on top
            // of it: only one tool is ever open.
            bubbleCaptureGen++
            ui.post { teardownOverlay(); removeBubble() }
        } else {
            refreshBubble()
        }
    }

    /** True if the floating bubble is currently on screen. */
    fun isBubbleShowing(): Boolean = bubbleView != null

    /** The clean screenshot behind the open bubble crop overlay (no bubble in
     *  it), for the assistant to reuse instead of a system shot that would
     *  include the overlay. Null if no bubble overlay is open. */
    fun openOverlayScreenshot(): Bitmap? = if (overlayView != null) overlayBitmap else null

    private fun presentOverlay(bitmap: Bitmap, snap: SnapBounds?) {
        ShareHelper.cleanupCache(this)
        teardownOverlay()
        overlayGen = bubbleCaptureGen
        overlayBitmap = bitmap

        val screen = CropScreen(this)
        screen.isFocusableInTouchMode = true
        screen.callbacks = object : CropScreen.Callbacks {
            override fun onDeliver(share: Boolean, rect: Rect?) {
                val ok = try {
                    ShareHelper.deliver(this@ScreenshotAccessibilityService, bitmap, rect, share)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ScreenshotAccessibilityService,
                        R.string.error_capture_failed, Toast.LENGTH_LONG
                    ).show()
                    true
                }
                if (ok) dismissOverlay()
            }

            override fun onCancel() = dismissOverlay()
        }
        // Cancel on the back key.
        screen.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissOverlay(); true
            } else false
        }

        // Size to the whole physical display so the dim covers the status and
        // navigation bars too — not just the app content area.
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Guarded like addBubble: the window token dies with the service, and
        // an unguarded throw here would strand `capturing` at true with no
        // overlay for refreshBubble to tear down — wedging every future
        // capture, from every path, until the service is toggled.
        if (runCatching { windowManager.addView(screen, params) }.isFailure) {
            overlayBitmap = null
            // Restore the "no overlay is open" invariant overlayGen documents —
            // it was claimed just above, and leaving it pointing at the live
            // generation would let a future overlayGen-only check clear a latch
            // the assistant owns.
            overlayGen = -1
            abandonCapture()
            return
        }
        overlayView = screen
        screen.setBitmap(bitmap, snap)
        screen.requestFocus()
    }

    /** Re-fit an open crop overlay to the current display bounds (rotation). */
    private fun resizeOverlayToDisplay() {
        val view = overlayView ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.width == bounds.width() && params.height == bounds.height()) return
        params.width = bounds.width()
        params.height = bounds.height()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun dismissOverlay() {
        teardownOverlay()
        if (overlayGen != bubbleCaptureGen) return // assistant owns the latch now
        capturing = false
        refreshBubble()
    }

    private fun teardownOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        overlayBitmap = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
