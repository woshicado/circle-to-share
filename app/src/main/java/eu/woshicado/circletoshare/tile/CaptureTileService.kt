package eu.woshicado.circletoshare.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import eu.woshicado.circletoshare.MainActivity
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.animationSettleMs
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService

/**
 * Quick Settings tile: a third way into the crop overlay, alongside the
 * assistant gesture and the floating bubble — without either the assistant
 * role or a permanently visible button.
 *
 * The tile is a momentary action, not a toggle, so it never reports
 * [Tile.STATE_ACTIVE]. It rides on the accessibility service (the same engine
 * as the bubble); with that service off there is nothing to capture with, so
 * the tile opens the app instead of failing silently.
 */
class CaptureTileService : TileService() {

    companion object {
        // Long enough for the keyguard-exit transition to flip
        // isKeyguardLocked(), short enough not to feel like a hang. Scaled
        // like every other wait here: the exit is an animation, and at 2x a
        // fixed 400ms would retry while still locked and drop the capture the
        // user just authenticated for.
        private const val KEYGUARD_EXIT_GRACE_MS = 400f
        private const val KEYGUARD_EXIT_GRACE_OFF_MS = 100L
        private const val KEYGUARD_EXIT_GRACE_MAX_MS = 1500L

        // How long after asking for an unlock a further tap is taken for the
        // same request. The prompt covers the tile well within this.
        private const val UNLOCK_PROMPT_DEBOUNCE_MS = 1000L
    }

    private val ui = Handler(Looper.getMainLooper())

    // When an unlock was last asked for (uptime), so taps in quick succession
    // on the lock screen can't stack unlock prompts and retries. A moment in
    // time rather than a flag waiting to be cleared: nothing reports a prompt
    // the user backs out of, and a flag left set would keep the tile dead
    // until the shade is closed and opened again.
    private var unlockPromptedAt = Long.MIN_VALUE

    override fun onStopListening() {
        // The unlock collapses the shade, which is exactly what unbinds us —
        // don't leave a posted retry holding this service alive to toast from
        // a dead context.
        ui.removeCallbacksAndMessages(null)
        super.onStopListening()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            // Always INACTIVE, never UNAVAILABLE: SystemUI swallows clicks on
            // an unavailable tile, and a dead tap with no explanation is worse
            // than a live one that opens the app and says what's missing. A
            // newly added tile defaults to ACTIVE (highlighted), which would
            // wrongly read as "capture is on".
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        // Handle "no accessibility service" here, before any unlock prompt:
        // startActivityAndCollapse only works while the click's window token is
        // granted, and SystemUI revokes that once the tile stops listening —
        // so the same call made after unlockAndRun is silently dropped, and the
        // user would have authenticated for nothing.
        if (ScreenshotAccessibilityService.instance == null) {
            Toast.makeText(this, R.string.error_tile_needs_accessibility, Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        capture(mayPromptUnlock = true)
    }

    /**
     * Capturing over a lock screen would just photograph the lock screen, so
     * the service refuses while the keyguard is up and we get through the
     * unlock first.
     *
     * We deliberately don't consult `isLocked` to decide that: it is
     * isDeviceLocked, which a trust agent (Extend Unlock) reports as false
     * while the keyguard is still on screen — reading it here would leave the
     * tile a silent no-op in exactly that case. The service's own keyguard
     * check is authoritative; [Refusal.LOCKED] is what drives the prompt.
     */
    private fun capture(mayPromptUnlock: Boolean) {
        // Re-read rather than capture it: on the post-unlock retry the service
        // may have gone away while the user was authenticating. Only toast
        // there — openApp() can no longer collapse the shade (see onClick).
        val service = ScreenshotAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, R.string.error_tile_needs_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        when (service.triggerCaptureFromShade()) {
            ScreenshotAccessibilityService.Refusal.NONE -> Unit
            ScreenshotAccessibilityService.Refusal.LOCKED ->
                if (mayPromptUnlock) promptUnlockThenCapture() else notifyLocked()
            // The shade stays open, which is the feedback: a crop tool is
            // already up and about to be visible anyway.
            ScreenshotAccessibilityService.Refusal.BUSY -> Unit
        }
    }

    private fun promptUnlockThenCapture() {
        val now = SystemClock.uptimeMillis()
        // (Subtraction on its own could overflow against the initial value.)
        if (unlockPromptedAt != Long.MIN_VALUE && now - unlockPromptedAt < UNLOCK_PROMPT_DEBOUNCE_MS) {
            return // the prompt for this is already on its way up
        }
        unlockPromptedAt = now
        unlockAndRun {
            // SystemUI can run this as the keyguard starts exiting, while
            // isKeyguardLocked() is still true — retrying immediately would be
            // refused again and drop the capture the user just authenticated
            // for. One grace period, then a single attempt that can't re-prompt.
            ui.postDelayed(
                { capture(mayPromptUnlock = false) },
                animationSettleMs(
                    this,
                    baseMs = KEYGUARD_EXIT_GRACE_MS,
                    offMs = KEYGUARD_EXIT_GRACE_OFF_MS
                ).coerceAtMost(KEYGUARD_EXIT_GRACE_MAX_MS)
            )
        }
        // (A cancelled unlock never runs the block above.)
    }

    /** Last resort: the unlock went through but the keyguard is somehow still
     *  up. Say so rather than leaving the tap looking broken. */
    private fun notifyLocked() =
        ScreenshotAccessibilityService.notify(this, R.string.error_tile_locked)

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            // The Intent overload throws UnsupportedOperationException on 34+,
            // but below 34 it is the only one there is.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
