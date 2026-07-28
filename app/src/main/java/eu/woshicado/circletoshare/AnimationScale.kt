package eu.woshicado.circletoshare

import android.content.Context
import android.provider.Settings

/**
 * How long to wait for a system animation to finish before capturing behind it.
 *
 * Several capture paths have to outlast an animation they didn't start — the
 * assistant's zoom-out, the Quick Settings shade collapsing — and each has its
 * own measured [baseMs] at 1x. All of them must respect the user's animation
 * scale settings, hence one shared helper.
 *
 * Returns [baseMs] scaled up for slower-than-normal animations, or [offMs]
 * when the user has animations switched off entirely (nothing to wait for, but
 * a frame or two is still needed for the teardown to land). Neither has a
 * default: each caller waits out a different animation and has to justify its
 * own numbers.
 *
 * Reading `Settings.Global` needs no permission. Cheap enough for a click path
 * (three cached provider reads, and the result is immediately spent on a
 * `postDelayed`), but don't call it per-frame or in a loop.
 */
fun animationSettleMs(context: Context, baseMs: Float, offMs: Long): Long {
    val r = context.contentResolver
    val scale = maxOf(
        Settings.Global.getFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, 1f),
        Settings.Global.getFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
        Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )
    return if (scale <= 0f) offMs else (baseMs * scale.coerceAtLeast(1f)).toLong()
}
