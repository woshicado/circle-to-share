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
import eu.woshicado.circletoshare.share.Delivery
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
    // The remembered crop as found (its six keys), to put back.
    private var lastCropWas: Map<String, Int> = emptyMap()

    @Before
    fun setUp() = onMain {
        // Off for these tests: setBitmap would otherwise start a real scan of
        // the blank bitmap on a worker thread, whose (empty) result arrives
        // whenever it arrives and replaces any codes a test has put in.
        qrDetectionWasOn = Prefs.isQrDetectEnabled(context)
        Prefs.setQrDetectEnabled(context, false)
        lastCropWas = context.getSharedPreferences("cts", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("last_crop_") }.mapValues { it.value as Int }
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
        // (A test that started a sideways run remembered its selection as
        // the last crop: back to what was remembered before.)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().apply {
            for (key in listOf("last_crop_l", "last_crop_t", "last_crop_r", "last_crop_b", "last_crop_w", "last_crop_h")) {
                lastCropWas[key]?.let { putInt(key, it) } ?: remove(key)
            }
        }.apply()
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
    fun aSelectionTurnsCancelIntoSave() {
        val delivered = mutableListOf<Delivery>()
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) {
                    delivered += action
                }
                override fun onCancel() {
                    cancelled++
                }
            }
        }
        val (dismiss, _, _) = barButtons()

        onMain { findCropView(screen)!!.applyCropRect(Rect(100, 200, 600, 900)) }
        assertEquals(context.getString(R.string.action_save), dismiss.text.toString())
        // Delivering remembers the crop for this capture size, which would
        // turn the other tests' hint into the restore offer: put back what
        // was remembered before. ("cts" is the app's preference file.)
        val prefs = context.getSharedPreferences("cts", Context.MODE_PRIVATE)
        val remembered = prefs.all.filterKeys { it.startsWith("last_crop_") }
        try {
            onMain { dismiss.performClick() }
        } finally {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("last_crop_") }.forEach { remove(it) }
                remembered.forEach { (key, value) -> putInt(key, value as Int) }
            }.commit()
        }
        assertEquals("Save saves", listOf(Delivery.SAVE), delivered)
        assertEquals("and doesn't cancel", 0, cancelled)

        // A new capture starts without a selection: Cancel again.
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            dismiss.performClick()
        }
        assertEquals(context.getString(R.string.action_cancel), dismiss.text.toString())
        assertEquals(1, cancelled)
        assertEquals(1, delivered.size)
    }

    @Test
    fun anOpenThatFails_saysSoInThePanelAndKeepsTheToolOpen() {
        var cancelled = 0
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
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
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
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

    private fun moreButton(label: Int): TextView = findText(screen, context.getString(label))!!

    /** Whether [button] is on screen: itself and the row it is in. */
    private fun shows(button: View): Boolean =
        button.visibility == View.VISIBLE && (button.parent as View).visibility == View.VISIBLE

    @Test
    fun captureMoreIsOnlyThereForAHostThatCanDoIt() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        assertFalse(shows(above))
        assertFalse(shows(below))

        val asked = ArrayList<CropScreen.More>()
        onMain {
            screen.onCaptureMore = { asked += it }
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))
        // A row of their own above the bar, clear of the bar's buttons.
        val bar = barButtons().first().parent as View
        val row = above.parent as View
        assertTrue("the buttons overlap the bar", row.bottom <= bar.top)
        onMain {
            above.performClick()
            below.performClick()
        }
        assertEquals(listOf(CropScreen.More.ABOVE, CropScreen.More.BELOW), asked)

        onMain {
            screen.onCaptureMore = null
            layOut()
        }
        assertFalse(shows(above))
        assertFalse(shows(below))
    }

    @Test
    fun onlyTheWaysThereAreToGoAreOffered() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        val bothLeft = below.left
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.BELOW)
            layOut()
        }
        assertFalse(shows(above))
        assertTrue(shows(below))
        // The one left stays where it was: it is tapped again and again.
        assertEquals(bothLeft, below.left)
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.ABOVE)
            layOut()
        }
        assertTrue(shows(above))
        assertFalse(shows(below))
        onMain {
            screen.captureMoreChoices = emptySet()
            layOut()
        }
        assertFalse(shows(above))
        assertFalse(shows(below))
    }

    /** The "↔" button, found by what it says of itself (it has no label). */
    private fun sidewaysButton(): View = findByDescription(screen, context.getString(R.string.action_capture_sideways))!!

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findByDescription(root.getChildAt(i), description)?.let { return it }
        }
        return null
    }

    @Test
    fun sidewaysIsOffered_betweenTheTwo_whileNoRunHasBegun() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val sideways = sidewaysButton()
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        // Not without a host for it.
        assertFalse(shows(sideways))
        var asked = 0
        onMain {
            screen.onCaptureSideways = { asked++ }
            layOut()
        }
        assertTrue(shows(sideways))
        assertTrue("between the two", above.right <= sideways.left && sideways.right <= below.left)
        onMain { sideways.performClick() }
        assertEquals(1, asked)
        // Gone once the picture has grown one way...
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.BELOW)
            layOut()
        }
        assertFalse(shows(sideways))
        // ...and in a sideways run, which has buttons of its own.
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.LEFT, CropScreen.More.RIGHT)
            layOut()
        }
        assertFalse(shows(sideways))
        assertFalse(shows(above))
        assertTrue(shows(moreButton(R.string.action_capture_more_left)))
        assertTrue(shows(moreButton(R.string.action_capture_more_right)))
    }

    @Test
    fun aCropOfAScrollshot_isNotRemembered() {
        // The crop of the screen before stays the one to restore.
        Prefs.saveLastCrop(context, Rect(10, 20, 300, 400), WIDTH, HEIGHT)
        val (dismiss, _, _) = barButtons()
        onMain {
            screen.callbacks = object : CropScreen.Callbacks {
                override fun onDeliver(action: Delivery, rect: Rect?) = Unit
                override fun onCancel() = Unit
            }
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT * 3, Bitmap.Config.ARGB_8888), scrollshot = true)
            findCropView(screen)!!.applyCropRect(Rect(100, 2000, 600, 4000))
            layOut()
            dismiss.performClick() // Save
        }
        assertEquals(Rect(10, 20, 300, 400), Prefs.getLastCropFor(context, WIDTH, HEIGHT))
        assertNull(Prefs.getLastCropFor(context, WIDTH, HEIGHT * 3))
    }

    @Test
    fun nothingScrollingSideways_stillOffersTheLastCrop() {
        Prefs.saveLastCrop(context, Rect(10, 20, 300, 400), WIDTH, HEIGHT)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertNotNull(findText(screen, context.getString(R.string.crop_hint_restore)))
        onMain {
            screen.pickArea(emptyList())
            layOut()
        }
        val hint = findText(screen, context.getString(R.string.capture_sideways_none_restore))
        assertNotNull("the hint says both: no row, and the crop to restore", hint)
        assertTrue(hint!!.isClickable)
        onMain { hint.performClick() }
        assertEquals(Rect(10, 20, 300, 400), crop.getCropRect())
    }

    @Test
    fun nothingScrollingSideways_saysSo_andOnlyTakesTheSidewaysButtonAway() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val sideways = sidewaysButton()
        val left = moreButton(R.string.action_capture_more_left)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.onSidewaysRun = { _, _ -> }
            layOut()
        }
        assertTrue(shows(sideways))
        onMain {
            screen.pickArea(emptyList())
            layOut()
        }
        // The same view, told: the vertical buttons stay, the "↔" goes.
        assertFalse(screen.pickingArea)
        assertTrue(shows(above))
        assertTrue(shows(below))
        assertFalse(shows(sideways))
        assertEquals(
            context.getString(R.string.capture_sideways_none),
            findText(screen, context.getString(R.string.capture_sideways_none))?.text?.toString()
        )
        // A box drawn by hand offers the sideways run as ever...
        onMain {
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertTrue(shows(left))
        // ...and cleared, the word stays until the next capture.
        onMain {
            crop.clear()
            layOut()
        }
        assertFalse(shows(sideways))
        assertNotNull(findText(screen, context.getString(R.string.capture_sideways_none)))
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(sideways))
        assertNull(findText(screen, context.getString(R.string.capture_sideways_none)))
    }

    @Test
    fun choosingARow_byTapOrByBox_startsASidewaysRunFromTheSelection() {
        val above = moreButton(R.string.action_capture_more_above)
        val left = moreButton(R.string.action_capture_more_left)
        val right = moreButton(R.string.action_capture_more_right)
        val crop = findCropView(screen)!!
        var started: Pair<Rect, CropScreen.More>? = null
        onMain {
            screen.onCaptureMore = {}
            screen.onCaptureSideways = {}
            screen.onSidewaysRun = { area, more -> started = area to more }
            layOut()
        }
        val areas = listOf(Rect(0, 200, WIDTH, 500), Rect(0, 1200, WIDTH, 1500))
        onMain {
            screen.pickArea(areas)
            layOut()
        }
        // Choosing: the vertical buttons are away, and so are the sideways
        // ones until something is selected; the hint says what to do.
        assertTrue(screen.pickingArea)
        assertFalse(shows(above))
        assertFalse(shows(right))
        assertEquals(
            context.getString(R.string.capture_sideways_pick),
            findText(screen, context.getString(R.string.capture_sideways_pick))?.text?.toString()
        )
        // A tap on the second outline makes it the selection...
        val where = crop.mapToView(areas[1])
        onMain {
            val picker = findByDescriptionLess(screen, "AreaPickerView")!!
            val x = where.centerX()
            val y = where.centerY()
            picker.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
            picker.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, x, y, 0))
            layOut()
        }
        assertEquals(areas[1], crop.getCropRect())
        assertTrue(shows(left))
        assertTrue(shows(right))
        assertEquals(
            context.getString(R.string.capture_sideways_go),
            findText(screen, context.getString(R.string.capture_sideways_go))?.text?.toString()
        )
        // ...and "More right" starts the run of it.
        onMain { right.performClick() }
        assertEquals(areas[1] to CropScreen.More.RIGHT, started)
        assertFalse(screen.pickingArea)

        // A box of the user's own does as well — with no outlines asked for:
        // every selection offers the two, while no run has begun.
        onMain {
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertFalse(screen.pickingArea)
        assertTrue(shows(left))
        assertFalse(shows(above))
        onMain { left.performClick() }
        assertEquals(Rect(100, 800, 900, 1100) to CropScreen.More.LEFT, started)
        // And is remembered, as a shared crop is, for the next capture of
        // the same screen.
        assertEquals(Rect(100, 800, 900, 1100), Prefs.getLastCropFor(context, WIDTH, HEIGHT))
        // Not in a sideways run, whose buttons are its steps.
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.RIGHT)
            crop.applyCropRect(Rect(100, 800, 900, 1100))
            layOut()
        }
        assertFalse(shows(left))
        assertFalse(shows(right))
        onMain {
            screen.captureMoreChoices = setOf(CropScreen.More.ABOVE, CropScreen.More.BELOW)
            crop.clear()
        }

        // Back while choosing only leaves the choosing; a new capture too.
        var backs = 0
        onMain {
            screen.onBackKey = { backs++ }
            screen.pickArea(areas)
            screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        assertEquals(0, backs)
        assertFalse(screen.pickingArea)
        onMain {
            screen.pickArea(areas)
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertFalse(screen.pickingArea)
        assertTrue(shows(above))
    }

    private fun findByDescriptionLess(root: View, className: String): View? {
        if (root.javaClass.simpleName == className) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) findByDescriptionLess(root.getChildAt(i), className)?.let { return it }
        }
        return null
    }

    @Test
    fun captureMoreStepsAsideWhileSomethingIsSelected() {
        val above = moreButton(R.string.action_capture_more_above)
        val below = moreButton(R.string.action_capture_more_below)
        val crop = findCropView(screen)!!
        onMain {
            screen.onCaptureMore = {}
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))

        onMain { crop.applyCropRect(Rect(200, 600, 800, 1400)) }
        assertFalse(shows(above))
        assertFalse(shows(below))

        // A new capture starts with nothing selected.
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(above))
        assertTrue(shows(below))
    }

    @Test
    fun captureMoreComesAndGoesWithTheTool() {
        val below = moreButton(R.string.action_capture_more_below)
        onMain {
            screen.onCaptureMore = {}
            screen.showMessage("nothing to crop")
            layOut()
        }
        assertFalse(shows(below))
        onMain {
            screen.setBitmap(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
            layOut()
        }
        assertTrue(shows(below))
        onMain {
            screen.clear()
            layOut()
        }
        assertFalse(shows(below))
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
