package eu.woshicado.circletoshare

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * at a time per screen, closed by a tap outside, Back, leaving the screen,
 * or its button going (its section folded away, or out of sight). It
 * follows a button that moves, while there is room beside it.
 *
 * Its content is laid out to [width]; a part that can shrink (the setup
 * recordings) fits itself into [room] before [show].
 */
internal class AnchoredCard(
    private val activity: AppCompatActivity,
    private val anchor: View,
) : DefaultLifecycleObserver {

    // Where things are in the window, not on the screen: that is what a
    // popup is placed by, and the two differ once the window is not the
    // whole screen (split screen, a freeform window).
    private val anchorAt = IntArray(2).also { anchor.getLocationInWindow(it) }
    private val frame = visibleFrame()

    /** What can be seen of the window (not what the keyboard is over), in
     *  the window's coordinates. */
    private fun visibleFrame(): Rect = Rect().also { visible ->
        anchor.getWindowVisibleDisplayFrame(visible)
        // (That one is the screen's.)
        val inWindow = IntArray(2).also { anchor.getLocationInWindow(it) }
        val onScreen = IntArray(2).also { anchor.getLocationOnScreen(it) }
        visible.offset(inWindow[0] - onScreen[0], inWindow[1] - onScreen[1])
    }

    /** The window between the system's bars — whether or not the keyboard
     *  is over some of it — from top to bottom, in its own coordinates. */
    private fun betweenBars(): IntRange {
        val root = anchor.rootView
        val bars = ViewCompat.getRootWindowInsets(root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        return (bars?.top ?: 0)..(root.height - (bars?.bottom ?: 0))
    }
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
        // Its right edge under the button's, kept on the screen; below the
        // button or above it, on the roomier side as it was when opened.
        fun x() = (anchorAt[0] + anchor.width - width).coerceIn(frame.left + margin, frame.right - margin - width)
        val height = content.measuredHeight
        fun top(from: Int, to: Int) = cardTop(anchorAt[1], anchor.height, height, from, to, below >= above)
        // (With no room on either side, on a screen that short: over the
        // button, on the side there was most.)
        val y = top(frame.top, frame.bottom)
            ?: if (below >= above) anchorAt[1] + anchor.height else anchorAt[1] - height

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

        // A popup stays where it was put. Its button need not: rows above
        // it come and go (a section folds, the keyboard this card's own
        // focus sends away gives the page its room back). The card goes
        // after it, to the same side or the other, where there is room —
        // and goes altogether where there is none, or the button is gone
        // (its section folded away) or out from between the system's bars.
        //
        // Room then is the window between those bars, not what the
        // keyboard leaves of it: the keyboard is on its way out when the
        // page moves for it, and may still count as there.
        val now = IntArray(2)
        val follow = {
            anchor.getLocationInWindow(now)
            if (!anchor.isShown) {
                card.dismiss()
            } else if (now[0] != anchorAt[0] || now[1] != anchorAt[1]) {
                anchorAt[0] = now[0]
                anchorAt[1] = now[1]
                val room = betweenBars()
                val inSight = now[1] + anchor.height > room.first && now[1] < room.last
                val moved = if (inSight) top(room.first, room.last) else null
                if (moved != null) card.update(x(), moved, -1, -1) else card.dismiss()
            }
        }
        // (A button moves when the page is laid out anew or scrolls.)
        val onLayout = ViewTreeObserver.OnGlobalLayoutListener { follow() }
        val onScroll = ViewTreeObserver.OnScrollChangedListener { follow() }
        anchor.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
        anchor.viewTreeObserver.addOnScrollChangedListener(onScroll)
        card.setOnDismissListener {
            onDismiss()
            anchor.viewTreeObserver.takeIf { it.isAlive }?.apply {
                removeOnGlobalLayoutListener(onLayout)
                removeOnScrollChangedListener(onScroll)
            }
            activity.lifecycle.removeObserver(this)
            if (root.getTag(R.id.anchored_card) === card) root.setTag(R.id.anchored_card, null)
        }
        popup = card
        root.setTag(R.id.anchored_card, card)
        // Gone with the screen (rotation, leaving it) rather than leaked.
        activity.lifecycle.addObserver(this)
        // LEFT, not START: x is measured from the window's left edge, and
        // START would mirror it in a right-to-left layout.
        card.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x(), y)
        return card
    }

    override fun onStop(owner: LifecycleOwner) {
        popup?.dismiss()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_WIDTH_DP = 320

        /**
         * Where the top of a card [cardHeight] high goes, beside a button
         * at [anchorTop] that is [anchorHeight] high, with room for it from
         * [from] to [to]: right under the button or right over it — the
         * side asked for ([preferUnder]) if it fits there, else the other,
         * and null if it fits on neither.
         */
        fun cardTop(
            anchorTop: Int, anchorHeight: Int, cardHeight: Int, from: Int, to: Int, preferUnder: Boolean
        ): Int? {
            val under = anchorTop + anchorHeight
            val over = anchorTop - cardHeight
            val fitsUnder = under + cardHeight <= to
            val fitsOver = over >= from
            return when {
                preferUnder -> if (fitsUnder) under else if (fitsOver) over else null
                else -> if (fitsOver) over else if (fitsUnder) under else null
            }
        }
    }
}
