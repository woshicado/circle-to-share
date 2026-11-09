package eu.woshicado.circletoshare

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** The "?" beside the setup steps and the card with the recording it opens. */
@RunWith(AndroidJUnit4::class)
class SetupHelpTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Whether "How to open it" is folded is stored, and these tests tap its
    // header: as found, afterwards.
    private var triggersWere = true

    @Before
    fun remember() {
        triggersWere = Prefs.isTriggersExpanded(instrumentation.targetContext)
    }

    @After
    fun restore() {
        Prefs.setTriggersExpanded(instrumentation.targetContext, triggersWere)
        instrumentation.targetContext.getSharedPreferences("cts", android.content.Context.MODE_PRIVATE)
            .edit().commit()
    }

    private class Helper(val button: Int, val title: Int, val clip: Int)

    private val helpers = listOf(
        Helper(R.id.help_assistant, R.string.help_title_assistant, R.raw.help_assistant),
        Helper(R.id.help_fallback, R.string.help_title_fallback, R.raw.help_fallback),
        Helper(R.id.help_tile, R.string.help_title_tile, R.raw.help_tile),
        Helper(R.id.help_trigger_power, R.string.help_title_trigger_power, R.raw.help_trigger_power),
        Helper(R.id.help_trigger_corner, R.string.help_title_trigger_corner, R.raw.help_trigger_corner),
        Helper(R.id.help_trigger_tile, R.string.help_title_trigger_tile, R.raw.help_trigger_tile),
        Helper(R.id.help_trigger_bubble, R.string.help_title_trigger_bubble, R.raw.help_trigger_bubble),
    )

    /** The main screen with its sections open: folded away (the setup steps
     *  where setup is done, "How to open it" where the user left it so),
     *  the "?" of each row is folded away with them. */
    private fun launchWithStepsShown(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity {
                if (it.findViewById<View>(R.id.setup_content).visibility != View.VISIBLE) {
                    it.findViewById<View>(R.id.setup_header).performClick()
                }
                if (it.findViewById<View>(R.id.triggers_content).visibility != View.VISIBLE) {
                    it.findViewById<View>(R.id.triggers_header).performClick()
                    // (Stored by that tap: back to how it was.)
                    Prefs.setTriggersExpanded(it, false)
                }
            }
            instrumentation.waitForIdleSync()
        }

    private fun ActivityScenario<MainActivity>.shownCard(): PopupWindow? {
        var card: PopupWindow? = null
        onActivity { card = it.window.decorView.getTag(R.id.anchored_card) as? PopupWindow }
        return card
    }

    private fun ActivityScenario<MainActivity>.click(button: Int) {
        onActivity { it.findViewById<View>(button).performClick() }
        instrumentation.waitForIdleSync()
    }

    /** Playback starts once the card has drawn its first frame (see
     *  SetupHelp.kt), a moment after the click: wait for it. */
    private fun PopupWindow.clipStarts(): Boolean {
        val clip = contentView.findViewById<ImageView>(R.id.help_clip).drawable as AnimatedImageDrawable
        repeat(20) {
            instrumentation.waitForIdleSync()
            if (clip.isRunning) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun eachHelper_opensItsCard_playingItsRecording() {
        launchWithStepsShown().use { scenario ->
            for (helper in helpers) {
                scenario.click(helper.button)
                val card = scenario.shownCard()
                assertNotNull(card)
                scenario.onActivity { activity ->
                    assertTrue(card!!.isShowing)
                    val content = card.contentView
                    assertEquals(
                        activity.getString(helper.title),
                        content.findViewById<TextView>(R.id.help_title).text.toString(),
                    )
                    val clip = content.findViewById<ImageView>(R.id.help_clip).drawable
                    assertTrue(clip is AnimatedImageDrawable)
                    // It fits on the screen, beside the "?".
                    assertTrue(content.height <= activity.window.decorView.height)
                }
                assertTrue(card!!.clipStarts())
                scenario.onActivity { card.dismiss() }
            }
        }
    }

    @Test
    fun back_closesTheCard_andStopsTheRecording() {
        launchWithStepsShown().use { scenario ->
            scenario.click(R.id.help_assistant)
            val card = scenario.shownCard()!!
            var clip: AnimatedImageDrawable? = null
            scenario.onActivity {
                clip = card.contentView.findViewById<ImageView>(R.id.help_clip).drawable
                    as AnimatedImageDrawable
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(card.isShowing)
                assertFalse(clip!!.isRunning)
            }
            assertNull(scenario.shownCard())
        }
    }

    @Test
    fun openingAnotherHelper_closesTheFirst() {
        launchWithStepsShown().use { scenario ->
            scenario.click(R.id.help_assistant)
            val first = scenario.shownCard()!!
            scenario.click(R.id.help_tile)
            val second = scenario.shownCard()!!
            scenario.onActivity {
                assertFalse(first.isShowing)
                assertTrue(second.isShowing)
                second.dismiss()
            }
        }
    }

    @Test
    fun recordings_areAnimations() {
        val resources = instrumentation.targetContext.resources
        for (helper in helpers) {
            val bytes = resources.openRawResource(helper.clip).use { it.readBytes() }
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            assertTrue(drawable is AnimatedImageDrawable)
        }
    }

    @Test
    fun aCard_followsItsButton_andClosesWhenThatIsGone() {
        launchWithStepsShown().use { scenario ->
            // The button on screen, with room around it to move in.
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.help_trigger_power)
                val at = IntArray(2).also { button.getLocationOnScreen(it) }
                activity.findViewById<android.widget.ScrollView>(R.id.root_scroll)
                    .scrollBy(0, at[1] - activity.window.decorView.height / 3)
            }
            instrumentation.waitForIdleSync()
            scenario.click(R.id.help_trigger_power)
            val card = scenario.shownCard()
            assertNotNull(card)

            fun top(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }[1]
            var buttonWas = 0
            var cardWas = 0
            // The sections looked at again, as on every return to the
            // screen, and found as they are: no matter to the card.
            scenario.onActivity {
                it.renderSetup(isAssistant = false, fallbackEnabled = false)
                it.renderSetup(isAssistant = false, fallbackEnabled = false)
                buttonWas = top(it.findViewById(R.id.help_trigger_power))
                cardWas = top(card!!.contentView)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(card!!.isShowing) }

            // A line above goes, and the row moves up: the card with it, by
            // as much.
            scenario.onActivity { it.findViewById<View>(R.id.status_assistant).visibility = View.GONE }
            var moved = 0
            repeat(20) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { moved = top(card!!.contentView) - cardWas }
                if (moved != 0) return@repeat
                Thread.sleep(50)
            }
            scenario.onActivity {
                assertTrue(card!!.isShowing)
                val buttonMoved = top(it.findViewById(R.id.help_trigger_power)) - buttonWas
                assertTrue("the button moved", buttonMoved < 0)
                assertEquals(buttonMoved, moved)
            }

            // Its own section folding takes the button away altogether.
            scenario.onActivity { it.findViewById<View>(R.id.triggers_header).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertFalse(card!!.isShowing) }
        }
    }
}
