package eu.woshicado.circletoshare.crop

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.woshicado.circletoshare.qr.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [CropOverlayView] with synthetic touch streams. The view is laid out
 * at exactly the bitmap's size, so view and bitmap coordinates coincide.
 */
@RunWith(AndroidJUnit4::class)
class CropOverlayViewGestureTest {

    private class Recorder : CropOverlayView.Listener {
        var hasSelection = false
        var doubleTapInside = 0
        var longPressInside = 0
        var doubleTapEmpty = 0
        val qrTaps = ArrayList<QrCode>()

        override fun onSelectionChanged(hasSelection: Boolean) {
            this.hasSelection = hasSelection
        }

        override fun onDoubleTapInside() {
            doubleTapInside++
        }

        override fun onLongPressInside() {
            longPressInside++
        }

        override fun onDoubleTapEmpty() {
            doubleTapEmpty++
        }

        override fun onQrTapped(code: QrCode) {
            qrTaps.add(code)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val events = Recorder()
    private lateinit var view: CropOverlayView
    private var density = 1f

    // Synthetic event clock. Each touch stream is dispatched in one go on the
    // main thread, so the gesture detector's (real-time) long-press timer
    // never gets to run mid-stream; only these event times drive tap/double-
    // tap classification.
    private var clock = 0L

    @Before
    fun setUp() = onMain {
        val context = instrumentation.targetContext
        density = context.resources.displayMetrics.density
        view = CropOverlayView(context)
        view.listener = events
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        view.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        clock = SystemClock.uptimeMillis()
    }

    // --- Drawing a crop -----------------------------------------------------

    @Test
    fun freeform_strokeBecomesItsBoundingBox() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)

        assertTrue(events.hasSelection)
        val crop = cropRect()!!
        assertTrue("crop $crop should cover the stroke", crop.contains(Rect(200, 600, 800, 1300)))
        assertTrue("crop $crop should hug the stroke", Rect(150, 550, 850, 1350).contains(crop))
    }

