package eu.woshicado.circletoshare

import android.app.Instrumentation
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen's sections: the setup steps that fold away, the rows
 *  that come and go with their switch, and the way to the scrollshot page. */
@RunWith(AndroidJUnit4::class)
class MainScreenSectionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private var qrWas = true
    private var buttonsWere: Boolean? = null
    private var triggersWere = true

    @Before
    fun remember() {
        qrWas = Prefs.isQrDetectEnabled(context)
        // (Stored or not: one of the tests is about what holds when not.)
        buttonsWere = prefs().takeIf { it.contains("capture_more") }?.getBoolean("capture_more", false)
        triggersWere = Prefs.isTriggersExpanded(context)
    }

    private fun prefs() = context.getSharedPreferences("cts", Context.MODE_PRIVATE)

    @After
    fun restore() {
        Prefs.setQrDetectEnabled(context, qrWas)
        Prefs.setTriggersExpanded(context, triggersWere)
        prefs().edit().apply {
            buttonsWere?.let { putBoolean("capture_more", it) } ?: remove("capture_more")
        }.commit()
    }

    private fun MainActivity.shows(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun theSetupSteps_showWhileThereIsSomethingToDo_andFoldAwayOnceDone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // (A test cannot make this app the assistant: the screen is
                // told what it would find.)
                activity.renderSetup(isAssistant = false, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                assertEquals(
                    "✓ Assistant · ✗ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                assertEquals(false, activity.shows(R.id.setup_content))
                assertEquals(
                    "✓ Assistant · ✓ Fallback",
                    activity.findViewById<TextView>(R.id.setup_summary).text.toString()
                )
                // The header is still there, to open them with.
                assertEquals(true, activity.shows(R.id.setup_header))
            }
        }
    }

    @Test
    fun aTapOnTheSetupHeader_opensAndClosesTheSteps_untilWhatIsDoneChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val header = activity.findViewById<View>(R.id.setup_header)
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                header.performClick()
                assertEquals(true, activity.shows(R.id.setup_content))
                // Looked at again (every return to the screen does): as left.
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                assertEquals(true, activity.shows(R.id.setup_content))
                header.performClick()
                assertEquals(false, activity.shows(R.id.setup_content))

                // Folded away by hand while there was something to do, and
                // then all done and undone again: they are back.
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(true, activity.shows(R.id.setup_content))
                header.performClick()
                assertEquals(false, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = false)
                assertEquals(false, activity.shows(R.id.setup_content))
                activity.renderSetup(isAssistant = true, fallbackEnabled = true)
                activity.renderSetup(isAssistant = false, fallbackEnabled = true)
                assertEquals(true, activity.shows(R.id.setup_content))
            }
        }
    }

    @Test
    fun theStepsOpenedByHand_areStillOpenAfterARotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var openAsFound = false
            scenario.onActivity { activity ->
                openAsFound = activity.shows(R.id.setup_content)
                activity.findViewById<View>(R.id.setup_header).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(!openAsFound, activity.shows(R.id.setup_content))
            }
        }
    }

    @Test
    fun theHighlightColor_isOnlyThereWhileCodesAreLookedFor() {
        Prefs.setQrDetectEnabled(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<MaterialSwitch>(R.id.switch_qr)
                assertEquals(true, activity.shows(R.id.qr_color_row))
                switch.performClick()
                assertEquals(false, activity.shows(R.id.qr_color_row))
                assertEquals(false, Prefs.isQrDetectEnabled(context))
                switch.performClick()
                assertEquals(true, activity.shows(R.id.qr_color_row))
            }
        }
        Prefs.setQrDetectEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(false, it.shows(R.id.qr_color_row)) }
        }
    }

    @Test
    fun theScrollshotRow_isAButtonToItsPage_andASwitchBesideIt() {
        Prefs.setCaptureMoreEnabled(context, true)
        val monitor = Instrumentation.ActivityMonitor(
            ScrollshotSettingsActivity::class.java.name, null, false
        )
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // The switch switches, and goes nowhere.
                scenario.onActivity {
                    it.findViewById<MaterialSwitch>(R.id.switch_capture_more).performClick()
                }
                assertNull(monitor.waitForActivityWithTimeout(500))
                assertEquals(false, Prefs.isCaptureMoreEnabled(context))

                // The text beside it opens the page, and switches nothing.
                scenario.onActivity {
                    it.findViewById<View>(R.id.btn_scrollshot_settings).performClick()
                }
                val page = monitor.waitForActivityWithTimeout(5000)
                assertNotNull(page)
                assertEquals(false, Prefs.isCaptureMoreEnabled(context))
                instrumentation.runOnMainSync { page.finish() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun howToOpenIt_foldsOnATap_andIsAsLeftTheNextTimeTheAppIsOpened() {
        Prefs.setTriggersExpanded(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(true, activity.shows(R.id.triggers_content))
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(false, activity.shows(R.id.triggers_content))
                // Its header stays, and so does what comes after it.
                assertEquals(true, activity.shows(R.id.triggers_header))
                assertEquals(true, activity.shows(R.id.switch_freeform))
            }
        }
        assertEquals(false, Prefs.isTriggersExpanded(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(false, activity.shows(R.id.triggers_content))
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(true, activity.shows(R.id.triggers_content))
            }
        }
        assertEquals(true, Prefs.isTriggersExpanded(context))
    }

    @Test
    fun foldedAway_howToOpenIt_saysWhetherTheFloatingButtonIsOn() {
        Prefs.setTriggersExpanded(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val summary = activity.findViewById<TextView>(R.id.triggers_summary)
                fun says(text: Int) = assertEquals(activity.getString(text), summary.text.toString())
                // Open, the switch itself is there to see.
                assertEquals(View.INVISIBLE, summary.visibility)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(View.VISIBLE, summary.visibility)
                // What the switch under it says, in words...
                val switchedOn = activity.findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked
                says(
                    if (switchedOn) R.string.triggers_summary_bubble_on
                    else R.string.triggers_summary_bubble_off
                )
                // ...either way. (Told which: the setting itself is the
                // device's, with a button on its screen that goes by it.)
                activity.renderTriggersSummary(bubbleOn = true)
                says(R.string.triggers_summary_bubble_on)
                activity.renderTriggersSummary(bubbleOn = false)
                says(R.string.triggers_summary_bubble_off)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(View.INVISIBLE, summary.visibility)
            }
        }
    }

    @Test
    fun howToOpenIt_isOpen_whereNothingIsStored() {
        prefs().edit().remove("triggers_expanded").commit()
        assertEquals(true, Prefs.isTriggersExpanded(context))
    }

    @Test
    fun scrollshotButtons_areOff_whereNothingIsStored() {
        prefs().edit().remove("capture_more").commit()
        assertEquals(false, Prefs.isCaptureMoreEnabled(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals(false, it.findViewById<MaterialSwitch>(R.id.switch_capture_more).isChecked)
            }
        }
    }

    private fun ActivityScenario<MainActivity>.notice(): ExperimentalNoticeDialog? {
        var notice: ExperimentalNoticeDialog? = null
        instrumentation.waitForIdleSync()
        onActivity {
            it.supportFragmentManager.executePendingTransactions()
            notice = it.supportFragmentManager.fragments
                .filterIsInstance<ExperimentalNoticeDialog>().firstOrNull()
        }
        return notice
    }

    @Test
    fun switchingAnExperimentalFeatureOn_saysWhatThatMeans_andWhereToReport() {
        Prefs.setCaptureMoreEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNull(scenario.notice())
            scenario.onActivity {
                // (A test cannot have the accessibility service running.)
                it.fallbackCheck = { true }
                val switch = it.findViewById<MaterialSwitch>(R.id.switch_capture_more)
                switch.performClick()
                assertEquals(true, switch.isChecked)
                // Off and on again in the same breath finds the first notice.
                switch.performClick()
                switch.performClick()
            }
            // On straight away: the notice asks nothing.
            assertEquals(true, Prefs.isCaptureMoreEnabled(context))
            val notice = scenario.notice()
            assertNotNull(notice)
            scenario.onActivity { activity ->
                val message = notice!!.dialog!!.findViewById<TextView>(android.R.id.message)
                val text = message.text as android.text.Spanned
                assertEquals(true, text.contains("\"" + activity.getString(R.string.switch_scrollshot) + "\""))
                // (Paragraphs, and a list: not one run of text.)
                assertEquals(true, text.contains("\n\nSo that") && text.contains("\n• "))
                assertEquals(true, text.contains("Android " + android.os.Build.VERSION.RELEASE))
                // Two links, on the address and on the page, that a tap opens.
                val links = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
                assertEquals(
                    listOf(
                        activity.getString(R.string.feedback_email),
                        "github.com/woshicado/circle-to-share/issues"
                    ),
                    links.map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }
                )
                assertEquals(true, message.movementMethod is android.text.method.LinkMovementMethod)
            }

            // Still there after a rotation, and only once.
            scenario.recreate()
            assertNotNull(scenario.notice())
            scenario.onActivity { activity ->
                assertEquals(1, activity.supportFragmentManager.fragments.count { it is ExperimentalNoticeDialog })
                activity.supportFragmentManager.fragments
                    .filterIsInstance<ExperimentalNoticeDialog>().single().dismiss()
            }
        }
    }

    @Test
    fun theNotice_isOnlyForSwitchingOn_notForBeingOn_norForSwitchingOff() {
        Prefs.setCaptureMoreEnabled(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNull(scenario.notice())
            // (A rotation sets the switch as well, to where it was.)
            scenario.recreate()
            assertNull(scenario.notice())
            scenario.onActivity {
                it.findViewById<MaterialSwitch>(R.id.switch_capture_more).performClick()
            }
            assertEquals(false, Prefs.isCaptureMoreEnabled(context))
            assertNull(scenario.notice())
        }
    }

    @Test
    fun withoutTheAccessibilityService_theButtonsCannotBeSwitchedOn_andNothingIsSaidOfReports() {
        Prefs.setCaptureMoreEnabled(context, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fallbackCheck = { false }
                val switch = activity.findViewById<MaterialSwitch>(R.id.switch_capture_more)
                switch.performClick()
                assertEquals(false, switch.isChecked)
            }
            assertEquals(false, Prefs.isCaptureMoreEnabled(context))
            assertNull(scenario.notice())
        }
    }

    @Test
    fun aTapOnHowToOpenIt_goesByWhatIsShown_notByWhatIsStored() {
        Prefs.setTriggersExpanded(context, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Stored otherwise behind the screen's back (a second window).
                Prefs.setTriggersExpanded(context, false)
                activity.findViewById<View>(R.id.triggers_header).performClick()
                assertEquals(false, activity.shows(R.id.triggers_content))
                assertEquals(false, Prefs.isTriggersExpanded(context))
            }
        }
    }
}
