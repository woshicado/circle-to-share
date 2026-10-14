package eu.woshicado.circletoshare

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Calls [onRinging] when a call starts ringing, so a crop tool that covers
 * the screen can get out of its way: the call's notification appears beneath
 * the tool like everything else of the system's, and the phone would ring
 * with nothing to see or answer.
 *
 * The ringing is observable without a permission, unlike the telephony call
 * state. Audible ringing only: with the ringer on vibrate or silent the
 * system never enters this mode, and nothing else announces the call that
 * this app could see without a phone permission or without listening in on
 * notifications, neither of which it is going to ask for. There the tool
 * stays up.
 *
 * The mode listener is Android 12's. On Android 11 this does nothing, and the
 * tool stays up through audible ringing too.
 *
 * [context] is not used before [start], so a service may pass itself from a
 * field initializer.
 */
class RingWatcher(private val context: Context, private val onRinging: () -> Unit) {

    // Typed loosely and created in start(): the listener interface itself
    // does not exist before Android 12.
    private var listener: Any? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || listener != null) return
        val onMode = AudioManager.OnModeChangedListener { mode ->
            if (mode == AudioManager.MODE_RINGTONE) onRinging()
        }
        listener = onMode
        context.getSystemService(AudioManager::class.java)
            .addOnModeChangedListener(context.mainExecutor, onMode)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val onMode = listener as? AudioManager.OnModeChangedListener ?: return
        listener = null
        context.getSystemService(AudioManager::class.java).removeOnModeChangedListener(onMode)
    }
}