    @Test
    fun rectangle_dragRubberBandsCornerToCorner() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 500f to 900f, 800f to 1300f)

        assertTrue(events.hasSelection)
        assertRectNear(Rect(200, 600, 800, 1300), cropRect())
    }

    @Test
    fun rectangle_tapOutsideClearsSelection() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        assertTrue(events.hasSelection)

        tap(100f, 2000f)

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun tinyScribbleIsDiscarded() {
        stroke(500f to 500f, 503f to 502f)

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun freeform_wobblyTapLeavesNoBox() {
        // Under the tap slop on both axes, yet enough that the padded
        // bounding box would pass the minimum selection size.
        val wobble = dp(5f).toFloat()
        stroke(500f to 1500f, 500f + wobble to 1500f + wobble)

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun freeform_tapOnASnappableElementLeavesNoBox() {
        // An icon-sized element: snapping would pull a tap's dust-sized box
        // out to its edges, past the minimum selection size.
        val side = dp(32f)
        val icon = Rect(500, 1500, 500 + side, 1500 + side)
        onMain { view.setSnapBounds(SnapBounds(listOf(icon), WIDTH, HEIGHT)) }

        tap(icon.exactCenterX(), icon.exactCenterY())

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun freeform_strokeSnapsToNearbyElementEdges() {
        val card = Rect(200, 600, 800, 1300)
        onMain { view.setSnapBounds(SnapBounds(listOf(card), WIDTH, HEIGHT)) }

        // Drawn just inside the card: padding takes each edge to within the
        // snap threshold of the card's, and it pulls onto them.
        val inset = dp(12f).toFloat()
        stroke(
            200f + inset to 600f + inset, 800f - inset to 600f + inset,
            800f - inset to 1300f - inset, 200f + inset to 1300f - inset
        )

        // Tighter than the usual tolerance: unsnapped, the box would be only
        // inset - padding = 2dp off the card.
        assertRectNear(card, cropRect(), tolerance = 1)
    }

    @Test
    fun rectangle_boxDrawnTightlyAroundALineOfText_isKept() {
        // A single line of text: wide, but lower than the smallest box the
        // tool keeps. Snapping the drawn box onto it top and bottom must not
        // be what gets the box thrown away as a scribble.
        val line = Rect(200, 1000, 800, 1000 + dp(18f))
        onMain {
            view.freeform = false
            view.setSnapBounds(SnapBounds(listOf(line), WIDTH, HEIGHT))
        }

        // Rubber-banded around it with 6dp to spare on every side: 30dp
        // high as drawn, and every edge within snapping range of the line's.
        val slack = dp(6f).toFloat()
        stroke(
            line.left - slack to line.top - slack,
            line.exactCenterX() to line.exactCenterY(),
            line.right + slack to line.bottom + slack
        )

        assertTrue("the box should still be there", events.hasSelection)
        val box = cropRect()!!
        // Left and right snap onto the line's as usual…
        assertEquals(line.left.toFloat(), box.left.toFloat(), 1f)
        assertEquals(line.right.toFloat(), box.right.toFloat(), 1f)
        // …top and bottom stay as drawn, where snapping would leave too little.
        assertEquals(line.top - slack, box.top.toFloat(), 1f)
        assertEquals(line.bottom + slack, box.bottom.toFloat(), 1f)
    }

    @Test
    fun freeform_loopDrawnTightlyAroundAnIcon_isKept() {
        // Small in both directions.
        val side = dp(18f)
        val icon = Rect(500, 1500, 500 + side, 1500 + side)
        onMain { view.setSnapBounds(SnapBounds(listOf(icon), WIDTH, HEIGHT)) }

        // A loop just inside the icon's outline: with its padding the box
        // comes to 7dp around the icon — 32dp a side, and every edge within
        // snapping range of the icon's.
        val inset = dp(3f).toFloat()
        stroke(
            icon.left + inset to icon.top + inset, icon.right - inset to icon.top + inset,
            icon.right - inset to icon.bottom - inset, icon.left + inset to icon.bottom - inset
        )

        assertTrue("the box should still be there", events.hasSelection)
        val box = cropRect()!!
        assertTrue(box.width() >= dp(24f) && box.height() >= dp(24f))
        assertTrue("and still holds the icon", box.contains(icon))
    }

    // --- A view that covers only part of the display ------------------------

    @Test
    fun viewShortOfTheBottomEdge_showsTheScreenshotUnscaled() {
        // As when the overlay leaves a navigation bar uncovered: the view is
        // shorter than the display the screenshot is of.
        val strip = 132
        relayout(WIDTH, HEIGHT - strip)
        onMain { view.setDisplayFrame(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())) }

        onMain { view.freeform = false }
        stroke(200f to 600f, 500f to 900f, 800f to 1300f)

        // One view pixel is one screenshot pixel: nothing was shrunk to fit.
        assertRectNear(Rect(200, 600, 800, 1300), cropRect())
    }

    @Test
    fun viewShortOfTheBottomEdge_cannotSelectIntoTheUncoveredStrip() {
        val strip = 132
        relayout(WIDTH, HEIGHT - strip)
        onMain { view.setDisplayFrame(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())) }

        onMain { view.freeform = false }
        // The finger runs on past the view's bottom edge, over where the
        // uncovered strip of the screenshot would be.
        stroke(200f to 1800f, 500f to 2000f, 800f to (HEIGHT - 10).toFloat())

        val crop = cropRect()!!
        assertEquals("the crop must end where the view does", HEIGHT - strip, crop.bottom)
        assertEquals(200, crop.left)
        assertEquals(1800, crop.top)
    }

    @Test
    fun viewShortOfTheBottomEdge_boxSnappedTowardsTheUncoveredStrip_isKept() {
        val strip = 132
        val viewBottom = HEIGHT - strip
        relayout(WIDTH, viewBottom)
        onMain { view.setDisplayFrame(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())) }
        // An element starting 22dp above the view's bottom edge and ending
        // 6dp below it, in the strip the view doesn't cover.
        val element = Rect(0, viewBottom - dp(22f), WIDTH, viewBottom + dp(6f))
        onMain {
            view.setSnapBounds(SnapBounds(listOf(element), WIDTH, HEIGHT))
            view.freeform = false
        }

        // A box 30dp high down to the view's last row. Its top snaps down
        // onto the element's, its bottom would snap out to the element's —
        // but can't leave the image, and what is left is under the minimum.
        val bottom = (viewBottom - 1).toFloat()
        val top = bottom - dp(30f)
        stroke(200f to top, 500f to top + dp(15f), 800f to bottom)

        assertTrue("the box should still be there", events.hasSelection)
        val box = cropRect()!!
        assertTrue("as high as it was drawn, not $box", box.height() >= dp(29f))
        assertTrue(box.bottom <= viewBottom)
    }

    @Test
    fun viewShortOfTheBottomEdge_resizeCannotSnapIntoTheUncoveredStrip() {
        val strip = 132
        val viewBottom = HEIGHT - strip
        relayout(WIDTH, viewBottom)
        onMain { view.setDisplayFrame(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())) }
        // An element whose bottom edge lies just inside the uncovered strip:
        // a snap line a few pixels below the view's last row.
        onMain {
            view.setSnapBounds(SnapBounds(listOf(Rect(0, 1000, WIDTH, viewBottom + dp(4f))), WIDTH, HEIGHT))
            view.freeform = false
        }
        stroke(200f to 1500f, 500f to 1700f, 800f to 1900f)

        // Drag the bottom-right corner down to the view's bottom edge, where
        // that line is within snapping range.
        val box = cropRect()!!
        stroke(
            box.right.toFloat() to box.bottom.toFloat(),
            box.right.toFloat() to box.bottom + 100f,
            box.right.toFloat() to (viewBottom - 1).toFloat()
        )

        val crop = cropRect()!!
        assertTrue("crop $crop must not reach below the view (bottom $viewBottom)", crop.bottom <= viewBottom)
    }

    @Test
    fun viewOffsetFromTheLeftEdge_mapsTouchesToTheRightPixels() {
        // Landscape with the navigation bar on the left: the view starts
        // beside it. (Portrait numbers here; only the offset matters.)
        val bar = 132
        relayout(WIDTH - bar, HEIGHT)
        onMain {
            view.setDisplayFrame(RectF(-bar.toFloat(), 0f, (WIDTH - bar).toFloat(), HEIGHT.toFloat()))
        }

        onMain { view.freeform = false }
        stroke(100f to 600f, 300f to 900f, 500f to 1300f)

        // The view's x = 100 is the display's (and the screenshot's) x = 232.
        assertRectNear(Rect(100 + bar, 600, 500 + bar, 1300), cropRect())
    }

    @Test
    fun changingTheDisplayFrame_discardsTheSelection() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        assertTrue(events.hasSelection)

        onMain { view.setDisplayFrame(RectF(-132f, 0f, WIDTH - 132f, HEIGHT.toFloat())) }

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    // --- Cancelled touches --------------------------------------------------

    @Test
    fun freeform_cancelledTouchOutsideTheBox_leavesTheBoxAlone() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val before = cropRect()

        cancelledStroke(20f to 1800f)

        assertTrue(events.hasSelection)
        assertEquals(before, cropRect())
    }

    @Test
    fun freeform_cancelledSwipe_doesNotBecomeTheCrop() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val before = cropRect()

        // An edge swipe the system takes over part-way.
        cancelledStroke(5f to 1800f, 120f to 1790f, 300f to 1780f)

        assertTrue(events.hasSelection)
        assertEquals(before, cropRect())
    }

    @Test
    fun freeform_cancelledSwipeWithNoBox_leavesNoBox() {
        cancelledStroke(5f to 1800f, 120f to 1790f, 300f to 1700f)

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun rectangle_cancelledTouchOutsideTheBox_leavesTheBoxAlone() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        val before = cropRect()

        cancelledStroke(20f to 1800f)
        assertEquals(before, cropRect())

        cancelledStroke(5f to 1800f, 120f to 1790f, 300f to 1700f)
        assertTrue(events.hasSelection)
        assertEquals(before, cropRect())
    }

    @Test
    fun cancelledMoveOrResize_putsTheBoxBack() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        val before = cropRect()

        cancelledStroke(500f to 900f, 560f to 960f, 650f to 1050f) // a move
        assertEquals(before, cropRect())

        cancelledStroke(800f to 1300f, 850f to 1400f, 900f to 1500f) // a resize
        assertEquals(before, cropRect())
    }

    @Test
    fun cancelledTouchOnSelectedQr_keepsItAQrSelection() {
        val code = showQr(Rect(300, 300, 700, 700))
        tap(500f, 500f)
        assertEquals(1, events.qrTaps.size)

        cancelledStroke(500f to 500f, 560f to 560f, 640f to 640f)

        // Still the untouched box of the code: its highlight is showing, so
        // a tap on it opens it again.
        assertRectNear(code.bounds, cropRect())
        tap(500f, 500f)
        assertEquals(2, events.qrTaps.size)
    }

    @Test
    fun cancelAfterTheViewWasResized_doesNotBringTheOldBoxBack() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        assertTrue(events.hasSelection)

        // Finger down inside the box; the screen rotates under it (which
        // discards the box — it no longer means anything); then the touch is
        // cancelled.
        onMain {
            val downTime = clock
            send(downTime, MotionEvent.ACTION_DOWN, 500f to 900f)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, HEIGHT, WIDTH)
            assertNull(view.getCropRect())
            clock += 10
            send(downTime, MotionEvent.ACTION_CANCEL, 500f to 900f)
            clock += 1000
        }

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    // --- More than one finger -----------------------------------------------

    @Test
    fun secondFingerDuringADrag_isIgnored() {
        onMain { view.freeform = false }
        onMain {
            val t = clock
            sendFingers(t, MotionEvent.ACTION_DOWN, 0, 1 to (200f to 600f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 1 to (400f to 800f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 800f), 2 to (900f to 2000f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 1 to (600f to 1000f), 2 to (950f to 2100f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_UP, 1, 1 to (600f to 1000f), 2 to (950f to 2100f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 1 to (800f to 1300f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_UP, 0, 1 to (800f to 1300f))
            clock += 1000
        }

        assertRectNear(Rect(200, 600, 800, 1300), cropRect())
    }

    @Test
    fun firstFingerLiftingFirst_endsTheGestureThere() {
        // Circle mode; two fingers tap, and the one that came down first
        // lifts first. Nothing was drawn — in particular no box spanning the
        // two fingers.
        onMain {
            val t = clock
            sendFingers(t, MotionEvent.ACTION_DOWN, 0, 1 to (300f to 800f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (300f to 800f), 2 to (700f to 1400f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_UP, 0, 1 to (300f to 800f), 2 to (700f to 1400f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 2 to (720f to 1500f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_UP, 0, 2 to (720f to 1500f))
            clock += 1000
        }

        assertFalse(events.hasSelection)
        assertNull(cropRect())
    }

    @Test
    fun firstFingerLiftingFirstMidDrag_keepsWhatItDrew() {
        onMain { view.freeform = false }
        onMain {
            val t = clock
            sendFingers(t, MotionEvent.ACTION_DOWN, 0, 1 to (200f to 600f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 1 to (500f to 900f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 1 to (800f to 1300f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (800f to 1300f), 2 to (100f to 2200f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_UP, 0, 1 to (800f to 1300f), 2 to (100f to 2200f))
            // The straggler wanders off and lifts; none of it counts.
            clock += 10; sendFingers(t, MotionEvent.ACTION_MOVE, 0, 2 to (50f to 100f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_UP, 0, 2 to (50f to 100f))
            clock += 1000
        }

        assertRectNear(Rect(200, 600, 800, 1300), cropRect())
    }

    @Test
    fun doubleTapWhoseSecondTouchGainsAFinger_doesNothing() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val before = cropRect()

        onMain {
            val first = clock
            send(first, MotionEvent.ACTION_DOWN, 500f to 900f)
            clock += 50
            send(first, MotionEvent.ACTION_UP, 500f to 900f)
            clock += 100
            val t = clock
            sendFingers(t, MotionEvent.ACTION_DOWN, 0, 1 to (500f to 900f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (500f to 900f), 2 to (900f to 2000f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_UP, 1, 1 to (500f to 900f), 2 to (900f to 2000f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_UP, 0, 1 to (500f to 900f))
            clock += 1000
        }

        assertEquals("a two-finger touch is not a tap", 0, events.doubleTapInside)
        assertEquals(before, cropRect())
    }

    // --- Double-tap / long-press shortcuts ----------------------------------

    @Test
    fun doubleTapWhoseFirstTouchThisViewTookForAStroke_doesNotShare() {
        // A wobble in the sliver where this view already sees a stroke (it
        // leaves a small box) but GestureDetector, which truncates to whole
        // pixels and compares strictly, still sees a tap.
        val slop = ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop
        // Whole-pixel parts (p, q) that the detector still calls a tap, with
        // fractions on top that carry this view's own distance past the
        // slop — and far enough on both axes that the stroke leaves a box.
        val wobble = (slop / 2..slop).firstNotNullOfOrNull { p ->
            (slop / 2..slop).firstOrNull { q ->
                p * p + q * q <= slop * slop && kotlin.math.hypot(p + 0.9f, q + 0.9f) >= slop
            }?.let { q -> p + 0.9f to q + 0.9f }
        }
        assertNotNull("there is such a sliver at every touch slop", wobble)
        wobble!!

        onMain {
            repeat(2) { n ->
                val downTime = clock
                send(downTime, MotionEvent.ACTION_DOWN, 500f to 1500f)
                if (n == 0) {
                    clock += 20
                    send(downTime, MotionEvent.ACTION_MOVE, 500f + wobble.first to 1500f + wobble.second)
                }
                clock += 30
                send(downTime, MotionEvent.ACTION_UP, if (n == 0) 500f + wobble.first to 1500f + wobble.second else 500f to 1500f)
                clock += 100
            }
            clock += 1000
        }

        assertEquals(0, events.doubleTapInside)
        assertEquals(0, events.doubleTapEmpty)
    }

    @Test
    fun doubleTapOnEmptySpaceWithNoSelection_exits() {
        doubleTap(500f, 1500f)

        assertEquals(1, events.doubleTapEmpty)
    }

    @Test
    fun doubleTapInsideSelection_shares() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val before = cropRect()

        doubleTap(500f, 900f)

        assertEquals(1, events.doubleTapInside)
        assertEquals(0, events.doubleTapEmpty)
        assertEquals("the selection must survive the shortcut untouched", before, cropRect())
    }

    @Test
    fun wobblyDoubleTapInsideSmallSelection_sharesTheWholeBox() {
        // Small enough that every point inside is within reach of a corner
        // handle: the taps must still count as "inside", not as resizes.
        val before = selectSmallBox()
        val cx = before.exactCenterX()
        val cy = before.exactCenterY()
        val wobble = dp(3f).toFloat()

        onMain {
            repeat(2) {
                val downTime = clock
                send(downTime, MotionEvent.ACTION_DOWN, cx to cy)
                clock += 20
                send(downTime, MotionEvent.ACTION_MOVE, cx + wobble to cy + wobble)
                clock += 30
                send(downTime, MotionEvent.ACTION_UP, cx + wobble to cy + wobble)
                clock += 100
            }
            clock += 1000
        }

        assertEquals(1, events.doubleTapInside)
        assertEquals(0, events.doubleTapEmpty)
        assertEquals("the box must reach the share untouched", before, cropRect())
    }

    @Test
    fun wobblyLongPressInsideSmallSelection_copiesTheWholeBox() {
        val before = selectSmallBox()
        val cx = before.exactCenterX()
        val cy = before.exactCenterY()
        val wobble = dp(3f).toFloat()

        // Real time here: the long-press timer is the thing under test.
        val downTime = SystemClock.uptimeMillis()
        onMain {
            clock = downTime
            send(downTime, MotionEvent.ACTION_DOWN, cx to cy)
            clock = downTime + 10
            send(downTime, MotionEvent.ACTION_MOVE, cx + wobble to cy + wobble)
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 400L)
        instrumentation.waitForIdleSync()
        onMain {
            clock = SystemClock.uptimeMillis()
            send(downTime, MotionEvent.ACTION_UP, cx + wobble to cy + wobble)
            clock += 1000
        }

        assertEquals(1, events.longPressInside)
        assertEquals("the box must reach the copy untouched", before, cropRect())
    }

    @Test
    fun wobblyTapOnCornerHandle_leavesTheBoxAlone() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val before = cropRect()!!
        val wobble = dp(3f).toFloat()

        // Just inside the top-left corner: a resize grab, if it were a drag.
        stroke(
            before.left + 10f to before.top + 10f,
            before.left + 10f + wobble to before.top + 10f + wobble
        )

        assertEquals(before, cropRect())
    }

    @Test
    fun draggingACornerHandle_resizes() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)

        stroke(800f to 1300f, 850f to 1400f, 900f to 1500f)

        assertRectNear(Rect(200, 600, 900, 1500), cropRect())
    }

    @Test
    fun draggingInsideTheBox_movesItWithoutResizing() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        val before = cropRect()!!

        // Fine-grained, like a real drag: 200px down-right in 5px steps.
        stroke(*Array(41) { i -> 500f + i * 5f to 900f + i * 5f })

        val after = cropRect()!!
        assertEquals(before.width().toFloat(), after.width().toFloat(), 2f)
        assertEquals(before.height().toFloat(), after.height().toFloat(), 2f)
        // The box follows the finger from where the drag was recognized, so
        // it travels the finger's 200px less at most the tap slop.
        val slop = ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop
        val travelled = after.left - before.left
        assertTrue("box moved $travelled px", travelled in (200 - 2 * slop)..200)
        assertEquals(travelled.toFloat(), (after.top - before.top).toFloat(), 2f)
    }

    @Test
    fun freeform_doubleTapOutsideSelection_clearsItWithoutExiting() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        assertTrue(events.hasSelection)

        doubleTap(100f, 2000f)

        assertEquals("double-tapping outside a box must not close the tool", 0, events.doubleTapEmpty)
        assertFalse(events.hasSelection)
    }

    @Test
    fun rectangle_doubleTapOutsideSelection_clearsItWithoutExiting() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        assertTrue(events.hasSelection)

        doubleTap(100f, 2000f)

        assertEquals("double-tapping outside a box must not close the tool", 0, events.doubleTapEmpty)
        assertFalse(events.hasSelection)
    }

    @Test
    fun freeform_wobblyDoubleTapOutsideSelection_clearsItWithoutSharingOrExiting() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        assertTrue(events.hasSelection)

        // Each tap wobbles under the slop — the first must not leave a stray
        // box for the second to land "inside" of.
        val wobble = dp(5f).toFloat()
        onMain {
            repeat(2) {
                val downTime = clock
                send(downTime, MotionEvent.ACTION_DOWN, 100f to 2000f)
                clock += 20
                send(downTime, MotionEvent.ACTION_MOVE, 100f + wobble to 2000f + wobble)
                clock += 30
                send(downTime, MotionEvent.ACTION_UP, 100f + wobble to 2000f + wobble)
                clock += 100
            }
            clock += 1000
        }

        assertEquals("a double-tap outside must not share", 0, events.doubleTapInside)
        assertEquals("a double-tap outside must not close the tool", 0, events.doubleTapEmpty)
        assertFalse(events.hasSelection)
    }

    @Test
    fun freeform_wobblyDoubleTapOnEmptySpace_exits() {
        val wobble = dp(5f).toFloat()
        onMain {
            repeat(2) {
                val downTime = clock
                send(downTime, MotionEvent.ACTION_DOWN, 500f to 1500f)
                clock += 20
                send(downTime, MotionEvent.ACTION_MOVE, 500f + wobble to 1500f + wobble)
                clock += 30
                send(downTime, MotionEvent.ACTION_UP, 500f + wobble to 1500f + wobble)
                clock += 100
            }
            clock += 1000
        }

        assertEquals(0, events.doubleTapInside)
        assertEquals(1, events.doubleTapEmpty)
    }

    @Test
    fun tapThenQuickStrokeOnEmptySpace_drawsInsteadOfExiting() {
        tapThenQuickly(500f to 1500f) {
            listOf(500f to 1500f, 300f to 1500f, 300f to 1000f, 700f to 1000f, 700f to 1500f)
        }

        assertEquals("a tap followed by a stroke is not a double-tap", 0, events.doubleTapEmpty)
        assertTrue(events.hasSelection)
        assertTrue(cropRect()!!.contains(Rect(300, 1000, 700, 1500)))
    }

    @Test
    fun tapInsideThenQuickDrag_movesTheBoxInsteadOfSharing() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        val before = cropRect()!!

        tapThenQuickly(500f to 900f) { List(41) { i -> 500f + i * 5f to 900f + i * 5f } }

        assertEquals("a tap followed by a drag is not a double-tap", 0, events.doubleTapInside)
        val after = cropRect()!!
        assertEquals(before.width().toFloat(), after.width().toFloat(), 2f)
        assertTrue("the box should have followed the drag", after.left > before.left + 100)
    }

    @Test
    fun tapInsideThenQuickCornerDrag_resizesInsteadOfSharing() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)

        // First tap inside, near the bottom-right corner; second touch grabs
        // that corner and drags it out.
        tapThenQuickly(780f to 1280f) { listOf(790f to 1290f, 850f to 1400f, 900f to 1500f) }

        assertEquals(0, events.doubleTapInside)
        assertRectNear(Rect(200, 600, 900, 1500), cropRect())
    }

    @Test
    fun doubleTapWhoseSecondTapLandsOutsideTheBox_doesNotShare() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        val box = cropRect()!!

        // First tap just inside the right edge, second just outside it.
        tapThenQuickly(box.right - 15f to 900f) { listOf(box.right + 30f to 900f) }

        assertEquals(0, events.doubleTapInside)
        assertEquals(0, events.doubleTapEmpty)
    }

    @Test
    fun tapInsideThenSlowDrag_movesTheBoxWithoutCopying() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        val before = cropRect()!!

        // Real time: the long-press timer of the second touch is under test.
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        val secondDown = tapThenHoldDragging(500f to 900f, 500f to 900f, 560f to 960f)
        SystemClock.sleep(longPress + 400L)
        instrumentation.waitForIdleSync()
        onMain {
            clock = SystemClock.uptimeMillis()
            send(secondDown, MotionEvent.ACTION_MOVE, 600f to 1000f)
            clock += 10
            send(secondDown, MotionEvent.ACTION_UP, 600f to 1000f)
            clock += 1000
        }

        assertEquals("a drag is not a long-press", 0, events.longPressInside)
        assertEquals(0, events.doubleTapInside)
        val after = cropRect()!!
        assertEquals(before.width().toFloat(), after.width().toFloat(), 2f)
        assertTrue("the box should have followed the drag", after.left > before.left + 40)
    }

    @Test
    fun rectangle_tapThenSlowRubberBand_drawsWithoutCopyingOrExiting() {
        onMain { view.freeform = false }

        // The rubber-band grows over its own starting point — "inside the
        // box", as far as a long-press on that point could tell.
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        val secondDown = tapThenHoldDragging(300f to 800f, 300f to 800f, 500f to 1100f)
        SystemClock.sleep(longPress + 400L)
        instrumentation.waitForIdleSync()
        onMain {
            clock = SystemClock.uptimeMillis()
            send(secondDown, MotionEvent.ACTION_MOVE, 800f to 1500f)
            clock += 10
            send(secondDown, MotionEvent.ACTION_UP, 800f to 1500f)
            clock += 1000
        }

        assertEquals("a drag is not a long-press", 0, events.longPressInside)
        assertEquals(0, events.doubleTapEmpty)
        assertRectNear(Rect(300, 800, 800, 1500), cropRect())
    }

    @Test
    fun tapBesideQrThenQuickTapOnIt_opensTheCodeInsteadOfExiting() {
        val code = showQr(Rect(300, 300, 700, 700))

        // First tap on empty space next to the code, second on the code.
        tapThenQuickly(760f to 500f) { listOf(680f to 500f) }

        assertEquals("tapping a code must never close the tool", 0, events.doubleTapEmpty)
        assertEquals(listOf(code), events.qrTaps)
    }

    @Test
    fun freeform_tapToClearThenAtOnceDoubleTap_exits() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        assertTrue(events.hasSelection)

        // Three quick taps: the first clears the box, the next two are the
        // double-tap. (The gesture detector pairs one-two, not two-three.)
        quickTaps(3, 100f, 2000f)

        assertFalse(events.hasSelection)
        assertEquals(0, events.doubleTapInside)
        assertEquals(1, events.doubleTapEmpty)
    }

    @Test
    fun rectangle_tapToClearThenAtOnceDoubleTap_exits() {
        onMain { view.freeform = false }
        stroke(200f to 600f, 800f to 1300f)
        assertTrue(events.hasSelection)

        quickTaps(3, 100f, 2000f)

        assertFalse(events.hasSelection)
        assertEquals(1, events.doubleTapEmpty)
    }

    @Test
    fun fourQuickTapsOnEmptySpace_exitOncePerPair() {
        // One-two exits (the host would close here); should the view live
        // on, three-four is a double-tap of its own, not a leftover.
        quickTaps(4, 500f, 1500f)

        assertEquals(2, events.doubleTapEmpty)
    }

    @Test
    fun pressAndHoldOnEmptySpaceThenATap_doesNotExit() {
        // Real time: the detector's long-press has to fire for it to stop
        // counting the first touch as a tap.
        val hold = ViewConfiguration.getLongPressTimeout() + 400L
        val firstDown = SystemClock.uptimeMillis()
        onMain {
            clock = firstDown
            send(firstDown, MotionEvent.ACTION_DOWN, 500f to 1500f)
        }
        SystemClock.sleep(hold)
        instrumentation.waitForIdleSync()
        onMain {
            clock = SystemClock.uptimeMillis()
            send(firstDown, MotionEvent.ACTION_UP, 500f to 1500f)
            // One tap, promptly after the hold is released.
            clock += 100
            val secondDown = clock
            send(secondDown, MotionEvent.ACTION_DOWN, 500f to 1500f)
            clock += 50
            send(secondDown, MotionEvent.ACTION_UP, 500f to 1500f)
            clock += 1000
        }

        assertEquals("a hold and a tap are not a double-tap", 0, events.doubleTapEmpty)
    }

    @Test
    fun twoFingerTouchOnEmptySpaceThenATap_doesNotExit() {
        onMain {
            val t = clock
            sendFingers(t, MotionEvent.ACTION_DOWN, 0, 1 to (500f to 1500f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (500f to 1500f), 2 to (800f to 1900f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_POINTER_UP, 0, 1 to (500f to 1500f), 2 to (800f to 1900f))
            clock += 10; sendFingers(t, MotionEvent.ACTION_UP, 0, 2 to (800f to 1900f))
            clock += 100
            val tapDown = clock
            send(tapDown, MotionEvent.ACTION_DOWN, 500f to 1500f)
            clock += 50
            send(tapDown, MotionEvent.ACTION_UP, 500f to 1500f)
            clock += 1000
        }

        assertEquals("a two-finger touch is not half a double-tap", 0, events.doubleTapEmpty)
    }

    @Test
    fun twoTapsOnEmptySpaceTooFarApartInTime_doNotExit() {
        tap(500f, 1500f)   // these helpers leave a full second between streams
        tap(500f, 1500f)

        assertEquals(0, events.doubleTapEmpty)
    }

    @Test
    fun slowTapThenATapOnEmptySpaceTooLongAfterItWentDown_doesNotExit() {
        // A double-tap is two touches going down within the timeout of each
        // other. A lingering first tap followed by a prompt second one is
        // not: its second touch comes soon after the first came up, but too
        // long after it went down. (In real time, so the gesture detector
        // lets its own double-tap window lapse as it would under a finger,
        // and the verdict is the view's.)
        val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val firstDown = SystemClock.uptimeMillis()
        onMain {
            clock = firstDown
            send(firstDown, MotionEvent.ACTION_DOWN, 500f to 1500f)
            clock = firstDown + timeout / 2
            send(firstDown, MotionEvent.ACTION_UP, 500f to 1500f)
        }
        SystemClock.sleep(timeout + 200L)
        instrumentation.waitForIdleSync()
        onMain {
            // By the events' own times: down 5/6 of the timeout after the
            // first came up, 4/3 of it after the first went down.
            val secondDown = firstDown + timeout / 2 + timeout * 5 / 6
            clock = secondDown
            send(secondDown, MotionEvent.ACTION_DOWN, 500f to 1500f)
            clock += 50
            send(secondDown, MotionEvent.ACTION_UP, 500f to 1500f)
            clock = SystemClock.uptimeMillis() + 1000
        }

        assertEquals("two unhurried taps are not a double-tap", 0, events.doubleTapEmpty)
    }

    @Test
    fun doubleTapAfterSelectionWasAlreadyCleared_exits() {
        stroke(200f to 600f, 800f to 600f, 800f to 1300f, 200f to 1300f)
        tap(100f, 2000f) // clears the box
        assertFalse(events.hasSelection)

        doubleTap(100f, 2000f)

        assertEquals(1, events.doubleTapEmpty)
    }

    // --- QR codes -----------------------------------------------------------

    @Test
    fun freeform_tapOnQr_opensItAndSelectsItsBox() {
        val code = showQr(Rect(300, 300, 700, 700))

        tap(500f, 500f)

        assertEquals(listOf(code), events.qrTaps)
        assertTrue(events.hasSelection)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun freeform_tapOnQr_withFingerJitter_opensIt() {
        val code = showQr(Rect(300, 300, 700, 700))

        stroke(500f to 500f, 502f to 501f, 501f to 502f)

        assertEquals(listOf(code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun rectangle_tapOnQr_opensIt() {
        onMain { view.freeform = false }
        val code = showQr(Rect(300, 300, 700, 700))

        tap(500f, 500f)

        assertEquals(listOf(code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun rectangle_tapOnQr_withFingerJitter_opensIt() {
        onMain { view.freeform = false }
        val code = showQr(Rect(300, 300, 700, 700))

        // A real finger tap: a couple of sub-slop MOVEs between DOWN and UP.
        stroke(500f to 500f, 502f to 501f, 501f to 502f)

        assertEquals(listOf(code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun freeform_loopStartingOnQrAndClosingOnItsStart_isACropNotATap() {
        showQr(Rect(300, 300, 700, 700))

        // Starts on the code's left edge, circles the code and its
        // surroundings, and closes within a couple of pixels of the start.
        stroke(
            300f to 500f, 100f to 500f, 100f to 1200f, 900f to 1200f,
            900f to 200f, 100f to 200f, 100f to 498f, 302f to 501f
        )

        assertTrue("a drawn loop must not open the QR actions", events.qrTaps.isEmpty())
        assertTrue(events.hasSelection)
        val crop = cropRect()!!
        assertTrue("crop $crop should be the drawn loop", crop.contains(Rect(100, 200, 900, 1200)))
    }

    @Test
    fun dragStartingOnQr_isACropNotATap() {
        showQr(Rect(300, 300, 700, 700))

        stroke(500f to 500f, 700f to 900f, 900f to 1400f)

        assertTrue(events.qrTaps.isEmpty())
        assertTrue(events.hasSelection)
    }

    @Test
    fun secondTapOnSameQr_reopensIt() {
        val code = showQr(Rect(300, 300, 700, 700))

        tap(500f, 500f)
        tap(500f, 500f)

        assertEquals(listOf(code, code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun secondTapOnSmallQr_reopensIt() {
        // Small enough that every point of the box lies within a corner
        // handle's touch radius once it is selected.
        val side = dp(40f)
        val code = showQr(Rect(300, 300, 300 + side, 300 + side))
        val cx = 300f + side / 2f
        val cy = 300f + side / 2f

        tap(cx, cy)
        assertEquals(1, events.qrTaps.size)
        assertTrue(events.hasSelection)

        stroke(cx to cy, cx + 2f to cy + 1f) // second tap, with finger jitter

        assertEquals(listOf(code, code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun secondTapNearCornerOfSelectedQr_reopensIt() {
        val code = showQr(Rect(300, 300, 700, 700))

        tap(500f, 500f)
        tap(310f, 310f) // inside the code, on its top-left corner handle

        assertEquals(listOf(code, code), events.qrTaps)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun doubleTapOnQrTooSmallToSelect_doesNotExit() {
        val side = dp(16f) // below the minimum selection size
        showQr(Rect(300, 300, 300 + side, 300 + side))

        doubleTap(300f + side / 2f, 300f + side / 2f)

        assertEquals("tapping a code must never close the tool", 0, events.doubleTapEmpty)
        assertEquals("each tap on the code opens it", 2, events.qrTaps.size)
    }

    @Test
    fun doubleTapOnSelectableQr_opensItThenSharesItsBox() {
        // "Double-tap inside the box shares" holds for the box a QR tap
        // drew, too: the first tap opens the code and selects its box, the
        // second lands inside that box.
        val code = showQr(Rect(300, 300, 700, 700))

        doubleTap(500f, 500f)

        assertEquals(listOf(code), events.qrTaps)
        assertEquals(1, events.doubleTapInside)
        assertEquals(0, events.doubleTapEmpty)
        assertRectNear(code.bounds, cropRect())
    }

    @Test
    fun tappingAnUnselectableQr_dropsAnotherCodesSelection() {
        onMain { view.freeform = false }
        val side = dp(16f) // below the minimum selection size
        val big = QrCode("https://example.com/big", Rect(300, 300, 700, 700))
        val tiny = QrCode("https://example.com/tiny", Rect(300, 1500, 300 + side, 1500 + side))
        onMain { view.setQrCodes(listOf(big, tiny)) }

        tap(500f, 500f)
        assertRectNear(big.bounds, cropRect())

        tap(300f + side / 2f, 1500f + side / 2f)

        // The tiny code's actions are open; the big one's box must not stay
        // selected behind them.
        assertEquals(listOf(big, tiny), events.qrTaps)
        assertFalse(events.hasSelection)
        assertNull(cropRect())

        // And the highlights are showing again, so the big code can be
        // tapped once more.
        tap(500f, 500f)
        assertEquals(listOf(big, tiny, big), events.qrTaps)
        assertRectNear(big.bounds, cropRect())
    }

    @Test
    fun draggingASelectedQrBox_demotesItToAnOrdinarySelection() {
        showQr(Rect(300, 300, 700, 700))
        tap(500f, 500f)
        assertEquals(1, events.qrTaps.size)

        // Move the box away, then tap where the code is: the highlight is
        // hidden under an ordinary selection, so the tap must not open it.
        stroke(500f to 500f, 500f to 900f, 500f to 1500f)
        tap(310f, 310f)

        assertEquals(1, events.qrTaps.size)
    }

    // --- Helpers ------------------------------------------------------------

    /** A rubber-banded box under twice the corner-handle reach per side. */
    private fun selectSmallBox(): Rect {
        onMain { view.freeform = false }
        val side = dp(40f).toFloat()
        stroke(500f to 1000f, 500f + side / 2 to 1000f + side / 2, 500f + side to 1000f + side)
        onMain { view.freeform = true }
        assertTrue(events.hasSelection)
        return cropRect()!!
    }

    private fun relayout(width: Int, height: Int) = onMain {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    private fun showQr(bounds: Rect): QrCode {
        val code = QrCode("https://example.com/", bounds)
        onMain { view.setQrCodes(listOf(code)) }
        return code
    }

    private fun cropRect(): Rect? {
        var rect: Rect? = null
        onMain { rect = view.getCropRect() }
        return rect
    }

    /** One touch stream: DOWN at the first point, MOVE through the rest, UP
     *  at the last. */
    private fun stroke(vararg points: Pair<Float, Float>) = onMain {
        val downTime = clock
        send(downTime, MotionEvent.ACTION_DOWN, points.first())
        for (point in points.drop(1)) {
            clock += 10
            send(downTime, MotionEvent.ACTION_MOVE, point)
        }
        clock += 10
        send(downTime, MotionEvent.ACTION_UP, points.last())
        // Far enough apart that the next stream is never a double-tap.
        clock += 1000
    }

    private fun tap(x: Float, y: Float) = stroke(x to y)

    /** Like [stroke], but the system takes the touch away at the end. */
    private fun cancelledStroke(vararg points: Pair<Float, Float>) = onMain {
        val downTime = clock
        send(downTime, MotionEvent.ACTION_DOWN, points.first())
        for (point in points.drop(1)) {
            clock += 10
            send(downTime, MotionEvent.ACTION_MOVE, point)
        }
        clock += 10
        send(downTime, MotionEvent.ACTION_CANCEL, points.last())
        clock += 1000
    }

    /** A tap at [first], then — inside the double-tap window — a second
     *  touch stream through the points [second] returns. */
    private fun tapThenQuickly(
        first: Pair<Float, Float>,
        second: () -> List<Pair<Float, Float>>
    ) = onMain {
        val firstDown = clock
        send(firstDown, MotionEvent.ACTION_DOWN, first)
        clock += 50
        send(firstDown, MotionEvent.ACTION_UP, first)
        clock += 100

        val points = second()
        val secondDown = clock
        send(secondDown, MotionEvent.ACTION_DOWN, points.first())
        for (point in points.drop(1)) {
            clock += 10
            send(secondDown, MotionEvent.ACTION_MOVE, point)
        }
        clock += 10
        send(secondDown, MotionEvent.ACTION_UP, points.last())
        clock += 1000
    }

    private fun doubleTap(x: Float, y: Float) = quickTaps(2, x, y)

    /** [count] taps in a row, each within the double-tap window of the last. */
    private fun quickTaps(count: Int, x: Float, y: Float) = onMain {
        repeat(count) {
            val downTime = clock
            send(downTime, MotionEvent.ACTION_DOWN, x to y)
            clock += 50
            send(downTime, MotionEvent.ACTION_UP, x to y)
            clock += 100
        }
        clock += 1000
    }

    /**
     * In real time: a tap at [first], then at once a second touch that goes
     * down at [secondAt] and has dragged to [dragTo] — and is left held
     * there. Returns that touch's down-time, for the caller to continue the
     * stream after waiting.
     */
    private fun tapThenHoldDragging(
        first: Pair<Float, Float>,
        secondAt: Pair<Float, Float>,
        dragTo: Pair<Float, Float>
    ): Long {
        var secondDown = 0L
        onMain {
            val firstDown = SystemClock.uptimeMillis()
            clock = firstDown
            send(firstDown, MotionEvent.ACTION_DOWN, first)
            clock = firstDown + 50
            send(firstDown, MotionEvent.ACTION_UP, first)
            secondDown = firstDown + 150
            clock = secondDown
            send(secondDown, MotionEvent.ACTION_DOWN, secondAt)
            // Fine-grained, so the drag is recognized close to where it began.
            val steps = 12
            for (i in 1..steps) {
                clock += 5
                send(
                    secondDown, MotionEvent.ACTION_MOVE,
                    secondAt.first + (dragTo.first - secondAt.first) * i / steps to
                        secondAt.second + (dragTo.second - secondAt.second) * i / steps
                )
            }
        }
        return secondDown
    }

    /** A raw multi-finger event: [fingers] are (pointer id → position), in
     *  pointer-index order; [actionIndex] is the index the action is about. */
    private fun sendFingers(
        downTime: Long,
        action: Int,
        actionIndex: Int,
        vararg fingers: Pair<Int, Pair<Float, Float>>
    ) {
        val properties = Array(fingers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = fingers[i].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(fingers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = fingers[i].second.first
                y = fingers[i].second.second
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, clock,
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            fingers.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun send(downTime: Long, action: Int, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(downTime, clock, action, point.first, point.second, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun assertRectNear(expected: Rect, actual: Rect?, tolerance: Int = 2) {
        assertNotNull("expected a crop near $expected", actual)
        val a = actual!!
        val near = kotlin.math.abs(a.left - expected.left) <= tolerance &&
            kotlin.math.abs(a.top - expected.top) <= tolerance &&
            kotlin.math.abs(a.right - expected.right) <= tolerance &&
            kotlin.math.abs(a.bottom - expected.bottom) <= tolerance
        assertTrue("expected a crop near $expected but was $a", near)
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
    }
}
