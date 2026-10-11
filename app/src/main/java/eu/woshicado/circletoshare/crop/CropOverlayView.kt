package eu.woshicado.circletoshare.crop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.withSave
import eu.woshicado.circletoshare.qr.QrCode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frozen screenshot and a draggable/resizable crop rectangle with
 * Circle-to-Search-style corner handles.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSelectionChanged(hasSelection: Boolean)
        /** Double-tap inside the crop box. */
        fun onDoubleTapInside() {}
        /** Long-press held inside the crop box. */
        fun onLongPressInside() {}
        /** Double-tap on empty space with no selection. */
        fun onDoubleTapEmpty() {}
        /** Plain tap on a highlighted QR code. */
        fun onQrTapped(code: QrCode) {}
    }

    var listener: Listener? = null

    /**
     * When true (default), the first gesture is a freeform "circle" — the user
     * draws a loop/scribble and its bounding box becomes the crop box. When
     * false, the gesture rubber-bands a rectangle corner-to-corner. Either way
     * the resulting box is resized/moved with the same handles.
     */
    var freeform: Boolean = true

    private var bitmap: Bitmap? = null

    // Where the whole display lies in this view's coordinates, when the view
    // covers only part of it; null when the view is the display. The
    // screenshot is a picture of the display and is laid out against that, so
    // it stays exactly over what it shows even where this view stops short of
    // an edge (the bubble's overlay leaves a tappable navigation bar free).
    private var displayFrame: RectF? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val imageBounds = RectF()

    private var selection: RectF? = null

    // Freeform stroke state (only used while drawing a circle).
    private val strokePath = Path()
    private var hasStroke = false
    private var strokeMinX = 0f
    private var strokeMinY = 0f
    private var strokeMaxX = 0f
    private var strokeMaxY = 0f

    private enum class Mode { NONE, DRAWING, MOVING, RESIZING }

    private var mode = Mode.NONE
    private var activeCorner = -1 // 0=TL 1=TR 2=BL 3=BR
    private var downX = 0f
    private var downY = 0f
    private var moveOffsetX = 0f
    private var moveOffsetY = 0f

    // Set when a long-press has been handled for the current touch stream, so
    // the drawing/move/resize logic ignores the rest of the gesture.
    private var gestureHandled = false

    private enum class DoubleTap { NONE, INSIDE, EMPTY }

    // What the double-tap in progress will do if its second touch lifts as a
    // tap. Decided when that touch goes down, acted on only when it lifts:
    // until then it can still turn into a drag — a new circle, a move, a
    // resize — and someone who taps and then quickly drags didn't ask to
    // share or to close the tool.
    private var pendingDoubleTap = DoubleTap.NONE

    // Latched for the rest of the touch stream once the pointer strays a tap's
    // distance from where it went down. Comparing only the UP position to the
    // DOWN position can't tell a tap from a loop that closes on its own start.
    private var movedPastTapSlop = false

    // The finger the current touch stream belongs to: the first one down.
    // Further fingers never take part — they can't start, steer or finish
    // anything — and once this one lifts the stream is over for this view,
    // whatever the others go on to do.
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // The selection as it was when the current touch went down, to put back
    // if the system takes the touch away (ACTION_CANCEL).
    private var selectionAtDown: RectF? = null
    private var qrSelectionActiveAtDown = false

    // Whether the QR highlights were on screen when the current touch went
    // down. Freeform drawing clears the selection on DOWN, so by UP "nothing
    // is selected" no longer says whether the highlights were visible.
    private var qrVisibleAtDown = false

    // The last plain tap on empty space — nothing selected, nothing under
    // it, nothing done by it — as the possible first half of a double-tap to
    // exit. The gesture detector pairs taps itself, but only two at a time:
    // when the first of three quick taps cleared a box (and so doesn't
    // count), the detector has already spent its double-tap on taps one and
    // two, and never offers two and three. This is for pairing those.
    // (Both ends of the tap are kept: the detector wants the next touch down
    // soon after this one came up, and soon after it went down as well.)
    private var emptyTapTime = NO_TAP
    private var emptyTapDownTime = NO_TAP
    private var emptyTapX = 0f
    private var emptyTapY = 0f

    // Whether another finger joined the current touch stream at any point. A
    // touch with two fingers in it is not a tap.
    private var hadExtraFinger = false
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()

    // Down-time of the last tap that already did something by itself — cleared
    // a box, or opened a QR code's actions. Such a tap can't also be the first
    // half of the double-tap-on-empty-space exit shortcut.
    private var consumedTapDownTime = NO_TAP

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x.coerceIn(imageBounds.left, imageBounds.right)
                val y = e.y.coerceIn(imageBounds.top, imageBounds.bottom)
                val sel = selection
                // Called as the second touch goes down; [e] is the FIRST
                // tap's DOWN. The second touch still has to land in the same
                // place (checked in ACTION_DOWN) and lift as a tap (ACTION_UP)
                // before anything happens.
                //
                // And the first must have been a tap to this view, not only
                // to the detector, whose idea of how far a tap may wander is
                // not exactly ours: what it calls a tap may have drawn a
                // small box here, which the second tap would then be
                // "inside". (movedPastTapSlop still describes the first
                // touch at this point — the second's ACTION_DOWN, which
                // resets it, is handled after the detector's callbacks.)
                pendingDoubleTap = when {
                    movedPastTapSlop -> DoubleTap.NONE
                    sel != null && sel.contains(x, y) -> DoubleTap.INSIDE
                    // Exit only when there was nothing selected to begin with,
                    // so double-tapping outside a box doesn't fight the
                    // tap-to-clear gesture: its first tap has already cleared
                    // the box by now, which is why `sel == null` alone can't
                    // tell.
                    sel == null && e.downTime != consumedTapDownTime -> DoubleTap.EMPTY
                    else -> DoubleTap.NONE
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // A press that has become a drag is not a long-press. The
                // detector normally sees to that itself, but not for the
                // second touch of a double-tap: there it leaves the timer
                // armed however far the finger travels, and a tap followed
                // by a slow drag would copy and close mid-drag.
                if (movedPastTapSlop) return
                val x = e.x.coerceIn(imageBounds.left, imageBounds.right)
                val y = e.y.coerceIn(imageBounds.top, imageBounds.bottom)
                val sel = selection ?: return
                if (sel.contains(x, y)) {
                    gestureHandled = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener?.onLongPressInside()
                }
            }
        }
    )

    private val handleTouchRadius = dp(28f)
    // How far a touch may wander and still count as a tap — the system's own
    // threshold, so this view and its gesture detector (which decides what is
    // a double-tap) never disagree about the same touch.
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minSelection = dp(24f)
    private val cornerLength = dp(20f)
    // Breathing room so a hand-drawn loop doesn't clip the circled content.
    private val strokePadding = dp(10f)

    // --- Snap-to-content state ---------------------------------------------
    // Element bounds in bitmap space (scaled once from screen pixels), plus
    // the view-space candidate lines derived from them: every left/right edge
    // becomes a vertical line in snapXs, every top/bottom a horizontal line
    // in snapYs. Sorted and deduped for binary search.
    private var snapRectsBitmap: List<RectF> = emptyList()
    private var snapXs = FloatArray(0)
    private var snapYs = FloatArray(0)
    private val snapThreshold = dp(10f)
    // Currently engaged snap line per axis (NaN = free) — tracked only to fire
    // one haptic tick per engagement, not to make snapping stateful.
    private var engagedSnapX = Float.NaN
    private var engagedSnapY = Float.NaN

    // --- Detected QR codes --------------------------------------------------
    // Codes in bitmap space, plus their view-space boxes (re)derived from the
    // image matrix — same lifecycle as the snap lines: set per capture,
    // remapped on rotation, cleared with the bitmap.
    private var qrCodes: List<QrCode> = emptyList()
    private var qrHighlights: List<QrHighlight> = emptyList()
    private val qrChipPadX = dp(6f)
    private val qrChipPadY = dp(3f)

    // True while the current selection is the untouched box a QR tap drew
    // around the code. Highlights stay visible over such a selection (the
    // user hasn't started composing a crop of their own); the first real
    // manipulation — drag, resize, redraw — demotes it to an ordinary
    // selection and hides them.
    private var qrSelectionActive = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Default gold, so the QR highlight reads apart from the white crop
    // chrome; the host recolors per capture via setQrHighlightColor.
    private val qrStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(235, 255, 202, 40)
        strokeWidth = dp(2.5f)
    }
    private val qrFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(36, 255, 202, 40)
    }
    private val qrChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 255, 202, 40)
    }
    private val qrChipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val qrChipRect = RectF()

    fun setBitmap(b: Bitmap) {
        bitmap = b
        selection = null
        selectionAtDown = null
        qrSelectionActive = false
        mode = Mode.NONE
        resetStroke()
        // Snap bounds and QR codes belong to a specific capture — the host
        // sets fresh ones (or none) right after this.
        setSnapBounds(null)
        setQrCodes(emptyList())
        updateMatrix()
        listener?.onSelectionChanged(false)
        invalidate()
    }

    fun clear() {
        bitmap = null
        selection = null
        selectionAtDown = null
        qrSelectionActive = false
        mode = Mode.NONE
        resetStroke()
        setSnapBounds(null)
        setQrCodes(emptyList())
        invalidate()
    }

    private fun resetStroke() {
        strokePath.reset()
        hasStroke = false
    }

    /** Selected region in bitmap coordinates, or null when nothing is selected. */
    fun getCropRect(): Rect? {
        val sel = selection ?: return null
        val b = bitmap ?: return null
        val mapped = RectF(sel)
        inverseMatrix.mapRect(mapped)
        val rect = Rect(
            max(0, mapped.left.toInt()),
            max(0, mapped.top.toInt()),
            min(b.width, mapped.right.toInt()),
            min(b.height, mapped.bottom.toInt())
        )
        return if (rect.width() > 4 && rect.height() > 4) rect else null
    }

    /** Dimensions of the frozen screenshot, or null when none is set. */
    fun getBitmapSize(): Pair<Int, Int>? = bitmap?.let { it.width to it.height }

    /**
     * Re-apply a previously saved crop ([rect] in bitmap pixels), mapping it
     * back into screen space and clamping to the image. No-op if the view isn't
     * laid out yet or the mapped box is too small.
     */
    fun applyCropRect(rect: Rect) {
        selection = selectionFor(rect) ?: return
        qrSelectionActive = false
        mode = Mode.NONE
        activeCorner = -1
        resetStroke()
        listener?.onSelectionChanged(true)
        invalidate()
    }

    /**
     * Element bounds captured with the screenshot, or null to disable
     * snapping. Scaled to bitmap space once here; the view-space candidate
     * lines are (re)derived in [updateMatrix] so rotation keeps them valid.
     */
    fun setSnapBounds(snap: SnapBounds?) {
        val b = bitmap
        if (snap == null || b == null ||
            snap.screenWidth <= 0 || snap.screenHeight <= 0
        ) {
            snapRectsBitmap = emptyList()
            snapXs = FloatArray(0)
            snapYs = FloatArray(0)
            return
        }
        // Normally 1.0 — guards against a system shot whose resolution
        // differs from the display's.
        val sx = b.width / snap.screenWidth.toFloat()
        val sy = b.height / snap.screenHeight.toFloat()
        snapRectsBitmap = snap.rects.map {
            RectF(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy)
        }
        rebuildSnapLines()
    }

    /** Recolor the QR highlight (stroke, fill, chip) to [color]'s RGB; the
     *  alphas stay fixed. Chip text flips black/white for contrast. */
    fun setQrHighlightColor(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        qrStrokePaint.color = Color.argb(235, r, g, b)
        qrFillPaint.color = Color.argb(36, r, g, b)
        qrChipPaint.color = Color.argb(235, r, g, b)
        // Perceived luminance (ITU-R BT.601) — dark text on light chips,
        // light text on dark ones.
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        qrChipTextPaint.color = if (luminance >= 140f) Color.BLACK else Color.WHITE
        invalidate()
    }

    /** QR codes detected in the current capture ([QrCode.bounds] in bitmap
     *  pixels). They are drawn highlighted; a plain tap on one fires
     *  [Listener.onQrTapped]. */
    fun setQrCodes(codes: List<QrCode>) {
        qrCodes = codes
        rebuildQrRects()
        invalidate()
    }

    /**
     * [rect] (bitmap pixels) as a view-space selection clamped to the image,
     * or null when there is no laid-out image to map onto or the result would
     * be too small to serve as a selection.
     */
    private fun selectionFor(rect: Rect): RectF? {
        bitmap ?: return null
        if (imageBounds.isEmpty) return null
        val mapped = RectF(rect)
        imageMatrix.mapRect(mapped)
        mapped.set(
            mapped.left.coerceIn(imageBounds.left, imageBounds.right),
            mapped.top.coerceIn(imageBounds.top, imageBounds.bottom),
            mapped.right.coerceIn(imageBounds.left, imageBounds.right),
            mapped.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
        )
        return if (mapped.width() < minSelection || mapped.height() < minSelection) null else mapped
    }

    /** Map the bitmap-space QR boxes into view space (rotation re-derives)
     *  and settle each chip's label, so drawing does no text work per frame.
     *  The chip carries the decoded payload when that fits within the code's
     *  own width, else just "QR". */
    private fun rebuildQrRects() {
        qrHighlights = if (qrCodes.isEmpty() || imageBounds.isEmpty) {
            emptyList()
        } else {
            qrCodes.map { code ->
                val rect = RectF(code.bounds).also { imageMatrix.mapRect(it) }
                val payload = code.text.trim().replace(WHITESPACE_RUN, " ")
                val payloadWidth = qrChipTextPaint.measureText(payload)
                if (payload.isNotEmpty() && payloadWidth + qrChipPadX * 2 <= rect.width()) {
                    QrHighlight(rect, code, payload, payloadWidth)
                } else {
                    QrHighlight(rect, code, QR_CHIP_FALLBACK, qrChipTextPaint.measureText(QR_CHIP_FALLBACK))
                }
            }
        }
    }

    /** Map the bitmap-space element rects into view space and derive the
     *  sorted, deduped candidate lines. */
    private fun rebuildSnapLines() {
        if (snapRectsBitmap.isEmpty() || imageBounds.isEmpty) {
            snapXs = FloatArray(0)
            snapYs = FloatArray(0)
            return
        }
        val xs = ArrayList<Float>(snapRectsBitmap.size * 2)
        val ys = ArrayList<Float>(snapRectsBitmap.size * 2)
        val mapped = RectF()
        val minX = imageBounds.left - snapThreshold
        val maxX = imageBounds.right + snapThreshold
        val minY = imageBounds.top - snapThreshold
        val maxY = imageBounds.bottom + snapThreshold
        for (rect in snapRectsBitmap) {
            mapped.set(rect)
            imageMatrix.mapRect(mapped)
            if (mapped.left in minX..maxX) xs.add(mapped.left)
            if (mapped.right in minX..maxX) xs.add(mapped.right)
            if (mapped.top in minY..maxY) ys.add(mapped.top)
            if (mapped.bottom in minY..maxY) ys.add(mapped.bottom)
        }
        snapXs = sortedDeduped(xs)
        snapYs = sortedDeduped(ys)
    }

    private fun sortedDeduped(values: ArrayList<Float>): FloatArray {
        if (values.isEmpty()) return FloatArray(0)
        values.sort()
        val out = ArrayList<Float>(values.size)
        for (v in values) {
            if (out.isEmpty() || v - out.last() > 1f) out.add(v)
        }
        return out.toFloatArray()
    }

    /** Nearest candidate within [snapThreshold] of [value], else NaN. */
    private fun nearestSnapLine(value: Float, candidates: FloatArray): Float {
        if (candidates.isEmpty()) return Float.NaN
        // Binary search for the insertion point, then compare both neighbors.
        var lo = 0
        var hi = candidates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (candidates[mid] < value) lo = mid + 1 else hi = mid
        }
        var best = Float.NaN
        var bestDist = snapThreshold
        if (lo < candidates.size && abs(candidates[lo] - value) <= bestDist) {
            best = candidates[lo]
            bestDist = abs(candidates[lo] - value)
        }
        if (lo > 0 && abs(candidates[lo - 1] - value) < bestDist) {
            best = candidates[lo - 1]
        }
        return best
    }

    /** Snap [value] on the x-axis (stateless), ticking on new engagement. */
    private fun snapX(value: Float): Float {
        val line = nearestSnapLine(value, snapXs)
        if (line.isNaN()) {
            engagedSnapX = Float.NaN
            return value
        }
        if (line != engagedSnapX) {
            engagedSnapX = line
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return line
    }

    /** Snap [value] on the y-axis (stateless), ticking on new engagement. */
    private fun snapY(value: Float): Float {
        val line = nearestSnapLine(value, snapYs)
        if (line.isNaN()) {
            engagedSnapY = Float.NaN
            return value
        }
        if (line != engagedSnapY) {
            engagedSnapY = line
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return line
    }

    private fun resetSnapEngagement() {
        engagedSnapX = Float.NaN
        engagedSnapY = Float.NaN
    }

    /**
     * Snap all four edges of a freshly created box (circle result or
     * rectangle rubber-band, on release) independently, then clamp to the
     * image. One tick if anything moved.
     */
    private fun snapSelectionEdges(sel: RectF) {
        val drawn = RectF(sel)
        val l = nearestSnapLine(sel.left, snapXs)
        val r = nearestSnapLine(sel.right, snapXs)
        val t = nearestSnapLine(sel.top, snapYs)
        val b = nearestSnapLine(sel.bottom, snapYs)
        if (!l.isNaN()) sel.left = l
        if (!r.isNaN()) sel.right = r
        if (!t.isNaN()) sel.top = t
        if (!b.isNaN()) sel.bottom = b
        // (A snap line may lie a little beyond the image; the box may not.)
        sel.set(
            sel.left.coerceIn(imageBounds.left, imageBounds.right),
            sel.top.coerceIn(imageBounds.top, imageBounds.bottom),
            sel.right.coerceIn(imageBounds.left, imageBounds.right),
            sel.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
        )
        // Snapping must not be what makes a box too small to keep: pulled
        // onto an element under the minimum size (a line of text, an icon),
        // a box drawn deliberately around it would be thrown away on release
        // as a scribble. Where that would happen, the edges stay as drawn.
        if (sel.width() < minSelection && drawn.width() >= minSelection) {
            sel.left = drawn.left
            sel.right = drawn.right
        }
        if (sel.height() < minSelection && drawn.height() >= minSelection) {
            sel.top = drawn.top
            sel.bottom = drawn.bottom
        }
        if (sel != drawn) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onGeometryChanged()
    }

    /** Tell the view where the whole display lies relative to it ([frame] in
     *  view coordinates), or null if it covers the display — see
     *  [displayFrame]. */
    fun setDisplayFrame(frame: RectF?) {
        if (frame == displayFrame) return
        displayFrame = frame?.let { RectF(it) }
        onGeometryChanged()
        invalidate()
    }

    /** The image now maps onto the view differently (new size, new place on
     *  the display). */
    private fun onGeometryChanged() {
        updateMatrix()
        // Nor may a touch in progress bring the old box back if it ends in
        // a cancel.
        selectionAtDown = null
        qrSelectionActiveAtDown = false
        // A screen-space selection is meaningless after a relayout (rotation):
        // it maps to the wrong bitmap region and can exceed the new image
        // bounds, making moveTo's coerceIn throw. Start over.
        if (selection != null) {
            selection = null
            qrSelectionActive = false
            mode = Mode.NONE
            activeCorner = -1
            resetStroke()
            listener?.onSelectionChanged(false)
        }
    }

    private fun updateMatrix() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val src = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val dst = displayFrame ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        imageMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
        imageMatrix.mapRect(imageBounds, src)
        // Only the part of the image this view shows can be touched or
        // selected.
        if (!imageBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
            imageBounds.setEmpty()
        }
        imageMatrix.invert(inverseMatrix)
        // The view-space candidate lines depend on the mapping — re-derive
        // them (handles rotation; the bitmap-space rects stay valid).
        rebuildSnapLines()
        rebuildQrRects()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, imageMatrix, bitmapPaint)

        val sel = selection
        if (sel == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            drawQrHighlights(canvas)
            // Show the freeform "circle" trail as the finger moves.
            if (hasStroke) canvas.drawPath(strokePath, strokePaint)
            return
        }

        // Dim everything outside the selection — in one pass with the box
        // clipped out. Four abutting rectangles leave a visible seam along
        // the box's top and bottom whenever those fall between pixels, where
        // the two partially covered rows blend to less than the full dim.
        canvas.withSave {
            clipOutRect(sel)
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }

        if (qrSelectionActive) drawQrHighlights(canvas)

        canvas.drawRect(sel, borderPaint)

        val l = min(cornerLength, min(sel.width(), sel.height()) / 3f)
        // Corner brackets: TL, TR, BL, BR
        canvas.drawLine(sel.left, sel.top, sel.left + l, sel.top, handlePaint)
        canvas.drawLine(sel.left, sel.top, sel.left, sel.top + l, handlePaint)
        canvas.drawLine(sel.right, sel.top, sel.right - l, sel.top, handlePaint)
        canvas.drawLine(sel.right, sel.top, sel.right, sel.top + l, handlePaint)
        canvas.drawLine(sel.left, sel.bottom, sel.left + l, sel.bottom, handlePaint)
        canvas.drawLine(sel.left, sel.bottom, sel.left, sel.bottom - l, handlePaint)
        canvas.drawLine(sel.right, sel.bottom, sel.right - l, sel.bottom, handlePaint)
        canvas.drawLine(sel.right, sel.bottom, sel.right, sel.bottom - l, handlePaint)
    }

    /** Colored box + chip over each detected code. Only drawn while nothing
     *  is selected: the highlight isn't part of the delivered crop, so it
     *  would misrepresent what a selection is about to copy/share. It returns
     *  when the selection is cleared. */
    private fun drawQrHighlights(canvas: Canvas) {
        if (qrHighlights.isEmpty()) return
        val radius = dp(10f)
        val fm = qrChipTextPaint.fontMetrics
        val chipH = fm.descent - fm.ascent + qrChipPadY * 2
        for (highlight in qrHighlights) {
            val rect = highlight.rect
            canvas.drawRoundRect(rect, radius, radius, qrFillPaint)
            canvas.drawRoundRect(rect, radius, radius, qrStrokePaint)
            // Chip above the top-left corner; inside it when there's no room.
            val chipLeft = rect.left
            var chipTop = rect.top - chipH - dp(4f)
            if (chipTop < 0f) chipTop = rect.top + dp(4f)
            qrChipRect.set(
                chipLeft, chipTop,
                chipLeft + highlight.chipTextWidth + qrChipPadX * 2, chipTop + chipH
            )
            canvas.drawRoundRect(qrChipRect, chipH / 2, chipH / 2, qrChipPaint)
            canvas.drawText(
                highlight.chipText,
                qrChipRect.left + qrChipPadX,
                qrChipRect.top + qrChipPadY - fm.ascent,
                qrChipTextPaint
            )
        }
    }

    /** Select [code]'s highlight box as the crop, marked as a QR selection
     *  (highlights stay visible over it) — or nothing at all, if the mapped
     *  box would be too small to serve as a selection. */
    private fun selectQr(code: QrCode) {
        // Whatever was selected before — say, another code's box — is not
        // this code's: its actions are about to open, and Share/Copy must
        // not go on delivering the other one.
        selection = selectionFor(code.bounds)
        qrSelectionActive = selection != null
    }

    /** The QR code under a plain tap at ([x], [y]) in view space, if any. */
    private fun qrAt(x: Float, y: Float): QrCode? {
        val slop = dp(8f)
        return qrHighlights.firstOrNull { (rect) ->
            x >= rect.left - slop && x <= rect.right + slop &&
                y >= rect.top - slop && y <= rect.bottom + slop
        }?.code
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        // What this event means for the finger the stream began with.
        var action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                // A fresh touch stream starts un-handled.
                gestureHandled = false
                pendingDoubleTap = DoubleTap.NONE
                hadExtraFinger = false
            }
            // More than one finger is not a tap, so not half a double-tap
            // either (the detector drops its own at this point, too).
            MotionEvent.ACTION_POINTER_DOWN -> {
                pendingDoubleTap = DoubleTap.NONE
                hadExtraFinger = true
            }
            // Our finger lifting ends the gesture, even with others down.
            MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    action = MotionEvent.ACTION_UP
                }
        }
        // Let the detector inspect the event first, so a long-press can claim
        // the stream.
        gestureDetector.onTouchEvent(event)

        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return true // our finger is gone; not our business
        val x = event.getX(pointerIndex).coerceIn(imageBounds.left, imageBounds.right)
        val y = event.getY(pointerIndex).coerceIn(imageBounds.top, imageBounds.bottom)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
        }

        if (gestureHandled) {
            // The gesture (copy) owns this stream — skip drawing and drop any
            // in-progress stroke so it doesn't linger as a stray box.
            if (hasStroke) resetStroke()
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                movedPastTapSlop = false
                selectionAtDown = selection?.let { RectF(it) }
                qrSelectionActiveAtDown = qrSelectionActive
                qrVisibleAtDown = selection == null || qrSelectionActive
                resetSnapEngagement()
                val sel = selection
                // "Double-tap inside the box" needs both taps inside it. The
                // second may land up to the double-tap distance away from the
                // first — outside, it is a tap-to-clear, not a share.
                if (pendingDoubleTap == DoubleTap.INSIDE && sel?.contains(x, y) != true) {
                    pendingDoubleTap = DoubleTap.NONE
                }
                // Likewise "double-tap on empty space" needs the second tap
                // on empty space: landing on a highlighted code, it opens
                // the code.
                if (pendingDoubleTap == DoubleTap.EMPTY && qrVisibleAtDown && qrAt(x, y) != null) {
                    pendingDoubleTap = DoubleTap.NONE
                }
                // A second tap on empty space that the detector did not pair
                // with the one before (see emptyTapTime), by the detector's
                // own measures of "soon" and "near".
                if (pendingDoubleTap == DoubleTap.NONE && sel == null && emptyTapTime != NO_TAP &&
                    event.eventTime - emptyTapTime in DOUBLE_TAP_MIN_GAP_MS..doubleTapTimeout &&
                    event.eventTime - emptyTapDownTime <= doubleTapTimeout &&
                    hypot(x - emptyTapX, y - emptyTapY) <= doubleTapSlop &&
                    qrAt(x, y) == null
                ) {
                    pendingDoubleTap = DoubleTap.EMPTY
                }
                emptyTapTime = NO_TAP
                val corner = sel?.let { hitCorner(it, x, y) } ?: -1
                mode = when {
                    corner >= 0 -> {
                        activeCorner = corner
                        Mode.RESIZING
                    }
                    sel != null && sel.contains(x, y) -> Mode.MOVING
                    else -> Mode.DRAWING
                }
                if (mode == Mode.DRAWING && freeform) {
                    // Start a fresh circle: drop any old box, begin the trail.
                    if (selection != null) consumedTapDownTime = event.downTime
                    selection = null
                    strokePath.reset()
                    strokePath.moveTo(x, y)
                    strokeMinX = x; strokeMaxX = x
                    strokeMinY = y; strokeMaxY = y
                    hasStroke = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!movedPastTapSlop && hypot(x - downX, y - downY) >= tapSlop) {
                    movedPastTapSlop = true
                    // The drag proper starts here: take the box's offset from
                    // the finger now rather than at touch-down, so the box
                    // picks up smoothly instead of lurching by the slop.
                    if (mode == Mode.MOVING) {
                        selection?.let {
                            moveOffsetX = x - it.left
                            moveOffsetY = y - it.top
                        }
                    }
                }
                // A real drag (past the tap threshold) is the user taking
                // over: a box a QR tap drew becomes an ordinary selection and
                // the highlights hide. Sub-threshold jitter during a tap must
                // not demote it, or the tap-on-code gesture would misfire.
                if (mode != Mode.NONE && qrSelectionActive && movedPastTapSlop) {
                    qrSelectionActive = false
                }
                when (mode) {
                    Mode.DRAWING -> {
                        if (freeform) {
                            strokePath.lineTo(x, y)
                            strokeMinX = min(strokeMinX, x); strokeMaxX = max(strokeMaxX, x)
                            strokeMinY = min(strokeMinY, y); strokeMaxY = max(strokeMaxY, y)
                        } else if (movedPastTapSlop) {
                            // Only a real drag rubber-bands. A finger tap
                            // always jitters a little; treating that as a
                            // one-pixel box would swallow the tap.
                            selection = RectF(
                                min(downX, x), min(downY, y),
                                max(downX, x), max(downY, y)
                            )
                        }
                    }
                    // A box changes only under a real drag. While the touch
                    // could still be a tap, a double-tap or a long-press it
                    // stays exactly as it is — otherwise a tap's wobble near
                    // a corner pulls that corner to the finger, and on a
                    // small box (which is all corner) that shrinks or kills
                    // it just before the shortcut aimed at it fires.
                    Mode.RESIZING -> if (movedPastTapSlop) selection?.let { resize(it, x, y) }
                    Mode.MOVING -> if (movedPastTapSlop) selection?.let { moveTo(it, x, y) }
                    Mode.NONE -> Unit
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (action == MotionEvent.ACTION_CANCEL) {
                    // The system took the touch away — typically it turned
                    // out to be an edge swipe for Back or Home. It never was
                    // an edit: put everything back as the finger found it,
                    // rather than committing half a gesture as a crop.
                    selection = selectionAtDown
                    qrSelectionActive = qrSelectionActiveAtDown
                    selectionAtDown = null
                    pendingDoubleTap = DoubleTap.NONE
                    resetStroke()
                    resetSnapEngagement()
                    mode = Mode.NONE
                    activeCorner = -1
                    listener?.onSelectionChanged(selection != null)
                    invalidate()
                    return true
                }
                val doubleTap = pendingDoubleTap
                pendingDoubleTap = DoubleTap.NONE
                if (action == MotionEvent.ACTION_UP &&
                    doubleTap != DoubleTap.NONE && !movedPastTapSlop
                ) {
                    // The second tap of a double-tap, lifted where it landed.
                    // Nothing about the box changed during it (that takes a
                    // drag); drop the dot of a stroke it may have started.
                    resetStroke()
                    resetSnapEngagement()
                    mode = Mode.NONE
                    activeCorner = -1
                    invalidate()
                    when (doubleTap) {
                        DoubleTap.INSIDE -> listener?.onDoubleTapInside()
                        else -> listener?.onDoubleTapEmpty()
                    }
                    return true
                }
                // A plain tap on a QR highlight opens its actions AND selects
                // the code's box, so it can be shared/copied as an image too.
                // Only if the highlights were visible when the finger went
                // down (no selection, or the untouched box from a previous QR
                // tap) — an invisible tap target would be a trap. Claimed
                // before the crop logic below, whatever the mode: a tap on an
                // already selected code lands on its box (MOVING) or, for a
                // small code, inevitably on a corner handle (RESIZING).
                if (action == MotionEvent.ACTION_UP &&
                    qrVisibleAtDown && !movedPastTapSlop
                ) {
                    val code = qrAt(downX, downY)
                    if (code != null) {
                        consumedTapDownTime = event.downTime
                        resetStroke()
                        resetSnapEngagement()
                        mode = Mode.NONE
                        activeCorner = -1
                        selectQr(code)
                        listener?.onSelectionChanged(selection != null)
                        invalidate()
                        listener?.onQrTapped(code)
                        return true
                    }
                }
                if (mode == Mode.DRAWING) {
                    // Whatever this gesture produced (or cleared), it's the
                    // user composing their own crop — never a QR box.
                    qrSelectionActive = false
                    if (freeform) {
                        // Only a real stroke becomes a box. A tap's few
                        // pixels of wobble, once padded (and, with snapping
                        // on, pulled out to a nearby element), can pass the
                        // minimum size — and a stray box under the finger
                        // turns the next tap into "double-tap inside: share".
                        if (hasStroke && movedPastTapSlop) {
                            // Bounding box of the drawn loop — padded and clamped
                            // to the image. The loop need not be closed.
                            selection = RectF(
                                max(strokeMinX - strokePadding, imageBounds.left),
                                max(strokeMinY - strokePadding, imageBounds.top),
                                min(strokeMaxX + strokePadding, imageBounds.right),
                                min(strokeMaxY + strokePadding, imageBounds.bottom)
                            )
                        }
                        resetStroke()
                    } else if (!movedPastTapSlop) {
                        // A plain tap outside the selection clears it.
                        if (selection != null) consumedTapDownTime = event.downTime
                        selection = null
                    }
                    // A freshly drawn box (circle or rubber-band) snaps its
                    // edges to nearby element bounds on release.
                    selection?.let { snapSelectionEdges(it) }
                }
                resetSnapEngagement()
                // Discard incidental taps / tiny scribbles.
                selection?.let {
                    if (it.width() < minSelection || it.height() < minSelection) {
                        selection = null
                    }
                }
                mode = Mode.NONE
                activeCorner = -1
                listener?.onSelectionChanged(selection != null)
                invalidate()
                // A tap as the detector would count one: brief (a press held
                // to long-press length is not a tap, however still), with one
                // finger, in one place.
                if (action == MotionEvent.ACTION_UP && !movedPastTapSlop && !hadExtraFinger &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    selectionAtDown == null && selection == null &&
                    consumedTapDownTime != event.downTime
                ) {
                    emptyTapTime = event.eventTime
                    emptyTapDownTime = event.downTime
                    emptyTapX = x
                    emptyTapY = y
                }
            }
        }
        return true
    }

    private fun hitCorner(sel: RectF, x: Float, y: Float): Int {
        val corners = arrayOf(
            floatArrayOf(sel.left, sel.top),
            floatArrayOf(sel.right, sel.top),
            floatArrayOf(sel.left, sel.bottom),
            floatArrayOf(sel.right, sel.bottom)
        )
        for (i in corners.indices) {
            if (abs(x - corners[i][0]) < handleTouchRadius &&
                abs(y - corners[i][1]) < handleTouchRadius
            ) {
                return i
            }
        }
        return -1
    }

    private fun resize(sel: RectF, xRaw: Float, yRaw: Float) {
        // Snap from the RAW finger position every event (stateless): the edge
        // pulls to a line while the finger is within the threshold band and
        // releases the moment it leaves — no sticky state, no jitter.
        // A snap line may lie a little outside what this view shows of the
        // image (they are kept for a threshold's width beyond it); the edge
        // itself must not follow it there — the crop would take in pixels
        // the user was never shown.
        val x = snapX(xRaw).coerceIn(imageBounds.left, imageBounds.right)
        val y = snapY(yRaw).coerceIn(imageBounds.top, imageBounds.bottom)
        when (activeCorner) {
            0 -> { sel.left = x; sel.top = y }
            1 -> { sel.right = x; sel.top = y }
            2 -> { sel.left = x; sel.bottom = y }
            3 -> { sel.right = x; sel.bottom = y }
        }
        // Flip the active corner when the user drags past the opposite edge.
        if (sel.left > sel.right) {
            val t = sel.left; sel.left = sel.right; sel.right = t
            activeCorner = when (activeCorner) {
                0 -> 1; 1 -> 0; 2 -> 3; else -> 2
            }
        }
        if (sel.top > sel.bottom) {
            val t = sel.top; sel.top = sel.bottom; sel.bottom = t
            activeCorner = when (activeCorner) {
                0 -> 2; 2 -> 0; 1 -> 3; else -> 1
            }
        }
    }

    private fun moveTo(sel: RectF, x: Float, y: Float) {
        val w = sel.width()
        val h = sel.height()
        var left = x - moveOffsetX
        var top = y - moveOffsetY
        // The upper bound can fall below the lower one if the selection is ever
        // wider/taller than the image — clamp it so coerceIn can't throw.
        left = left.coerceIn(imageBounds.left, max(imageBounds.left, imageBounds.right - w))
        top = top.coerceIn(imageBounds.top, max(imageBounds.top, imageBounds.bottom - h))
        sel.set(left, top, left + w, top + h)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    /** A detected code's view-space box and its ready-to-draw chip label. */
    private data class QrHighlight(
        val rect: RectF,
        val code: QrCode,
        val chipText: String,
        val chipTextWidth: Float
    )

    private companion object {
        const val NO_TAP = -1L
        // Taps closer together than this are one bouncing contact, not two
        // taps (the gesture detector's own lower bound).
        const val DOUBLE_TAP_MIN_GAP_MS = 40L
        const val QR_CHIP_FALLBACK = "QR"
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
