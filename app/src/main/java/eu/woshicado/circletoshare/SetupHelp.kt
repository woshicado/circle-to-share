package eu.woshicado.circletoshare

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnPreDraw
import java.nio.ByteBuffer
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
    button.setOnClickListener { showSetupHelp(button, title, clip, steps) }
}

private fun AppCompatActivity.showSetupHelp(
    anchor: View,
    @StringRes title: Int,
    @RawRes clip: Int,
    @StringRes steps: Int,
) {
    val card = AnchoredCard(this, anchor)
    val content = card.inflate(R.layout.popup_setup_help)
    content.findViewById<TextView>(R.id.help_title).setText(title)
    content.findViewById<TextView>(R.id.help_steps).setText(steps)
    val clipView = content.findViewById<ImageView>(R.id.help_clip)

    // From bytes rather than from the resource: a resource source would be
    // scaled from mdpi to the screen's density, and blurred with it.
    val bytes = resources.openRawResource(clip).use { it.readBytes() }
    val animation = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        as AnimatedImageDrawable
    animation.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    clipView.setImageDrawable(animation)
    clipView.setOnClickListener { if (animation.isRunning) animation.stop() else animation.start() }

    // The recording is portrait and taller than most screens have room for
    // next to the "?", so it shrinks (keeping its shape) to what is left on
    // the roomier side once the words are laid out.
    clipView.visibility = View.GONE
    content.measure(
        View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val textHeight = content.measuredHeight + (clipView.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    clipView.visibility = View.VISIBLE
    val aspect = animation.intrinsicWidth.toFloat() / animation.intrinsicHeight
    val maxClipWidth = card.width - content.paddingLeft - content.paddingRight
    val minClipHeight = MIN_CLIP_HEIGHT_DP * resources.displayMetrics.density
    val clipHeight = min(maxClipWidth / aspect, (card.room - textHeight).toFloat()).coerceAtLeast(minClipHeight)
    clipView.layoutParams = clipView.layoutParams.apply {
        width = (clipHeight * aspect).roundToInt()
        height = clipHeight.roundToInt()
    }

    val popup = card.show(content) { animation.stop() }
    // Started only once the clip has been drawn: the drawable times its
    // frames from its previous draw, and started before the first one it
    // jumps seconds ahead, skipping the start of the recording.
    clipView.doOnPreDraw { clipView.post { if (popup.isShowing) animation.start() } }
}

// Below this the recording is too small to make out; on a screen that short
// the card overlaps the "?" instead.
private const val MIN_CLIP_HEIGHT_DP = 160
