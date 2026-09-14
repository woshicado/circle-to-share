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

    // Set when a double-tap / long-press has been handled for the current touch
    // stream, so the drawing/move/resize logic ignores the rest of the gesture.
    private var gestureHandled = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x.coerceIn(imageBounds.left, imageBounds.right)
                val y = e.y.coerceIn(imageBounds.top, imageBounds.bottom)
                val sel = selection
                when {
                    sel != null && sel.contains(x, y) -> {
                        gestureHandled = true
                        listener?.onDoubleTapInside()
                    }
                    // Exit only when there is nothing selected, so double-tapping
                    // outside a box doesn't fight the tap-to-clear gesture.
                    sel == null -> {
                        gestureHandled = true
                        listener?.onDoubleTapEmpty()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
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
    private var qrRectsView: List<Pair<RectF, QrCode>> = emptyList()

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

    fun setBitmap(b: Bitmap) {
        bitmap = b
        selection = null
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
        bitmap ?: return
        if (imageBounds.isEmpty) return
        val mapped = RectF(rect)
        imageMatrix.mapRect(mapped)
        mapped.set(
            mapped.left.coerceIn(imageBounds.left, imageBounds.right),
            mapped.top.coerceIn(imageBounds.top, imageBounds.bottom),
            mapped.right.coerceIn(imageBounds.left, imageBounds.right),
            mapped.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
        )
        if (mapped.width() < minSelection || mapped.height() < minSelection) return
        selection = mapped
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

    /** Map the bitmap-space QR boxes into view space (rotation re-derives). */
    private fun rebuildQrRects() {
        qrRectsView = if (qrCodes.isEmpty() || imageBounds.isEmpty) {
            emptyList()
        } else {
            qrCodes.map { code ->
                RectF(code.bounds).also { imageMatrix.mapRect(it) } to code
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
        var moved = false
        val l = nearestSnapLine(sel.left, snapXs)
        val r = nearestSnapLine(sel.right, snapXs)
        val t = nearestSnapLine(sel.top, snapYs)
        val b = nearestSnapLine(sel.bottom, snapYs)
        if (!l.isNaN() && l != sel.left) { sel.left = l; moved = true }
        if (!r.isNaN() && r != sel.right) { sel.right = r; moved = true }
        if (!t.isNaN() && t != sel.top) { sel.top = t; moved = true }
        if (!b.isNaN() && b != sel.bottom) { sel.bottom = b; moved = true }
        sel.set(
            sel.left.coerceIn(imageBounds.left, imageBounds.right),
            sel.top.coerceIn(imageBounds.top, imageBounds.bottom),
            sel.right.coerceIn(imageBounds.left, imageBounds.right),
            sel.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
        )
        if (moved) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
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
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        imageMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
        imageMatrix.mapRect(imageBounds, src)
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

        // Dim everything outside the selection.
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)

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
     *  when the selection is cleared. The chip carries the decoded payload
     *  when that fits within the code's own width, else just "QR". */
    private fun drawQrHighlights(canvas: Canvas) {
        if (qrRectsView.isEmpty()) return
        val radius = dp(10f)
        val chipPadX = dp(6f)
        val chipPadY = dp(3f)
        val fm = qrChipTextPaint.fontMetrics
        val chipH = fm.descent - fm.ascent + chipPadY * 2
        for ((rect, code) in qrRectsView) {
            val payload = code.text.trim().replace(Regex("\\s+"), " ")
            val chipText = if (
                payload.isNotEmpty() &&
                qrChipTextPaint.measureText(payload) + chipPadX * 2 <= rect.width()
            ) payload else "QR"
            val textWidth = qrChipTextPaint.measureText(chipText)
            canvas.drawRoundRect(rect, radius, radius, qrFillPaint)
            canvas.drawRoundRect(rect, radius, radius, qrStrokePaint)
            // Chip above the top-left corner; inside it when there's no room.
            val chipLeft = rect.left
            var chipTop = rect.top - chipH - dp(4f)
            if (chipTop < 0f) chipTop = rect.top + dp(4f)
            val chip = RectF(
                chipLeft, chipTop,
                chipLeft + textWidth + chipPadX * 2, chipTop + chipH
            )
            canvas.drawRoundRect(chip, chipH / 2, chipH / 2, qrChipPaint)
            canvas.drawText(
                chipText,
                chip.left + chipPadX,
                chip.top + chipPadY - fm.ascent,
                qrChipTextPaint
            )
        }
    }

    /** Select [code]'s highlight box as the crop, marked as a QR selection
     *  (highlights stay visible over it). No-op if the mapped box would be
     *  too small to serve as a selection. */
    private fun selectQr(code: QrCode) {
        if (imageBounds.isEmpty) return
        val mapped = RectF(code.bounds)
        imageMatrix.mapRect(mapped)
        mapped.set(
            mapped.left.coerceIn(imageBounds.left, imageBounds.right),
            mapped.top.coerceIn(imageBounds.top, imageBounds.bottom),
            mapped.right.coerceIn(imageBounds.left, imageBounds.right),
            mapped.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
        )
        if (mapped.width() < minSelection || mapped.height() < minSelection) return
        selection = mapped
        qrSelectionActive = true
    }

    /** The QR code under a plain tap at ([x], [y]) in view space, if any. */
    private fun qrAt(x: Float, y: Float): QrCode? {
        val slop = dp(8f)
        return qrRectsView.firstOrNull { (rect, _) ->
            x >= rect.left - slop && x <= rect.right + slop &&
                y >= rect.top - slop && y <= rect.bottom + slop
        }?.second
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
        val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)

        // A fresh touch stream starts un-handled; let the detector inspect it
        // first so a double-tap or long-press can claim it.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureHandled = false
        gestureDetector.onTouchEvent(event)
        if (gestureHandled) {
            // The gesture (share/copy/exit) owns this stream — skip drawing and
            // drop any in-progress stroke so it doesn't linger as a stray box.
            if (hasStroke) resetStroke()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                resetSnapEngagement()
                val sel = selection
                val corner = sel?.let { hitCorner(it, x, y) } ?: -1
                mode = when {
                    corner >= 0 -> {
                        activeCorner = corner
                        Mode.RESIZING
                    }
                    sel != null && sel.contains(x, y) -> {
                        moveOffsetX = x - sel.left
                        moveOffsetY = y - sel.top
                        Mode.MOVING
                    }
                    else -> Mode.DRAWING
                }
                if (mode == Mode.DRAWING && freeform) {
                    // Start a fresh circle: drop any old box, begin the trail.
                    selection = null
                    strokePath.reset()
                    strokePath.moveTo(x, y)
                    strokeMinX = x; strokeMaxX = x
                    strokeMinY = y; strokeMaxY = y
                    hasStroke = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // A real drag (past the tap threshold) is the user taking
                // over: a box a QR tap drew becomes an ordinary selection and
                // the highlights hide. Sub-threshold jitter during a tap must
                // not demote it, or the tap-on-code gesture would misfire.
                if (mode != Mode.NONE && qrSelectionActive &&
                    hypot(x - downX, y - downY) >= dp(8f)
                ) {
                    qrSelectionActive = false
                }
                when (mode) {
                    Mode.DRAWING -> {
                        if (freeform) {
                            strokePath.lineTo(x, y)
                            strokeMinX = min(strokeMinX, x); strokeMaxX = max(strokeMaxX, x)
                            strokeMinY = min(strokeMinY, y); strokeMaxY = max(strokeMaxY, y)
                        } else {
                            selection = RectF(
                                min(downX, x), min(downY, y),
                                max(downX, x), max(downY, y)
                            )
                        }
                    }
                    Mode.RESIZING -> selection?.let { resize(it, x, y) }
                    Mode.MOVING -> selection?.let { moveTo(it, x, y) }
                    Mode.NONE -> Unit
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A plain tap on a QR highlight opens its actions AND selects
                // the code's box, so it can be shared/copied as an image too.
                // Only while the highlights are visible (no selection, or the
                // untouched box from a previous QR tap) — an invisible tap
                // target would be a trap. Claimed before the crop logic below:
                // there a motionless tap is otherwise a no-op (freeform:
                // dust-sized stroke discarded; rect: nothing to clear).
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    (selection == null || qrSelectionActive) &&
                    mode != Mode.RESIZING &&
                    hypot(x - downX, y - downY) < dp(8f)
                ) {
                    val code = qrAt(downX, downY)
                    if (code != null) {
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
                        if (hasStroke) {
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
                    } else if (hypot(x - downX, y - downY) < dp(8f)) {
                        // A plain tap outside the selection clears it.
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
        val x = snapX(xRaw)
        val y = snapY(yRaw)
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
}
