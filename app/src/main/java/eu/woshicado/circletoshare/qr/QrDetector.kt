package eu.woshicado.circletoshare.qr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A QR code found in a screenshot: its decoded payload and roughly where it
 *  sits, in bitmap pixels. */
data class QrCode(val text: String, val bounds: Rect)

/**
 * Offline QR detection on a frozen screenshot, via ZXing. Screenshots are the
 * decoder's best case — pixel-perfect, axis-aligned, unblurred — so a single
 * full-resolution pass (plus an inverted one for dark-mode codes) is enough.
 */
object QrDetector {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Scans [bitmap] on a worker thread and delivers the found codes on the
     * main thread, possibly empty. The bitmap is only read, never mutated or
     * kept — the caller decides whether the result still applies (the overlay
     * may have been dismissed or reloaded while the scan ran).
     */
    fun detect(bitmap: Bitmap, callback: (List<QrCode>) -> Unit): Scan {
        val scan = Scan()
        Thread({
            val codes = try {
                decode(bitmap) { scan.cancelled }
            } catch (t: Throwable) {
                // Decoding is best-effort garnish on the crop tool — a decoder
                // crash (or OOM on the pixel copy) must never take the overlay
                // down with it.
                emptyList()
            }
            if (!scan.cancelled) main.post { if (!scan.cancelled) callback(codes) }
        }, "qr-detect").start()
        return scan
    }

    /**
     * A scan under way. [cancel] it once nobody wants its result — the crop
     * tool closed, or showing another picture: a long picture takes a
     * while to go through and is held in memory for as long, and "Capture
     * more" replaces it at every step.
     */
    class Scan internal constructor() {
        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    /**
     * The codes in [bitmap], on the calling thread.
     *
     * A screenshot is decoded in one go. A long picture ("Capture more":
     * many screens joined into one) is too much to copy out at once — every
     * pixel becomes an int on the heap — so it is gone through in windows
     * of a few thousand rows, each overlapping the last by the picture's
     * width: a code is square and no wider than the picture, so whichever
     * window boundary it lies across, it lies whole in one of the two.
     */
    internal fun decode(bitmap: Bitmap, cancelled: () -> Boolean = { false }): List<QrCode> {
        val width = bitmap.width
        val height = bitmap.height
        val window = if (height <= MAX_ROWS_AT_ONCE) height else max(2 * width, MAX_ROWS_AT_ONCE - width)
        val found = ArrayList<QrCode>()
        var top = 0
        while (!cancelled()) {
            val rows = min(window, height - top)
            val pixels = IntArray(width * rows)
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            val source = RGBLuminanceSource(width, rows, pixels)
            decodePass(source, width, rows, top, found)
            if (cancelled()) break
            // Dark-mode QR codes are light-on-dark, which ZXing won't read
            // directly; a second pass on inverted luminance catches them.
            decodePass(source.invert(), width, rows, top, found)
            if (top + rows >= height) break
            top += window - width
        }
        return found
    }

    // The tallest picture decoded in one piece: more than any one screen.
    private const val MAX_ROWS_AT_ONCE = 4096

    /** [top]: the row of the whole picture that row 0 of [source] is. */
    private fun decodePass(
        source: LuminanceSource,
        width: Int,
        height: Int,
        top: Int,
        out: MutableList<QrCode>
    ) {
        val results: Array<Result> = try {
            QRCodeMultiReader().decodeMultiple(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
                )
            )
        } catch (e: com.google.zxing.NotFoundException) {
            return // no codes in this pass — the normal outcome
        }
        for (result in results) {
            val text = result.text ?: continue
            val bounds = boundsOf(result, width, height)?.apply { offset(0, top) } ?: continue
            // The same code found by both passes, in two windows of a long
            // picture, or twice in one collapses:
            // its two boxes share a center. Two on-screen copies of one
            // payload both survive, even side by side — their padded boxes
            // may overlap, but neither's center lies inside the other.
            val duplicate = out.any {
                it.text == text && it.bounds.contains(bounds.centerX(), bounds.centerY())
            }
            if (!duplicate) out.add(QrCode(text, bounds))
        }
    }

    /**
     * A tap-target box around the code. ZXing reports the finder-pattern
     * centers, which sit 3.5 modules inside the code's edge — the box they
     * span misses the outer ring. Padding by a quarter of the span is exact
     * for the smallest (version 1) codes and errs slightly large for bigger
     * ones, which for a highlight is the right direction to err.
     */
    private fun boundsOf(result: Result, width: Int, height: Int): Rect? {
        val points = result.resultPoints ?: return null
        if (points.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p == null) continue
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        if (minX > maxX) return null
        val pad = max(max(maxX - minX, maxY - minY) * 0.25f, 16f)
        return Rect(
            max(0, (minX - pad).roundToInt()),
            max(0, (minY - pad).roundToInt()),
            min(width, (maxX + pad).roundToInt()),
            min(height, (maxY + pad).roundToInt())
        )
    }
}
