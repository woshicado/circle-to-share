package eu.woshicado.circletoshare.capture

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import eu.woshicado.circletoshare.MainActivity
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.RingWatcher
import eu.woshicado.circletoshare.assist.AssistInteractionService
import eu.woshicado.circletoshare.crop.CropScreen
import eu.woshicado.circletoshare.crop.SnapBounds
import eu.woshicado.circletoshare.share.ShareHelper
import kotlin.math.abs
import kotlin.math.roundToInt
import java.io.IOException

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
        BUSY,

        /** The user has not agreed to the accessibility disclosure. Only the
         *  app's own screen can ask; nothing here can clear this. */
        NO_CONSENT
    }

    companion object {
        var instance: ScreenshotAccessibilityService? = null
            private set

        /**
         * Set by the assistant session while its overlay is up. That overlay
         * covers the system's own UI just as this service's does, but only
         * this service can see some of the reasons to get out of the way
         * (the power menu's window appearing) — so it passes them on.
         */
        var onYieldToSystemUi: (() -> Unit)? = null

        /**
         * Say something to the user from a place with no screen of its own —
         * a failed capture, a refusal.
         *
         * A toast is the obvious tool and mostly doesn't work here: from
         * Android 13 the system drops toasts from an app in the background
         * that may not post notifications, which this app (no permissions)
         * never may; and before that, they are drawn beneath the crop
         * overlay. So wherever this service is running it shows the message
         * itself, in a window of its own, and a toast is only the fallback.
         */
        fun notify(context: Context, messageRes: Int) {
            val service = instance
            if (service != null) {
                service.showNotice(context.getString(messageRes))
            } else {
                Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
            }
        }

        private const val PREFS = "cts"
        private const val KEY_BUBBLE = "bubble_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        // Set once the saved spot is known to be measured from the display's
        // corner — see migrateBubbleOrigin.
        private const val KEY_BUBBLE_ORIGIN_DISPLAY = "bubble_origin_display"
        private const val UNSET = Int.MIN_VALUE

        // Bubble opacity in percent. The floor keeps a bubble that has to be
        // found again (to drag or tap it) from fading out of sight entirely.
        const val BUBBLE_OPACITY_MIN = 20
        const val BUBBLE_OPACITY_MAX = 100
        const val BUBBLE_OPACITY_STEP = 5
        private const val NO_CAPTURE = -1

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        // From this smallest width up, the system shows a taskbar in place
        // of a plain navigation bar.
        private const val LARGE_SCREEN_MIN_WIDTH_DP = 600

        // Budget for the snap-bounds tree walk — node access is Binder IPC,
        // so keep the whole collection pass in the tens of milliseconds.
        private const val SNAP_MAX_NODES = 400
        private const val SNAP_MAX_DEPTH = 25

        // How long to wait after hiding the trigger before grabbing the screen.
        // The bubble is one removeView away; the Quick Settings shade has to
        // play its collapse animation first, or the shot is of the panel.
        private const val BUBBLE_HIDE_DELAY_MS = 80L

        // How long a notice stays up — a long toast's time.
        private const val NOTICE_MS = 3500L

        // How long after a window event to look at the screen's state a
        // second time. Events are delivered the moment they happen (see
        // notificationTimeout in the service config: with a delay there, a
        // later event replaces an undelivered one of the same type, and the
        // power menu's could be lost that way) — but what a window brings
        // with it, such as the keyguard reporting itself locked, can settle a
        // moment after the window appears.
        private const val WINDOW_EVENT_SETTLE_MS = 100L
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

        /** Always a value the settings slider accepts: in range and on a
         *  step (the slider throws on anything else). */
        fun getBubbleOpacity(context: Context): Int = snapOpacity(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BUBBLE_OPACITY, BUBBLE_OPACITY_MAX)
        )

        fun setBubbleOpacity(context: Context, percent: Int) {
            val snapped = snapOpacity(percent)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BUBBLE_OPACITY, snapped).apply()
            instance?.applySavedBubbleOpacity(snapped)
        }

        private fun snapOpacity(percent: Int): Int {
            val steps = ((percent - BUBBLE_OPACITY_MIN).toFloat() / BUBBLE_OPACITY_STEP).roundToInt()
            return (BUBBLE_OPACITY_MIN + steps * BUBBLE_OPACITY_STEP)
                .coerceIn(BUBBLE_OPACITY_MIN, BUBBLE_OPACITY_MAX)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val settledRefresh = Runnable { refreshBubble() }
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noticeView: View? = null
    private val removeNotice = Runnable {
        noticeView?.let { runCatching { windowManager.removeView(it) } }
        noticeView = null
    }
    private var overlayView: CropScreen? = null

    // The display the open overlay was laid out for: its size, and where its
    // navigation bar and cutout are (which is what tells one landscape from
    // the other). The overlay's frame is decided when it opens and again
    // only when one of these changes. It also depends on whether the
    // navigation bar is currently showing, and that can flip while the
    // overlay is up (an immersive app underneath, the overlay itself taking
    // focus) at moments that have nothing to do with layout; re-deciding
    // then would resize the window under the user's hands and drop the box
    // they are drawing.
    private var overlayDisplay: DisplayState? = null

    private data class DisplayState(
        val width: Int,
        val height: Int,
        val navigationBar: Insets,
        val cutout: Insets
    )
    private var overlayBitmap: Bitmap? = null
    private var bubbleDensityDpi = 0
    private var overlaySnap: SnapBounds? = null

    // Last bubble position (persisted), so it reappears where the user left it.
    private var bubbleX = UNSET
    private var bubbleY = UNSET

    // The opacity the settings slider is previewing mid-drag, not yet saved
    // (null otherwise). A bubble rebuilt during the drag shows this, not the
    // stored value. Main thread only.
    private var previewOpacity: Int? = null

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

    // The generation of a bubble/tile capture that holds the latch but has
    // not yet presented its overlay or given up; NO_CAPTURE otherwise.
    private var pendingCaptureGen = NO_CAPTURE

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    // Re-evaluate as the screen locks/unlocks/starts dreaming.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> userPresent = true
                Intent.ACTION_SCREEN_OFF -> userPresent = false
                // Lock screen "None" never sends ACTION_USER_PRESENT: with no
                // keyguard security, waking the screen IS user presence. The
                // same goes for a secure device woken inside its lock grace
                // period ("lock N seconds after the screen times out"): no
                // keyguard comes up, so none is dismissed, and without this
                // the bubble would stay gone until the next real unlock. A
                // secure device that wakes onto its keyguard (incl. Extend
                // Unlock's) still waits for USER_PRESENT.
                Intent.ACTION_SCREEN_ON ->
                    if (!getSystemService(KeyguardManager::class.java).isDeviceSecure ||
                        !keyguardLocked()
                    ) {
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

    // The system asking transient UI to get out of the way. The overlay
    // floats above every app and above the system's own dialogs, so unless it
    // goes (or, still on its way, is called off) too:
    //  - Home or Recents: the phone seems stuck on a frozen picture of the
    //    app that was just left;
    //  - an assistant: it opens underneath, and nothing appears to happen —
    //    unless the assistant is this app, whose session takes over the
    //    overlay's screenshot and closes it itself (see AssistSession.onShow);
    //    closing it here first would lose that.
    // (The assistant session's own window is closed by the system on the same
    // signal. The power menu sends no such signal — see onAccessibilityEvent.)
    private val closeSystemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val leaving = when (intent?.getStringExtra("reason")) {
                "homekey", "recentapps" -> true
                "assist" -> AssistInteractionService.instance == null
                else -> false
            }
            if (leaving) yieldToSystemUi()
        }
    }

    // A call coming in: the overlay must not sit on top of it. Android 12+
    // only — see RingWatcher.
    private val ringWatcher = RingWatcher(this) { yieldToSystemUi() }

    // Catches AOD/doze transitions, which don't reliably send ACTION_SCREEN_OFF.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (!isScreenInteractive()) userPresent = false
            // Rotation fires this too — keep an open overlay covering the
            // whole (re-oriented) screen, and the bubble within it.
            resizeOverlayToDisplay()
            keepBubbleOnScreen()
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
        migrateBubbleOrigin(prefs)
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
        // Its own registration, apart from the receiver above: only the
        // system may send this broadcast, but from Android 14 it does not
        // count as a "system broadcast" for registering without an exported
        // flag — mixing it in there throws. Not exported is all it needs: the
        // system's broadcasts are delivered regardless. (Below Android 13
        // there is no such flag, and no such rule.)
        @Suppress("DEPRECATION") // deprecated for sending; receiving is fine
        val closeSystemDialogs = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeSystemDialogsReceiver, closeSystemDialogs, RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag") // the flag is API 33
            registerReceiver(closeSystemDialogsReceiver, closeSystemDialogs)
        }
        displayManager.registerDisplayListener(displayListener, ui)
        ringWatcher.start()
        refreshBubble()
    }

    /**
     * The bubble's window used to be kept clear of the display cutout, which
     * put its origin a cutout in from the display's corner; it is now laid out
     * against the whole display (see addBubble). A spot saved by such a version
     * is that much short of where the user left the bubble — add it back,
     * once, so an update doesn't move the bubble.
     *
     * Measured on Android 12 and 15, with and without a cutout: the old origin
     * was the cutout's safe inset on the left and top edges — nothing at all
     * on a display without one, whatever the system bars — except that a
     * cutout at the top counted as the whole status bar it sits in.
     */
    private fun migrateBubbleOrigin(prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_BUBBLE_ORIGIN_DISPLAY, false)) return
        val editor = prefs.edit().putBoolean(KEY_BUBBLE_ORIGIN_DISPLAY, true)
        if (bubbleX != UNSET && bubbleY != UNSET) {
            val cutout = cutoutKeepOut(displayMetrics().windowInsets)
            bubbleX += cutout.left
            bubbleY += cutout.top
            editor.putInt(KEY_BUBBLE_X, bubbleX).putInt(KEY_BUBBLE_Y, bubbleY)
        }
        editor.apply()
    }

    /**
     * The strips of the display its cutout takes up, edge by edge. A cutout
     * at the top counts as the whole status bar it sits in, which is how the
     * system itself keeps a window clear of it.
     */
    private fun cutoutKeepOut(insets: WindowInsets): Insets {
        val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
        if (cutout.top == 0) return cutout
        val statusBar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
        return Insets.of(cutout.left, maxOf(cutout.top, statusBar.top), cutout.right, cutout.bottom)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The display listener can run before this process has been handed
        // the new configuration, and then the display bounds it reads are
        // still the old ones. Here they are current — do the same again.
        resizeOverlayToDisplay()
        if (bubbleView != null && newConfig.densityDpi != bubbleDensityDpi) {
            // Display size changed: the bubble was sized in pixels when it
            // was built. Build it again.
            removeBubble()
            refreshBubble()
        } else {
            keepBubbleOnScreen()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(closeSystemDialogsReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        ringWatcher.stop()
        ui.removeCallbacks(settledRefresh)
        dropNotice()
        teardownOverlay()
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(closeSystemDialogsReceiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        ringWatcher.stop()
        ui.removeCallbacks(settledRefresh)
        dropNotice()
        teardownOverlay()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    /**
     * Whether the user has agreed to what this service reads (the disclosure
     * in the app). Until then it is switched on in name only: it looks at no
     * event, no window and no screen, and shows no bubble — being enabled in
     * Settings, which can be done without ever opening the app, is not that
     * agreement.
     */
    private fun consented(): Boolean = Prefs.hasAccessibilityConsent(this)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // (Nothing of ours is on screen to keep in step with, either.)
        if (!consented()) return
        // The power menu opens beneath the crop overlay, where it can be
        // neither seen nor used. No broadcast announces it, but its dialog's
        // window appearing does arrive here.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            isPowerMenu(event)
        ) {
            yieldToSystemUi()
        }
        // Window transitions (e.g. the keyguard appearing over the crop overlay)
        // re-evaluate — this is what catches the lock screen while still awake.
        refreshBubble()
        ui.removeCallbacks(settledRefresh)
        ui.postDelayed(settledRefresh, WINDOW_EVENT_SETTLE_MS)
    }

    override fun onInterrupt() = Unit

    // --- Screen capture: bubble path + assistant fallback/clean re-capture --

    fun capture(callback: (Bitmap?) -> Unit) {
        if (!consented()) return callback(null)
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
        if (!consented()) return null
        val bounds = displayMetrics().bounds
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
            // (No filter on our own package: this app's main screen is an
            // application window like any other, and "Test capture" there is
            // where snapping gets tried first. None of our overlays is on
            // screen when this runs, and none is an application window.)
            val root = window.root ?: continue
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
     *  Returns the remaining node budget. Below [SNAP_MAX_DEPTH] a branch is
     *  simply not descended into — its children are never fetched (each
     *  fetch is IPC), and the walk carries on with the siblings. Only
     *  running out of budget ends the whole walk. */
    private fun walkSnapNodes(
        node: AccessibilityNodeInfo,
        depth: Int,
        budgetIn: Int,
        display: Rect,
        minSide: Int,
        out: MutableSet<Rect>
    ): Int {
        if (budgetIn <= 0) return 0
        var budget = budgetIn - 1
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
        if (depth >= SNAP_MAX_DEPTH) return budget
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

    /** Show [percent] on the bubble without storing it — the settings
     *  slider previews here while dragging and saves on release. */
    fun previewBubbleOpacity(percent: Int) {
        ui.post {
            previewOpacity = percent
            bubbleView?.alpha = percent / 100f
        }
    }

    /** The slider's value was saved: the preview, if any, is over. */
    private fun applySavedBubbleOpacity(percent: Int) {
        ui.post {
            previewOpacity = null
            bubbleView?.alpha = percent / 100f
        }
    }

    /** Forget the saved position so the bubble returns to its default spot. */
    fun resetBubblePosition() {
        bubbleX = UNSET
        bubbleY = UNSET
    }

    /**
     * The whole display's size and insets. Deliberately not
     * `currentWindowMetrics`, which describes "this context's window" — for a
     * service that is whatever the process's configuration says, and nothing
     * here is meant to depend on what windows the app's own activity is in.
     */
    private fun displayMetrics(): WindowMetrics = windowManager.maximumWindowMetrics

    /** True when the screen is awake — but beware: dreaming (AOD after a screen
     *  timeout) still counts as interactive, hence the separate [dreaming] flag. */
    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** The screen is awake and unlocked — the tool/bubble may be shown. */
    private fun isScreenUsable(): Boolean =
        isScreenInteractive() && !dreaming && !keyguardLocked()

    /** Bubble is shown only when enabled and agreed to, no tool open, the
     *  user is present, and the screen is usable — hidden on lock screen and
     *  AOD even when the device is trust-unlocked (Extend Unlock). */
    private fun shouldShowBubble(): Boolean =
        isBubbleEnabled(this) && consented() && !capturing && overlayView == null &&
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
            contentDescription = getString(R.string.bubble_description)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 52, 43, 109))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            alpha = (previewOpacity ?: getBubbleOpacity(this@ScreenshotAccessibilityService)) / 100f
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position the window against the whole display, not the part
            // left over once system bars and cutouts are taken off. Otherwise
            // its origin sits a status bar (or a side cutout) in from the
            // corner, and a spot clamped to the display's size ends up that
            // much beyond its edge.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        placeBubbleOnScreen(params)
        attachBubbleTouch(icon, params)
        // The window token dies with the service: a refreshBubble posted just
        // before unbind can run after it, where addView throws BadTokenException.
        if (runCatching { windowManager.addView(icon, params) }.isFailure) return
        bubbleView = icon
        bubbleDensityDpi = resources.configuration.densityDpi
    }

    /**
     * A short message in a window of this service's own (see [notify]): the
     * same dark pill as the crop tool's hint, near the bottom of the screen,
     * gone again by itself. It takes no touches and no focus.
     */
    private fun showNotice(message: String) = ui.post {
        dropNotice()
        val display = displayMetrics().bounds
        val notice = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 30, 30, 30))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            maxWidth = display.width() - 2 * dp(24)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
            // What a screen reader announces when the window appears.
            title = message
        }
        // Guarded like addBubble: the window token dies with the service.
        if (runCatching { windowManager.addView(notice, params) }.isFailure) return@post
        noticeView = notice
        ui.postDelayed(removeNotice, NOTICE_MS)
    }

    /** Take the notice down now, ahead of its timer. */
    private fun dropNotice() {
        ui.removeCallbacks(removeNotice)
        removeNotice.run()
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    /**
     * Point [params] at the remembered bubble spot — choosing the default
     * one if there is none — pulled inside the current display. Returns
     * whether that changed them.
     *
     * Only the window is clamped. The remembered spot stays where the user
     * put it (until they drag the bubble somewhere else), so a spot that
     * doesn't fit one orientation is back in place in the other.
     */
    private fun placeBubbleOnScreen(params: WindowManager.LayoutParams): Boolean {
        val metrics = displayMetrics()
        val display = metrics.bounds
        if (bubbleX == UNSET || bubbleY == UNSET) {
            // No spot yet (first run, or reset from the app): default to the
            // right edge, a third of the way down — the right edge of what's
            // usable, that is: in landscape a navigation bar or a cutout can
            // occupy that side, and a bubble parked on the bar would take its
            // touches.
            val occupied = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            bubbleX = display.width() - occupied.right - params.width - dp(8)
            bubbleY = display.height() / 3
        }
        // Inside the display, and clear of its cutout: a spot that is fine
        // in one orientation can otherwise come to lie under the camera in
        // another.
        val range = bubbleRange(params, metrics)
        val x = bubbleX.coerceIn(range.left, range.right)
        val y = bubbleY.coerceIn(range.top, range.bottom)
        if (x == params.x && y == params.y) return false
        params.x = x
        params.y = y
        return true
    }

    /**
     * Where the bubble's top-left corner may be for all of it to lie inside
     * the display and clear of its cutout: x from left to right, y from top
     * to bottom, inclusive.
     */
    private fun bubbleRange(
        params: WindowManager.LayoutParams,
        metrics: WindowMetrics = displayMetrics()
    ): Rect {
        val display = metrics.bounds
        val cutout = cutoutKeepOut(metrics.windowInsets)
        return Rect(
            cutout.left,
            cutout.top,
            (display.width() - cutout.right - params.width).coerceAtLeast(cutout.left),
            (display.height() - cutout.bottom - params.height).coerceAtLeast(cutout.top)
        )
    }

    /**
     * Re-place a showing bubble after the display changed size (rotation,
     * resolution). Its window is laid out with no limits, so nothing else
     * stops a spot from the tall orientation lying beyond the edge of the
     * wide one, out of reach.
     */
    private fun keepBubbleOnScreen() {
        val view = bubbleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (placeBubbleOnScreen(params)) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun attachBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        var longPressed = false
        var range = Rect()
        // The touch listener below eats every event, so the view's own
        // long-press detection never runs: time it here instead.
        val longPress = Runnable {
            // Taken down mid-press (screen locked, a capture began): the
            // press is gone with it.
            if (bubbleView !== view) return@Runnable
            longPressed = true
            view.performLongClick() // with the long-press haptic
        }
        // The click is what assistive tech invokes (Switch Access and Voice
        // Access only offer views with a click action); touch reaches it
        // through performClick below.
        view.setOnClickListener { triggerCapture() }
        // Returning false when nothing opens also skips the long-press haptic.
        view.setOnLongClickListener { openApp() }
        // Name the long press for screen readers ("double-tap and hold to
        // open Circle to Share") instead of a bare "long press".
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View, info: AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(
                    AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        getString(
                            R.string.bubble_long_press_action, getString(R.string.app_name)
                        )
                    )
                )
            }
        }
        // Clickable, but never focusable — which a click listener makes a
        // view by default. The bubble's window can't take key input anyway,
        // yet a focusable view in it still grabs "default focus" whenever
        // the window is created during keyboard navigation (D-pad, Tab, a
        // remote), and the framework then paints its focus highlight over
        // the view's square bounds: a grey box around the round bubble that
        // stays, because a window that never gets input focus never hears
        // that touch mode has resumed.
        view.isFocusable = false
        view.defaultFocusHighlightEnabled = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    moved = false
                    longPressed = false
                    // A DOWN without an UP/CANCEL before it would otherwise
                    // leave the earlier press's timer running too.
                    ui.removeCallbacks(longPress)
                    ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    // A drag stays within what placeBubbleOnScreen allows:
                    // dropped beyond it (the window has no limits of its
                    // own), the bubble would sit half off the screen or on
                    // the status bar until the next time it is put back —
                    // and then jump.
                    range = bubbleRange(params)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // The app is opening; the rest of this touch is spent.
                    if (longPressed) return@setOnTouchListener true
                    val dx = (event.rawX - startX)
                    val dy = (event.rawY - startY)
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        ui.removeCallbacks(longPress)
                    }
                    // Only a drag moves the bubble or its remembered spot. A
                    // tap's jitter must not: while the window is clamped
                    // (see placeBubbleOnScreen) it would overwrite the spot
                    // the bubble is meant to return to.
                    if (moved) {
                        params.x = (originX + dx.toInt()).coerceIn(range.left, range.right)
                        params.y = (originY + dy.toInt()).coerceIn(range.top, range.bottom)
                        bubbleX = params.x
                        bubbleY = params.y
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (longPressed) return@setOnTouchListener true
                    if (!moved) v.performClick() else persistBubblePosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPress)
                    // A drag can end in a cancel (a system gesture steals the
                    // pointer); the bubble already moved, so persist like UP.
                    if (moved) persistBubblePosition()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Bring up the app's settings screen. Allowed from here because the
     * system binds this service. Returns whether it went out; when it
     * didn't, says so.
     *
     * Like the Quick Settings tile's, plus SINGLE_TOP: the bubble can be
     * long-pressed beside an open settings screen (split screen), which is
     * then kept rather than rebuilt with what is half-typed there lost. The
     * tile must not keep it — a DisclosureActivity that CLEAR_TOP closes
     * above it would report a decline. The bubble never meets one: it only
     * shows once the disclosure is agreed to.
     */
    private fun openApp(): Boolean {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            showNotice(getString(R.string.bubble_open_failed, getString(R.string.app_name)))
        }
        return opened
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
        if (!consented()) return Refusal.NO_CONSENT
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
        if (dismissShade()) {
            awaitShadeCollapsed(gen)
        } else {
            ui.postDelayed({ runCapture(gen) }, BUBBLE_HIDE_DELAY_MS)
        }
        return Refusal.NONE
    }

    /**
     * Ask SystemUI to collapse the notification shade; false if there was no
     * shade to collapse.
     *
     * Android 12 added a global action for this. Android 11 has none, but
     * still lets an app send the close-system-dialogs broadcast (blocked from
     * 12 on), which collapses the shade the same way. The broadcast reports
     * nothing back, so look for the shade first.
     *
     * closeSystemDialogsReceiver hears that broadcast as well, and ignores
     * it: it only acts on the reasons the system gives, and this has none.
     */
    // Lint judges the broadcast by Android 12's rules (a permission no app
    // can hold) and by our own receiver for the same action; it is only ever
    // sent on Android 11, to SystemUI.
    @SuppressLint("MissingPermission", "UnsafeImplicitIntentLaunch")
    private fun dismissShade(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        if (!shadeExpanded()) return false
        @Suppress("DEPRECATION")
        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        return true
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
     *
     * "Big" means more than half the display in BOTH dimensions. Height alone
     * also matches the navigation bar when it sits on a side edge (landscape
     * with 3-button navigation) — a full-height system window that never goes
     * away, which would hold every capture for the whole timeout.
     */
    private fun shadeExpanded(): Boolean {
        val display = displayMetrics().bounds
        val bounds = Rect()
        return windows.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(bounds)
            bounds.height() > display.height() / 2 && bounds.width() > display.width() / 2
        }
    }

    /**
     * Whether a window event is the power menu's dialog appearing.
     *
     * Up to Android 16 the dialog is a class of its own in SystemUI's
     * globalactions package, and the event carries that name. From Android 17
     * it is a plain SystemUIDialog like dozens of others, so the name says
     * nothing. What the event still carries is the title SystemUI gives the
     * menu for screen readers — the system's own "Phone options" string,
     * which can be looked up in whatever language the device is set to.
     *
     * The event is all there is to go by: with the crop overlay covering it,
     * the menu's window is left out of the window list, and nothing in it
     * can be looked at. (A skin that names and titles its power menu
     * differently just keeps the overlay up, as before.)
     */
    private fun isPowerMenu(event: AccessibilityEvent): Boolean {
        if (event.className?.contains("globalactions", ignoreCase = true) == true) return true
        if (event.packageName?.toString() != SYSTEM_UI_PACKAGE) return false
        val title = powerMenuTitle() ?: return false
        return event.text.any { it?.toString() == title }
    }

    private fun powerMenuTitle(): String? {
        val system = Resources.getSystem()
        val id = system.getIdentifier("global_actions", "string", "android")
        return if (id != 0) system.getString(id) else null
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
        if (!consented()) return
        val gen = beginCapture() ?: return
        ui.postDelayed({ runCapture(gen) }, delayMs)
    }

    /** Claim the capture latch and hide the bubble, returning the generation
     *  that now owns it — or null if a tool is already open/opening. */
    private fun beginCapture(): Int? {
        if (capturing) return null
        capturing = true
        removeBubble()
        dropNotice() // like the bubble, it would be in the picture
        pendingCaptureGen = ++bubbleCaptureGen
        return pendingCaptureGen
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
                    showNotice(getString(R.string.error_capture_failed))
                    abandonCapture()
                }
            }
        }
    }

    /** Give up an in-flight capture and release the latch, so a screen that
     *  locked mid-capture doesn't leave the tool wedged shut. */
    private fun abandonCapture() {
        pendingCaptureGen = NO_CAPTURE
        capturing = false
        refreshBubble()
    }

    /**
     * Call off a bubble/tile capture that has claimed the latch but not put
     * its overlay up yet — the shade may still be collapsing, the screenshot
     * still on its way. Whatever is in flight for it finds itself superseded,
     * exactly as when the assistant takes over. No-op when there is no such
     * capture, including when the latch is the assistant's.
     */
    private fun cancelPendingCapture() {
        if (pendingCaptureGen != bubbleCaptureGen) return
        bubbleCaptureGen++
        abandonCapture()
    }

    /**
     * Something of the system's wants the screen: the user went Home or to
     * Recents, or opened the power menu or an assistant, or a call is coming
     * in. Close the crop
     * overlay — or, if it has not appeared yet, see that it doesn't: opening
     * a second later, it would land on top of where the user has just gone.
     */
    private fun yieldToSystemUi() {
        if (overlayView != null) dismissOverlay() else cancelPendingCapture()
        onYieldToSystemUi?.invoke()
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
            pendingCaptureGen = NO_CAPTURE
            ui.post { teardownOverlay(); removeBubble(); dropNotice() }
        } else {
            refreshBubble()
        }
    }

    /** True if the floating bubble is currently on screen. */
    fun isBubbleShowing(): Boolean = bubbleView != null

    /** True if a notice (see [notify]) is currently on screen. */
    fun isNoticeShowing(): Boolean = noticeView != null

    /** The clean screenshot behind the open bubble crop overlay (no bubble in
     *  it), for the assistant to reuse instead of a system shot that would
     *  include the overlay. Null if no bubble overlay is open. */
    fun openOverlayScreenshot(): Bitmap? = if (overlayView != null) overlayBitmap else null

    /** The snap bounds that go with [openOverlayScreenshot] — collected when
     *  that screenshot was taken, so they describe what is in the picture
     *  rather than whatever the app underneath has changed to since. */
    fun openOverlaySnapBounds(): SnapBounds? = if (overlayView != null) overlaySnap else null

    private fun presentOverlay(bitmap: Bitmap, snap: SnapBounds?) {
        ShareHelper.cleanupCache(this)
        teardownOverlay()
        pendingCaptureGen = NO_CAPTURE // it is being presented now
        overlayGen = bubbleCaptureGen
        overlayBitmap = bitmap
        overlaySnap = snap

        // (The screen itself takes no keyboard focus: Back reaches it without
        // — see CropScreen.dispatchKeyEvent — and a focused root would keep
        // arrow keys from ever reaching its buttons.)
        val screen = CropScreen(this)
        screen.callbacks = object : CropScreen.Callbacks {
            override fun onDeliver(share: Boolean, rect: Rect?) {
                val ok = try {
                    ShareHelper.deliver(this@ScreenshotAccessibilityService, bitmap, rect, share)
                } catch (e: IOException) {
                    showNotice(getString(R.string.error_save_failed))
                    true
                } catch (e: Exception) {
                    showNotice(getString(R.string.error_capture_failed))
                    true
                }
                if (ok) dismissOverlay()
            }

            override fun onCancel() = dismissOverlay()
        }
        // Cancel on the back key.
        screen.onBackKey = { dismissOverlay() }

        val frame = overlayFrame()
        overlayDisplay = displayState()
        val params = WindowManager.LayoutParams(
            frame.width(),
            frame.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not touch-modal: a window that takes key input otherwise
            // swallows touches outside its own bounds too — and the point of
            // stopping short of the navigation bar is that taps there reach
            // the bar.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.left
            y = frame.top
            // Positioned against the whole display, like the bubble.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        screen.setDisplayFrame(displayFrameWithin(frame))
        // Guarded like addBubble: the window token dies with the service, and
        // an unguarded throw here would strand `capturing` at true with no
        // overlay for refreshBubble to tear down — wedging every future
        // capture, from every path, until the service is toggled.
        if (runCatching { windowManager.addView(screen, params) }.isFailure) {
            overlayBitmap = null
            overlaySnap = null
            // Restore the "no overlay is open" invariant overlayGen documents —
            // it was claimed just above, and leaving it pointing at the live
            // generation would let a future overlayGen-only check clear a latch
            // the assistant owns.
            overlayGen = -1
            abandonCapture()
            return
        }
        overlayView = screen
        // onBackKey only sees Back where the platform still
        // sends KEYCODE_BACK to the view tree. Once predictive back is on for
        // the app — the default from Android 16 at this targetSdk — it no
        // longer does, and Back reaches a window only through a callback like
        // this one. (Where predictive back is off, the dispatcher ignores the
        // registration and onBackKey does the job.) The dispatcher
        // belongs to the window, so it goes away with the overlay.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            screen.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { dismissOverlay() }
        }
        screen.setBitmap(bitmap, snap)
    }

    /** Re-fit an open crop overlay to the current display bounds (rotation). */
    private fun resizeOverlayToDisplay() {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val display = displayState()
        if (display == overlayDisplay) return // see overlayDisplay
        overlayDisplay = display
        val frame = overlayFrame()
        // The display can change under an unchanged frame (a flip between
        // the two landscapes moves nothing but the bar's side), so always
        // pass it on; the view ignores a repeat.
        view.setDisplayFrame(displayFrameWithin(frame))
        if (params.x == frame.left && params.y == frame.top &&
            params.width == frame.width() && params.height == frame.height()
        ) {
            return
        }
        params.x = frame.left
        params.y = frame.top
        params.width = frame.width()
        params.height = frame.height()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Where the crop overlay's window goes, in display coordinates: the whole
     * display, so the dim covers the status bar too — except a navigation bar
     * with buttons on it (3-button navigation). An accessibility overlay sits
     * above that bar; covering it would leave Back, Home and Recents showing
     * through the frozen picture but dead to the touch. The gesture bar is
     * no such obstacle (its swipes are read above every window) and stays
     * covered — as does a large screen's taskbar, see below.
     */
    private fun overlayFrame(): Rect {
        val metrics = displayMetrics()
        val display = metrics.bounds
        // On a tablet or an unfolded foldable the tappable strip is a
        // taskbar: app icons as well as navigation buttons. Leaving that
        // free would let an app be launched underneath the frozen picture
        // with nothing to close it, so there the display is covered whole.
        // (Measured on the display itself — the configuration's "smallest
        // width" is the app's own window's when that is in split-screen.)
        val smallestWidthDp = minOf(display.width(), display.height()) / resources.displayMetrics.density
        if (smallestWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) {
            return Rect(0, 0, display.width(), display.height())
        }
        // As currently shown: a bar an immersive app has hidden is in nobody's
        // way, and the display is covered whole.
        val bar = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
        // "Tappable" is what tells a button bar from the gesture bar.
        val tappable = metrics.windowInsets.getInsets(WindowInsets.Type.tappableElement())
        return Rect(
            minOf(bar.left, tappable.left),
            0,
            display.width() - minOf(bar.right, tappable.right),
            display.height() - minOf(bar.bottom, tappable.bottom)
        )
    }

    private fun displayState(): DisplayState {
        val metrics = displayMetrics()
        // Where the bars are, whether or not they are showing. Asked of the
        // system afresh each time — unlike the display's rotation, which a
        // service reads from its process configuration, and that can still
        // be the old one when a display change is first reported (and for a
        // flip straight from one landscape to the other, nothing announces
        // its arrival later).
        val insets = metrics.windowInsets
        return DisplayState(
            metrics.bounds.width(),
            metrics.bounds.height(),
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()),
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
        )
    }

    /** The whole display as seen from a window at [frame] — what the crop
     *  view lays the screenshot out against. */
    private fun displayFrameWithin(frame: Rect): RectF {
        val display = displayMetrics().bounds
        return RectF(
            -frame.left.toFloat(), -frame.top.toFloat(),
            (display.width() - frame.left).toFloat(), (display.height() - frame.top).toFloat()
        )
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
        overlayDisplay = null
        overlayBitmap = null
        overlaySnap = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
