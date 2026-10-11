package eu.woshicado.circletoshare

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BulletSpan
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The highlight-color field on the setup screen: what a paste turns into. */
@RunWith(AndroidJUnit4::class)
class MainActivityColorFieldTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var colorBefore: String

    @Before
    fun setUp() {
        colorBefore = Prefs.getQrColorHex(context)
        Prefs.setQrColorHex(context, START)
    }

    @After
    fun tearDown() {
        // The app's real preferences: put the color back, and make sure it
        // is on disk before the test process goes away.
        Prefs.setQrColorHex(context, colorBefore)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().commit()
    }

    /** Replace the whole field with [text] in one edit — which is what a
     *  paste over a select-all is — and return what the field then shows. */
    private fun paste(text: String): String {
        var shown = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.qr_color_input)
                input.text.replace(0, input.length(), text)
                shown = input.text.toString()
            }
        }
        return shown
    }

    @Test
    fun pastingAColorWithSpaceAroundIt_keepsAllOfIt() {
        assertEquals("#FFCA28", paste(" #FFCA28 "))
        assertEquals("#FFCA28", Prefs.getQrColorHex(context))
    }

    @Test
    fun pastingAColorWithOtherCharactersInIt_keepsAllItsDigits() {
        assertEquals("#00ff7f", paste("\"#00ff7f\";"))
        assertEquals("#00FF7F", Prefs.getQrColorHex(context))
    }

    @Test
    fun pastingSomethingThatIsNotAColor_doesNotChangeTheColor() {
        // Seven hex digits once the "x" is dropped: not a color, and not to
        // be cut down to the first six, which would be a different one.
        assertEquals("0FFCA28", paste("0xFFCA28"))
        assertEquals(START, Prefs.getQrColorHex(context))
    }

    @Test
    fun pastingMoreThanFits_isCutToLength() {
        assertEquals("#123456", paste("#123456789"))
        assertEquals("#123456", Prefs.getQrColorHex(context))
    }

    @Test
    fun digitsOfOtherScripts_areNotTakenForHexDigits() {
        // Digits to Unicode, but not to a hex code: let in, they would sit
        // in the field looking like a color that then "is not a color".
        assertEquals("", paste("\u06F1\u06F1\u06F2\u06F2\u06F3\u06F3")) // Persian ۱۱۲۲۳۳
        assertEquals("#12", paste("#\uFF11\uFF121\uFF132"))                // full-width １２ and ３
        assertEquals(START, Prefs.getQrColorHex(context))
    }

    @Test
    fun pastingStyledTextOfSeveralParagraphs_isTakenAsItsCharacters() {
        // What "Paste" brings from a web page or a formatted note: text with
        // paragraph spans (list bullets, quotes), which may only lie on
        // paragraph boundaries — and dropping the line breaks moves those.
        val styled = SpannableString("#FFCA28\n#112233\n").apply {
            setSpan(BulletSpan(), 0, 8, Spanned.SPAN_PARAGRAPH)
            setSpan(BulletSpan(), 8, 16, Spanned.SPAN_PARAGRAPH)
        }
        var shown = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.qr_color_input)
                input.text.replace(0, input.length(), styled)
                shown = input.text.toString()
            }
        }
        assertEquals("#FFCA28", shown)
        assertEquals("#FFCA28", Prefs.getQrColorHex(context))
    }

    @Test
    fun aKeyboardThatComposesItsWord_doesNotDoubleLettersWhenOneIsDropped() {
        var shown = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.qr_color_input)
                input.text.clear()
                // What an IME that underlines the word being typed sends: the
                // whole word so far, each time, to replace the last version.
                val keyboard = input.onCreateInputConnection(EditorInfo())!!
                keyboard.setComposingText("f", 1)
                keyboard.setComposingText("fg", 1)  // "g" is no hex digit
                keyboard.setComposingText("fga", 1)
                keyboard.finishComposingText()
                shown = input.text.toString()
            }
        }
        assertEquals("fa", shown)
    }

    private companion object {
        const val START = "#112233"
    }
}
