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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** The "?" beside the setup steps and the card with the recording it opens. */
@RunWith(AndroidJUnit4::class)
class SetupHelpTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    private fun ActivityScenario<MainActivity>.shownCard(): PopupWindow? {
        var card: PopupWindow? = null
        onActivity { card = it.window.decorView.getTag(R.id.setup_help_card) as? PopupWindow }
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
}
