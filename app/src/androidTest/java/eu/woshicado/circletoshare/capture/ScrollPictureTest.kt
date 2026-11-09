package eu.woshicado.circletoshare.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.qr.QrDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * A "Capture more" run without a screen: frames cut from a made-up page,
 * joined by the real [ScrollPicture], and the picture that comes out looked
 * at as the crop tool would look at it.
 */
@RunWith(AndroidJUnit4::class)
class ScrollPictureTest {

    /**
     * A page [PAGE_HEIGHT] rows long, as an app would scroll it between a
     * toolbar of [HEADER] rows and a bottom bar of [FOOTER]: scattered bars
     * for the joining to go by (as text would be), a plain margin of
     * [MARGIN] on the right, and three QR codes —
     *  - [ON_SCREEN], whole in the first screenful;
     *  - [HALF_ON_SCREEN], cut off by the first screenful's lower edge;
     *  - [OFF_SCREEN], several screens further down.
     */
    private val page: Bitmap = Bitmap.createBitmap(WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.WHITE)
        val random = Random(7)
        val paint = Paint()
        repeat(260) {
            val gray = 40 + random.nextInt(160)
            paint.color = Color.rgb(gray, gray, gray)
            // (Clear of the right edge: a page has a margin, and what
            // floats there floats over plain background.)
            val left = random.nextInt(WIDTH - MARGIN - 60)
            val top = random.nextInt(PAGE_HEIGHT - 30)
            val right = (left + 40 + random.nextInt(260)).coerceAtMost(WIDTH - MARGIN)
            canvas.drawRect(Rect(left, top, right, top + 6 + random.nextInt(22)), paint)
        }
        for ((text, top) in CODES) drawQr(canvas, text, QR_LEFT, top)
    }

    private fun drawQr(canvas: Canvas, text: String, left: Int, top: Int) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val pixels = IntArray(QR_SIZE * QR_SIZE) {
            if (matrix[it % QR_SIZE, it / QR_SIZE]) Color.BLACK else Color.WHITE
        }
        canvas.drawBitmap(pixels, 0, QR_SIZE, left, top, QR_SIZE, QR_SIZE, false, null)
    }

    /**
     * The screen with the page scrolled down by [scroll] rows. On top of
     * the list, if asked for: a scrollbar's handle at the right edge, its
     * middle at row [handleAt]; a bar across the bottom of the list
     * ([slidingBar]), as a browser's that slides away once the page moves.
     */
    private fun frame(scroll: Int, handleAt: Int? = null, slidingBar: Boolean = false): Bitmap =
        Bitmap.createBitmap(WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(30, 60, 120)) // the two bars
            val list = Rect(0, HEADER, WIDTH, FRAME_HEIGHT - FOOTER)
            canvas.drawBitmap(page, Rect(0, scroll, WIDTH, scroll + list.height()), list, null)
            val label = Paint().apply { color = Color.WHITE; textSize = 40f }
            canvas.drawText("Toolbar", 24f, 56f, label)
            if (handleAt != null) {
                val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 60, 64) }
                canvas.drawCircle(WIDTH - 54f, handleAt.toFloat(), 40f, disc)
                canvas.drawRect(WIDTH - 64f, handleAt - 6f, WIDTH - 44f, handleAt + 6f, label)
            }
            if (slidingBar) {
                val bar = Paint().apply { color = Color.rgb(48, 48, 56) }
                canvas.drawRect(0f, list.bottom - 130f, WIDTH.toFloat(), list.bottom.toFloat(), bar)
                canvas.drawCircle(WIDTH - 60f, list.bottom - 65f, 28f, label)
            }
        }

    /** Capture from [scrolls]' first on through the rest, [going] one way;
     *  [frameAt] is the screen at each. */
    private fun capture(
        going: ScrollCapture.Direction, scrolls: List<Int>, frameAt: (Int) -> Bitmap = { frame(it) }
    ): Bitmap {
        val picture = ScrollPicture()
        val first = frameAt(scrolls.first())
        picture.start(first, picture.signatureOf(first), null)
        for ((from, to) in scrolls.zipWithNext()) {
            val moved = kotlin.math.abs(to - from)
            // (Asked for a little more than it moved, as a list that takes
            // up the first pixels of a drag makes it.)
            val joined = picture.join(going, frameAt(to), moved + 14, 0, 0, 0)
            assertEquals(
                "from $from to $to", ScrollCapture.Outcome.EXTENDED, picture.commit(going, joined, null)
            )
        }
        return picture.bitmap!!
    }

    /** Where in the picture a code at page row [top] is, if the picture
     *  begins with the page scrolled to [firstScroll]. */
    private fun expectedAt(top: Int, firstScroll: Int) =
        Rect(QR_LEFT, HEADER + top - firstScroll, QR_LEFT + QR_SIZE, HEADER + top - firstScroll + QR_SIZE)

    private fun assertAllThreeFound(picture: Bitmap, firstScroll: Int) {
        val found: List<QrCode> = QrDetector.decode(picture)
        assertEquals(
            "each code once: ${found.map { it.text }}",
            CODES.map { it.first }.sorted(), found.map { it.text }.sorted()
        )
        for ((text, top) in CODES) {
            val code = found.firstOrNull { it.text == text }
            assertNotNull(code)
            val expected = expectedAt(top, firstScroll)
            assertTrue(
                "$text is at ${code!!.bounds}, not around $expected",
                code.bounds.contains(expected.centerX(), expected.centerY()) &&
                    kotlin.math.abs(code.bounds.centerY() - expected.centerY()) < QR_SIZE / 4
            )
        }
    }

    @Test
    fun theMadeUpPageIsWhatItIsMeantToBe() {
        // Whole in the first screenful, cut by its edge, and well below it.
        val listBottom = FRAME_HEIGHT - HEADER - FOOTER
        assertTrue(CODES[0].second + QR_SIZE < listBottom)
        assertTrue(CODES[1].second < listBottom && CODES[1].second + QR_SIZE > listBottom)
        assertTrue(CODES[2].second > 2 * listBottom)
        // And the first screenful alone shows only the first.
        assertEquals(listOf(ON_SCREEN), QrDetector.decode(frame(0)).map { it.text })
    }

    @Test
    fun qrCodesOnScreenHalfOnScreenAndScrolledIntoViewAreAllFound() {
        val picture = capture(ScrollCapture.Direction.DOWN, (0..LAST_SCROLL step STEP).toList())
        assertEquals(FRAME_HEIGHT + LAST_SCROLL, picture.height)
        // (Long enough to be decoded in windows, as a real one is.)
        assertTrue(picture.height > 4096)
        assertAllThreeFound(picture, firstScroll = 0)
    }

    @Test
    fun theSameGoingUp() {
        val picture = capture(ScrollCapture.Direction.UP, (LAST_SCROLL downTo 0 step STEP).toList())
        assertEquals(FRAME_HEIGHT + LAST_SCROLL, picture.height)
        assertAllThreeFound(picture, firstScroll = 0)
    }

    /** How many pixels of [a] and [b] differ, in the rows [rows]. */
    private fun differing(a: Bitmap, b: Bitmap, rows: IntRange): Int {
        assertEquals(a.height, b.height)
        val pa = IntArray(WIDTH)
        val pb = IntArray(WIDTH)
        var n = 0
        for (y in rows) {
            a.getPixels(pa, 0, WIDTH, 0, y, WIDTH, 1)
            b.getPixels(pb, 0, WIDTH, 0, y, WIDTH, 1)
            for (x in 0 until WIDTH) if (pa[x] != pb[x]) n++
        }
        return n
    }

    @Test
    fun aScrollbarHandleIsNotInThePictureOnceForEveryFrame() {
        val scrolls = (0..LAST_SCROLL step STEP).toList()
        val plain = capture(ScrollCapture.Direction.DOWN, scrolls)
        // As a gallery's: not there until the list moves, then a little
        // further down the screen with every step.
        val withHandle = capture(ScrollCapture.Direction.DOWN, scrolls) { scroll ->
            frame(scroll, handleAt = if (scroll == 0) null else 380 + scroll / 20)
        }
        // Everything down to the last screenful's lower half is as if
        // there had been no handle (the last frame's own, in its upper
        // half, is painted over as well).
        assertEquals(0, differing(plain, withHandle, 0 until plain.height))
    }

    @Test
    fun aBarThatSlidesAwayIsNotPaintedIntoTheMiddle() {
        val scrolls = (0..LAST_SCROLL step STEP).toList()
        val plain = capture(ScrollCapture.Direction.DOWN, scrolls)
        // A browser's bottom bar: in the first frame, gone once the page
        // has moved.
        val withBar = capture(ScrollCapture.Direction.DOWN, scrolls) { scroll ->
            frame(scroll, slidingBar = scroll == 0)
        }
        assertEquals(0, differing(plain, withBar, 0 until plain.height))
    }

    @Test
    fun aCodeAcrossTheEdgeOfTwoDecodingWindowsIsFoundOnce() {
        // Tall and plain. At this width the windows are 3376 rows, each
        // beginning 2656 after the last: one code well inside the first,
        // one across where the second begins, one across where the first
        // ends, and so on down.
        val tall = Bitmap.createBitmap(WIDTH, 9000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tall)
        canvas.drawColor(Color.WHITE)
        val tops = listOf(100, 2500, 3200, 5900, 8600)
        for (top in tops) drawQr(canvas, "at $top", QR_LEFT, top)
        assertEquals(
            tops.map { "at $it" },
            QrDetector.decode(tall).sortedBy { it.bounds.top }.map { it.text }
        )
    }

    private companion object {
        const val WIDTH = 720
        const val FRAME_HEIGHT = 1280
        const val HEADER = 80
        const val FOOTER = 60
        const val PAGE_HEIGHT = 5000
        const val STEP = 560
        const val LAST_SCROLL = 3360 // six steps

        const val MARGIN = 130

        const val QR_SIZE = 300
        const val QR_LEFT = 200
        const val ON_SCREEN = "https://example.com/on-screen"
        const val HALF_ON_SCREEN = "https://example.com/half-on-screen"
        const val OFF_SCREEN = "https://example.com/off-screen"

        /** Each code and the page row its top is at. */
        val CODES = listOf(ON_SCREEN to 300, HALF_ON_SCREEN to 1000, OFF_SCREEN to 3700)
    }
}
