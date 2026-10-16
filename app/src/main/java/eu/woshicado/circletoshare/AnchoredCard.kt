package eu.woshicado.circletoshare

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.max
import kotlin.math.min

/**
 * A card that pops up beside the button that opened it: below it, or above
 * when there is more room there. The setup helpers ("?") and the Play
 * build's tip picker both open one, so they look and behave alike: one card
 * at a time per screen, closed by a tap outside, Back, or leaving the screen.
 *
 * Its content is laid out to [width]; a part that can shrink (the setup
 * recordings) fits itself into [room] before [show].
 */
internal class AnchoredCard(
    private val activity: AppCompatActivity,
    private val anchor: View,
) : DefaultLifecycleObserver {

    private val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
    private val anchorAt = IntArray(2).also { anchor.getLocationOnScreen(it) }
    private val margin = dp(16)
    private val below = frame.bottom - (anchorAt[1] + anchor.height) - margin
    private val above = anchorAt[1] - frame.top - margin

    val width = min(dp(MAX_WIDTH_DP), frame.width() - 2 * margin)

    /** The height free on the roomier side of the anchor. */
    val room = max(below, above)

    private var popup: PopupWindow? = null

    // A popup's content has no parent to inflate against.
    @SuppressLint("InflateParams")
    fun inflate(@LayoutRes layout: Int): View = activity.layoutInflater.inflate(layout, null)

    /** Show [content]; [onDismiss] runs once it closes, however it does. */
    fun show(content: View, onDismiss: () -> Unit = {}): PopupWindow {
        // One card at a time: the open one (if any) is kept on the window's
        // root view, which every card on the screen shares.
        val root = anchor.rootView
        (root.getTag(R.id.anchored_card) as? PopupWindow)?.dismiss()

        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val x = (anchorAt[0] + anchor.width - width).coerceIn(frame.left + margin, frame.right - margin - width)
        val y = if (below >= above) anchorAt[1] + anchor.height else anchorAt[1] - content.measuredHeight

        val card = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        val elevation = dp(6).toFloat()
        card.setBackgroundDrawable(
            MaterialShapeDrawable.createWithElevationOverlay(activity, elevation).apply {
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(dp(16).toFloat()).build()
                fillColor = ColorStateList.valueOf(
                    MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh)
                )
            }
        )
        card.elevation = elevation
        card.animationStyle = android.R.style.Animation_Dialog
        card.setOnDismissListener {
            onDismiss()
            activity.lifecycle.removeObserver(this)
            if (root.getTag(R.id.anchored_card) === card) root.setTag(R.id.anchored_card, null)
        }
        popup = card
        root.setTag(R.id.anchored_card, card)
        // Gone with the screen (rotation, leaving it) rather than leaked.
        activity.lifecycle.addObserver(this)
        // LEFT, not START: x is measured from the screen's left edge, and
        // START would mirror it in a right-to-left layout.
        card.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
        return card
    }

    override fun onStop(owner: LifecycleOwner) {
        popup?.dismiss()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_WIDTH_DP = 320
    }
}
