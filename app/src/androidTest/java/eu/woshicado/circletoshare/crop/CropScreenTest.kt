package eu.woshicado.circletoshare.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The crop overlay as a whole: the chrome around [CropOverlayView] must not
 *  get between the finger and the crop. */
@RunWith(AndroidJUnit4::class)
class CropScreenTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var screen: CropScreen

    private var qrDetectionWasOn = true

    @Before
    fun setUp() = onMain {
        // Off for these tests: setBitmap would otherwise start a real scan of
        // the blank bitmap on a worker thread, whose (empty) result arrives
        // whenever it arrives and replaces any codes a test has put in.
        qrDetectionWasOn = Prefs.isQrDetectEnabled(context)
        Prefs.setQrDetectEnabled(context, false)
        screen = CropScreen(context)
        // A size no real capture has, so no remembered crop can match it and
        // the hint is the plain, non-interactive one.
        screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        layOut()
    }

    /** Measure and lay the screen out. It isn't attached to a window here, so
     *  nothing does this by itself when a child appears or changes size —
     *  call it (on the main thread) after anything that should move views. */
    private fun layOut(width: Int = WIDTH) {
        screen.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, HEIGHT)
    }

    @After
    fun tearDown() {
        Prefs.setQrDetectEnabled(context, qrDetectionWasOn)
        // That write is asynchronous, and the test process is killed when the
        // run ends: flush, so the restored setting is what stays on the
        // device. ("cts" is the app's preference file — see Prefs.)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().commit()
    }

    @Test
    fun plainHintIsNotClickable() {
        val hint = findText(screen, context.getString(R.string.crop_hint_freeform))
            ?: findText(screen, context.getString(R.string.crop_hint))
        assertNotNull("the hint should be showing", hint)
        assertFalse("a hint with nothing to do must let touches through", hint!!.isClickable)
    }

    @Test
    fun aCropCanStartOnTheHint() {
        val hint = findText(screen, context.getString(R.string.crop_hint_freeform))
            ?: findText(screen, context.getString(R.string.crop_hint))
        assertNotNull(hint)
        val startX = (hint!!.left + hint.right) / 2f
        val startY = (hint.top + hint.bottom) / 2f
        val share = findText(screen, context.getString(R.string.action_share_full))
        assertNotNull("with nothing selected the button offers the full screen", share)

        // A stroke that begins on the hint and loops through the screen.
        onMain {
            val downTime = SystemClock.uptimeMillis()
            var time = downTime
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
                screen.dispatchTouchEvent(event)
                event.recycle()
                time += 10
            }
            send(MotionEvent.ACTION_DOWN, startX, startY)
            send(MotionEvent.ACTION_MOVE, startX - 200f, startY + 300f)
            send(MotionEvent.ACTION_MOVE, startX, startY + 700f)
            send(MotionEvent.ACTION_MOVE, startX + 200f, startY + 300f)
            send(MotionEvent.ACTION_UP, startX + 200f, startY + 300f)
        }

        // The crop view got the stroke and made a selection: the button now
        // shares the selection, and the hint has stepped aside.
        assertEquals(context.getString(R.string.action_share), share!!.text.toString())
        assertEquals(View.GONE, hint.visibility)
    }

    @Test
    fun anOpenThatFails_saysSoInThePanelAndKeepsTheToolOpen() {
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(share: Boolean, rect: Rect?) = Unit
                override fun onCancel() {
                    cancelled++
                }
            }
        }
        // A link in a scheme nothing on the device handles.
        val code = QrCode("circletoshare-test-nohandler://x", Rect(300, 600, 700, 1000))
        val cropView = findCropView(screen)!!
        onMain { cropView.setQrCodes(listOf(code)) }

        val open = findText(screen, context.getString(R.string.qr_open))!!
        val error = findText(screen, context.getString(R.string.qr_open_failed))!!
        // The panel is what the error line sits in; a line that is "visible"
        // inside a hidden panel shows nothing.
        val panel = error.parent as View
        assertEquals("the panel is closed until a code is tapped", View.GONE, panel.visibility)

        tapOn(500f, 800f)
        onMain { layOut() }

        assertEquals("tapping the code opens its panel", View.VISIBLE, panel.visibility)
        assertEquals("Open is offered for a link", View.VISIBLE, open.visibility)
        assertEquals("no error before trying", View.GONE, error.visibility)

        onMain {
            open.performClick()
            layOut()
        }

        assertEquals("the panel stays open", View.VISIBLE, panel.visibility)
        assertEquals("the failure must be visible in it", View.VISIBLE, error.visibility)
        assertEquals("Open would only fail again", View.GONE, open.visibility)
        assertEquals("a failed open must not close the tool", 0, cancelled)

        // A touch elsewhere closes the panel; tapping the code again starts
        // afresh. ("Elsewhere" is the strip beside the panel: it keeps a
        // margin from the screen's edge however wide the text makes it.)
        val beside = Rect().also { panel.getHitRect(it) }
        assertTrue("the panel should be laid out", beside.width() > 0 && beside.height() > 0)
        assertTrue("the panel should leave a margin at the edge", beside.left > 2)
        // A touch on the panel itself must not close it…
        tapOn(beside.exactCenterX(), beside.top + 4f)
        assertEquals(View.VISIBLE, panel.visibility)
        // …one beside it does.
        tapOn(beside.left / 2f, beside.exactCenterY())
        assertEquals(View.GONE, panel.visibility)
        tapOn(500f, 800f)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, error.visibility)
        assertEquals(View.VISIBLE, open.visibility)
    }

    @Test
    fun backReachesTheHostEvenWithFocusOnAButton() {
        var backs = 0
        onMain { screen.onBackKey = { backs++ } }
        val cancel = findText(screen, context.getString(R.string.action_cancel))
        assertNotNull(cancel)
        onMain {
            // Keyboard focus on a pill: from here a key listener on the
            // screen itself would never be consulted.
            cancel!!.isFocusableInTouchMode = true
            cancel.requestFocus()
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        assertEquals("one Back press, one callback", 1, backs)
    }

    @Test
    fun withoutABackHandler_backIsLeftToTheWindow() {
        var consumed = true
        onMain {
            consumed = screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) or
                screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        // The assistant session's dialog handles Back itself; the screen
        // must not swallow it.
        assertFalse(consumed)
    }

    @Test
    fun buttonsThatFitSideBySide_areARowWithTheMainActionLast() {
        val (cancel, copy, share) = barButtons()

        assertEquals("one row", cancel.top, copy.top)
        assertEquals("one row", copy.top, share.top)
        assertTrue(cancel.right <= copy.left && copy.right <= share.left)
        for (button in listOf(cancel, copy, share)) assertEquals(1, button.lineCount)
    }

    @Test
    fun buttonsThatDoNotFitSideBySide_stackWithTheMainActionFirst() {
        val (cancel, copy, share) = barButtons()
        // Too narrow for the three in a row, wide enough for the widest alone
        // — what a small screen at a large font size comes to.
        val bar = share.parent as View
        val narrow = share.width + (cancel.width + copy.width) / 2
        assertTrue(narrow < bar.width)

        onMain { layOut(narrow) }

        assertTrue("main action on top", share.bottom <= copy.top)
        assertTrue(copy.bottom <= cancel.top)
        for (button in listOf(cancel, copy, share)) {
            assertEquals("no label is broken across lines", 1, button.lineCount)
            assertEquals("equally wide", share.width, button.width)
            assertTrue("within the screen", bar.left + button.left >= 0 && bar.left + button.right <= narrow)
        }

        // Its label getting shorter (a selection appearing) changes nothing…
        onMain {
            share.text = context.getString(R.string.action_share)
            layOut(narrow)
        }
        assertTrue("still stacked", share.bottom <= copy.top)

        // …and with room again it is a row again, in its usual order.
        onMain { layOut() }
        assertEquals(cancel.top, share.top)
        assertTrue(cancel.right <= copy.left && copy.right <= share.left)
    }

    @Test
    fun theQrPanelSitsAboveTheButtons_howeverTallTheyAre() {
        val (cancel, _, share) = barButtons()
        val bar = share.parent as View
        val panel = findText(screen, context.getString(R.string.qr_open_failed))!!.parent as View
        val cropView = findCropView(screen)!!
        onMain { cropView.setQrCodes(listOf(QrCode("https://example.com/", Rect(300, 600, 700, 1000)))) }

        tapOn(500f, 800f)
        onMain { layOut() }
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue("above the row", panel.bottom < bar.top)

        val narrow = share.width + cancel.width / 2
        onMain { layOut(narrow) }
        assertTrue("the buttons are stacked", share.bottom <= cancel.top)
        assertTrue("above the stack", panel.bottom < bar.top)
        assertTrue("and on screen", panel.top >= 0)
    }

    @Test
    fun aMessageInPlaceOfTheTool_closesWhenClicked() {
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(share: Boolean, rect: Rect?) = Unit
                override fun onCancel() {
                    cancelled++
                }
            }
            screen.showMessage("Nothing to crop")
            layOut()
        }

        val message = findText(screen, "Nothing to crop")
        assertNotNull(message)
        assertEquals(View.VISIBLE, message!!.visibility)
        assertEquals("no tool behind it", View.GONE, findCropView(screen)!!.visibility)
        // (setUp had put a picture up, on its black backing.)
        assertNull("nor a black screen where the picture was", screen.background)
        assertEquals(View.GONE, (barButtons().first().parent as View).visibility)

        // (The click itself, not a touch: a view outside any window never
        // gets to run the click it posts when a finger comes up.)
        assertTrue(screen.isClickable)
        onMain { screen.performClick() }
        assertEquals(1, cancelled)

        // A capture arriving turns it back into the tool: no click to take.
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertFalse(screen.isClickable)
        assertFalse(screen.hasOnClickListeners())
        assertEquals(View.VISIBLE, findCropView(screen)!!.visibility)
    }

    /** Cancel, Copy and the share button of the main bar. */
    private fun barButtons(): List<TextView> {
        val share = findText(screen, context.getString(R.string.action_share_full))!!
        val bar = share.parent as ViewGroup
        fun inBar(label: Int): TextView =
            (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
                .first { it.text.toString() == context.getString(label) }
        return listOf(inBar(R.string.action_cancel), inBar(R.string.action_copy), share)
    }

    private fun tapOn(x: Float, y: Float) = onMain {
        val downTime = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 50)) {
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
            screen.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun findCropView(root: View): CropOverlayView? {
        if (root is CropOverlayView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findCropView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val WIDTH = 1081
        const val HEIGHT = 2399
    }
}
