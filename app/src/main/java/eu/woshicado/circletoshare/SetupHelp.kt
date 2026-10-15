package eu.woshicado.circletoshare

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The "?" beside a setup step. Tapping it opens a card anchored to it that
 * loops a short screen recording of the step in Android's Settings, with the
 * same steps in words below (which is also what TalkBack reads). Every helper
 * goes through here, so they all look and behave alike.
 *
 * [clip] is a looping animated WebP in res/raw: raw, so Android doesn't
 * scale it for the screen density.
 */
internal fun AppCompatActivity.attachSetupHelp(
    button: View,
    @StringRes title: Int,
    @RawRes clip: Int,
    @StringRes steps: Int,
) {
    val description = getString(R.string.help_button_description, getString(title))
    button.contentDescription = description
    TooltipCompat.setTooltipText(button, description)
    button.setOnClickListener { SetupHelpCard(this, button, title, clip, steps).show() }
}

private class SetupHelpCard(
    private val activity: AppCompatActivity,
    private val anchor: View,
    @StringRes private val title: Int,
    @RawRes private val clip: Int,
    @StringRes private val steps: Int,
) : DefaultLifecycleObserver {

    private lateinit var popup: PopupWindow
    private lateinit var animation: AnimatedImageDrawable

    // A popup's content has no parent to inflate against.
    @SuppressLint("InflateParams")
    fun show() {
        // One card at a time: the open one (if any) is kept on the window's
        // root view, which every helper on the screen shares.
        val root = anchor.rootView
        (root.getTag(R.id.setup_help_card) as? PopupWindow)?.dismiss()

        val content = activity.layoutInflater.inflate(R.layout.popup_setup_help, null)
        content.findViewById<TextView>(R.id.help_title).setText(title)
        content.findViewById<TextView>(R.id.help_steps).setText(steps)
        val clipView = content.findViewById<ImageView>(R.id.help_clip)

        // From bytes rather than from the resource: a resource source would
        // be scaled from mdpi to the screen's density, and blurred with it.
        val bytes = activity.resources.openRawResource(clip).use { it.readBytes() }
        animation = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            as AnimatedImageDrawable
        animation.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        clipView.setImageDrawable(animation)
        clipView.setOnClickListener { if (animation.isRunning) animation.stop() else animation.start() }

        val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val margin = dp(16)
        val width = min(dp(MAX_WIDTH_DP), frame.width() - 2 * margin)

        // The recording is portrait and taller than most screens have room
        // for next to the "?", so it shrinks (keeping its shape) to what is
        // left on the roomier side once the words are laid out.
        val anchorAt = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val below = frame.bottom - (anchorAt[1] + anchor.height) - margin
        val above = anchorAt[1] - frame.top - margin
        clipView.visibility = View.GONE
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val textHeight = content.measuredHeight + (clipView.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        clipView.visibility = View.VISIBLE
        val aspect = animation.intrinsicWidth.toFloat() / animation.intrinsicHeight
        val maxClipWidth = width - content.paddingLeft - content.paddingRight
        val clipHeight = min(maxClipWidth / aspect, (max(below, above) - textHeight).toFloat())
            .coerceAtLeast(dp(MIN_CLIP_HEIGHT_DP).toFloat())
        clipView.layoutParams = clipView.layoutParams.apply {
            this.width = (clipHeight * aspect).roundToInt()
            this.height = clipHeight.roundToInt()
        }
        val height = textHeight + clipHeight.roundToInt()

        val x = (anchorAt[0] + anchor.width - width).coerceIn(frame.left + margin, frame.right - margin - width)
        val y = if (below >= above) anchorAt[1] + anchor.height else anchorAt[1] - height

        popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            val elevation = dp(6).toFloat()
            setBackgroundDrawable(
                MaterialShapeDrawable.createWithElevationOverlay(activity, elevation).apply {
                    shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCornerSizes(dp(16).toFloat()).build()
                    fillColor = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh
                        )
                    )
                }
            )
            this.elevation = elevation
            animationStyle = android.R.style.Animation_Dialog
            setOnDismissListener {
                animation.stop()
                activity.lifecycle.removeObserver(this@SetupHelpCard)
                if (root.getTag(R.id.setup_help_card) === popup) root.setTag(R.id.setup_help_card, null)
            }
        }
        root.setTag(R.id.setup_help_card, popup)
        // Gone with the screen (rotation, leaving it) rather than leaked.
        activity.lifecycle.addObserver(this)
        // LEFT, not START: x is measured from the screen's left edge, and
        // START would mirror it in a right-to-left layout.
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
        // Started only once the clip has been drawn: the drawable times its
        // frames from its previous draw, and started before the first one
        // it jumps seconds ahead, skipping the start of the recording.
        clipView.doOnPreDraw { clipView.post { if (popup.isShowing) animation.start() } }
    }

    override fun onStop(owner: LifecycleOwner) = popup.dismiss()

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_WIDTH_DP = 320

        // Below this the recording is too small to make out; on a screen
        // that short the card overlaps the "?" instead.
        const val MIN_CLIP_HEIGHT_DP = 160
    }
}
