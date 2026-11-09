package eu.woshicado.circletoshare

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The scrollshot page: the general settings, and the apps with their own. */
@RunWith(AndroidJUnit4::class)
class ScrollshotSettingsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    // The one app sure to be installed, and to be opened from a launcher.
    private val app = context.packageName
    private val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()

    private var stepWas = 0
    private var handlesWere = true
    private var snapWas = false
    private var appsWere: Set<String>? = null

    @Before
    fun remember() {
        stepWas = Prefs.getCaptureMoreStep(context)
        handlesWere = Prefs.isCaptureMoreHandleRemovalEnabled(context)
        snapWas = Prefs.isSnapEnabled(context)
        // The apps that have settings of their own on this device are out
        // of the way for the test (what they are set to stays where it is).
        val prefs = context.getSharedPreferences("cts", Context.MODE_PRIVATE)
        appsWere = prefs.getStringSet("scrollshot_apps", null)?.let { HashSet(it) }
        prefs.edit().remove("scrollshot_apps").commit()
    }

    @After
    fun restore() {
        Prefs.setAppOverride(context, app, false)
        Prefs.setCaptureMoreStep(context, stepWas)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().apply {
            if (appsWere == null) remove("scrollshot_apps") else putStringSet("scrollshot_apps", appsWere)
        }.commit()
        Prefs.setCaptureMoreHandleRemovalEnabled(context, handlesWere)
        Prefs.setSnapEnabled(context, snapWas)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().commit()
    }

    private fun ActivityScenario<ScrollshotSettingsActivity>.shownApps(): List<String> {
        var shown = emptyList<String>()
        onActivity { shown = it.shownApps() }
        return shown
    }

    /** Open the apps and wait for them (they are read off the main thread). */
    private fun ActivityScenario<ScrollshotSettingsActivity>.openApps() {
        onActivity { it.findViewById<View>(R.id.apps_header).performClick() }
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (shownApps().isNotEmpty()) return
            Thread.sleep(50)
        }
    }

    /** The row of [app], brought to the top of the list by searching for it. */
    private fun ActivityScenario<ScrollshotSettingsActivity>.onRowOf(
        app: String, block: (View) -> Unit
    ) {
        onActivity { it.findViewById<EditText>(R.id.apps_search).setText(appLabel) }
        instrumentation.waitForIdleSync()
        onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.apps_list)
            val row = list.findViewHolderForAdapterPosition(1 + activity.shownApps().indexOf(app))
            block(row!!.itemView)
        }
    }

    @Test
    fun theGeneralSettings_areTheOnesStored_andStoreWhatIsSet() {
        Prefs.setCaptureMoreStep(context, 35)
        Prefs.setCaptureMoreHandleRemovalEnabled(context, true)
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(35f, activity.findViewById<Slider>(R.id.slider_capture_more_step).value)
                val handles = activity.findViewById<MaterialSwitch>(R.id.switch_capture_more_handles)
                assertTrue(handles.isChecked)
                handles.performClick()
            }
        }
        assertFalse(Prefs.isCaptureMoreHandleRemovalEnabled(context))
    }

    @Test
    fun noAppIsListed_untilTheAppsAreOpened() {
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            assertEquals(emptyList<String>(), scenario.shownApps())
            scenario.onActivity {
                assertEquals(1, it.findViewById<RecyclerView>(R.id.apps_list).adapter!!.itemCount)
            }
            scenario.openApps()
            assertTrue(app in scenario.shownApps())
            // And closed, they are off the page again.
            scenario.onActivity { it.findViewById<View>(R.id.apps_header).performClick() }
            assertEquals(emptyList<String>(), scenario.shownApps())
        }
    }

    @Test
    fun theSearch_leavesTheAppsWithItInTheirName() {
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.openApps()
            scenario.onActivity {
                it.findViewById<EditText>(R.id.apps_search).setText("no app is called this at all")
            }
            assertEquals(emptyList<String>(), scenario.shownApps())
            scenario.onActivity {
                it.findViewById<EditText>(R.id.apps_search).setText(appLabel.uppercase())
            }
            assertTrue(app in scenario.shownApps())
        }
    }

    @Test
    fun theSearchField_goesToTheTopOfTheList_whenItIsTypedIn() {
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.openApps()
            scenario.onActivity { it.findViewById<EditText>(R.id.apps_search).requestFocus() }
            repeat(3) { instrumentation.waitForIdleSync() }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.apps_list)
                val search = activity.findViewById<View>(R.id.apps_search)
                val inList = IntArray(2).also { list.getLocationOnScreen(it) }
                val onScreen = IntArray(2).also { search.getLocationOnScreen(it) }
                // (Unless the apps are too few to scroll that far.)
                if (list.canScrollVertically(1)) assertEquals(inList[1], onScreen[1])
                assertTrue(onScreen[1] >= inList[1])
            }
            // Closed, the apps take the field and its focus with them.
            scenario.onActivity { it.findViewById<View>(R.id.apps_header).performClick() }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.apps_search).hasFocus())
            }
        }
    }

    @Test
    fun switchingAnAppOn_givesItTheGeneralSettingsToChange_andOffTakesThemAway() {
        Prefs.setCaptureMoreStep(context, 30)
        Prefs.setCaptureMoreHandleRemovalEnabled(context, false)
        Prefs.setSnapEnabled(context, true)
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.openApps()
            scenario.onRowOf(app) { row ->
                val options = row.findViewById<View>(R.id.app_options)
                assertEquals(View.GONE, options.visibility)
                row.findViewById<MaterialSwitch>(R.id.app_switch).performClick()
                assertEquals(View.VISIBLE, options.visibility)
                assertEquals(30f, row.findViewById<Slider>(R.id.app_step).value)
                assertFalse(row.findViewById<MaterialSwitch>(R.id.app_handles).isChecked)
                assertTrue(row.findViewById<MaterialSwitch>(R.id.app_snap).isChecked)
                assertTrue(app in Prefs.getAppOverrides(context))

                row.findViewById<MaterialSwitch>(R.id.app_snap).performClick()
                row.findViewById<MaterialSwitch>(R.id.app_handles).performClick()
            }
            assertFalse(Prefs.isSnapEnabled(context, app))
            assertTrue(Prefs.isCaptureMoreHandleRemovalEnabled(context, app))
            // The general ones are as they were.
            assertTrue(Prefs.isSnapEnabled(context))
            assertFalse(Prefs.isCaptureMoreHandleRemovalEnabled(context))

            scenario.onRowOf(app) { row ->
                // The row itself is the switch too.
                row.findViewById<View>(R.id.app_row).performClick()
                assertEquals(View.GONE, row.findViewById<View>(R.id.app_options).visibility)
            }
            assertFalse(app in Prefs.getAppOverrides(context))
            assertTrue(Prefs.isSnapEnabled(context, app))
        }
    }

    @Test
    fun anAppWithItsOwnSettings_isListedFirst_andCounted() {
        Prefs.setAppOverride(context, app, true)
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.scrollshot_apps_count, Prefs.getAppOverrides(context).size),
                    activity.findViewById<android.widget.TextView>(R.id.apps_title).text.toString()
                )
            }
            scenario.openApps()
            assertEquals(app, scenario.shownApps().first())
        }
    }

    @Test
    fun theOpenList_isOpenAgainAfterARotation() {
        ActivityScenario.launch(ScrollshotSettingsActivity::class.java).use { scenario ->
            scenario.openApps()
            scenario.recreate()
            repeat(100) {
                instrumentation.waitForIdleSync()
                if (scenario.shownApps().isNotEmpty()) return@repeat
                Thread.sleep(50)
            }
            assertTrue(app in scenario.shownApps())
        }
    }
}
