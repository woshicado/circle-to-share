package eu.woshicado.circletoshare

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The "Capture more" settings as the capture reads them. */
@RunWith(AndroidJUnit4::class)
class CaptureMorePrefsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var stepWas = 0
    private var sidewaysWas: Int? = null
    private fun prefs() = context.getSharedPreferences("cts", Context.MODE_PRIVATE)
    private var handlesWere = true
    private var snapWas = false
    private var appsWere: Set<String>? = null

    @Before
    fun remember() {
        stepWas = Prefs.getCaptureMoreStep(context)
        sidewaysWas = prefs().takeIf { it.contains("capture_more_step_sideways") }?.getInt("capture_more_step_sideways", 0)
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
        Prefs.setAppOverride(context, APP, false)
        Prefs.setSnapEnabled(context, snapWas)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().apply {
            if (appsWere == null) remove("scrollshot_apps") else putStringSet("scrollshot_apps", appsWere)
        }.commit()
        Prefs.setCaptureMoreStep(context, stepWas)
        prefs().edit().apply {
            sidewaysWas?.let { putInt("capture_more_step_sideways", it) } ?: remove("capture_more_step_sideways")
        }.apply()
        Prefs.setCaptureMoreHandleRemovalEnabled(context, handlesWere)
        // (The writes are asynchronous and the test process is killed when
        // the run ends: flush, so what was there is what stays.)
        context.getSharedPreferences("cts", Context.MODE_PRIVATE).edit().commit()
    }

    @Test
    fun theStepIsAlwaysOneTheSliderCanShow() {
        // The slider throws on a value out of its range or off its steps.
        for ((stored, read) in listOf(50 to 50, 30 to 30, 33 to 35, 32 to 30, 5 to 20, 0 to 20, 95 to 80, -40 to 20)) {
            Prefs.setCaptureMoreStep(context, stored)
            assertEquals("stored $stored", read, Prefs.getCaptureMoreStep(context))
        }
    }

    @Test
    fun handleRemovalCanBeSwitchedOff() {
        Prefs.setCaptureMoreHandleRemovalEnabled(context, false)
        assertFalse(Prefs.isCaptureMoreHandleRemovalEnabled(context))
        Prefs.setCaptureMoreHandleRemovalEnabled(context, true)
        assertTrue(Prefs.isCaptureMoreHandleRemovalEnabled(context))
    }

    @Test
    fun anAppsOwnSettings_startAsTheGeneralOnes_andThenGoTheirOwnWay() {
        Prefs.setCaptureMoreStep(context, 30)
        Prefs.setCaptureMoreHandleRemovalEnabled(context, false)
        Prefs.setSnapEnabled(context, true)
        Prefs.setAppOverride(context, APP, true)
        assertTrue(APP in Prefs.getAppOverrides(context))
        assertEquals(30, Prefs.getCaptureMoreStep(context, APP))
        assertFalse(Prefs.isCaptureMoreHandleRemovalEnabled(context, APP))
        assertTrue(Prefs.isSnapEnabled(context, APP))

        // The app's change only what the app gets...
        Prefs.setCaptureMoreStep(context, 70, APP)
        Prefs.setCaptureMoreHandleRemovalEnabled(context, true, APP)
        Prefs.setSnapEnabled(context, false, APP)
        assertEquals(70, Prefs.getCaptureMoreStep(context, APP))
        assertTrue(Prefs.isCaptureMoreHandleRemovalEnabled(context, APP))
        assertFalse(Prefs.isSnapEnabled(context, APP))
        assertEquals(30, Prefs.getCaptureMoreStep(context))
        assertFalse(Prefs.isCaptureMoreHandleRemovalEnabled(context))
        assertTrue(Prefs.isSnapEnabled(context))
        assertEquals(30, Prefs.getCaptureMoreStep(context, OTHER_APP))

        // ...and the general ones don't reach it any more.
        Prefs.setCaptureMoreStep(context, 40)
        assertEquals(70, Prefs.getCaptureMoreStep(context, APP))
        assertEquals(40, Prefs.getCaptureMoreStep(context, OTHER_APP))
    }

    @Test
    fun anAppWithoutSettingsOfItsOwn_getsTheGeneralOnes() {
        // Even with values left behind under its name.
        Prefs.setCaptureMoreStep(context, 70, APP)
        Prefs.setSnapEnabled(context, true, APP)
        Prefs.setCaptureMoreStep(context, 35)
        Prefs.setSnapEnabled(context, false)
        assertEquals(35, Prefs.getCaptureMoreStep(context, APP))
        assertFalse(Prefs.isSnapEnabled(context, APP))
        assertEquals(35, Prefs.getCaptureMoreStep(context, null))
    }

    @Test
    fun takingAnAppsSettingsAway_forgetsThem() {
        Prefs.setCaptureMoreStep(context, 50)
        Prefs.setAppOverride(context, APP, true)
        Prefs.setCaptureMoreStep(context, 70, APP)
        Prefs.setAppOverride(context, APP, false)
        assertFalse(APP in Prefs.getAppOverrides(context))
        assertEquals(50, Prefs.getCaptureMoreStep(context, APP))

        // Given again, they start from the general ones, not where they were.
        Prefs.setCaptureMoreStep(context, 25)
        Prefs.setAppOverride(context, APP, true)
        assertEquals(25, Prefs.getCaptureMoreStep(context, APP))
    }

    @Test
    fun whichAppACaptureIsOf_onlyMattersForSnapping_whereAnAppsOwnSettingDiffers() {
        Prefs.setSnapEnabled(context, false)
        assertFalse(Prefs.snapDependsOnApp(context))
        Prefs.setAppOverride(context, APP, true)
        assertFalse(Prefs.snapDependsOnApp(context))
        Prefs.setSnapEnabled(context, true, APP)
        assertTrue(Prefs.snapDependsOnApp(context))
        Prefs.setSnapEnabled(context, true)
        assertFalse(Prefs.snapDependsOnApp(context))
    }

    @Test
    fun theSidewaysStep_hasItsOwnDefault_andGoesWithAnAppsOwnSettings() {
        assertEquals(Prefs.CAPTURE_MORE_STEP_SIDEWAYS_DEFAULT, Prefs.getCaptureMoreStepSideways(context))
        Prefs.setCaptureMoreStepSideways(context, 60)
        Prefs.setAppOverride(context, APP, true)
        assertEquals(60, Prefs.getCaptureMoreStepSideways(context, APP))
        Prefs.setCaptureMoreStepSideways(context, 80, APP)
        assertEquals(80, Prefs.getCaptureMoreStepSideways(context, APP))
        assertEquals(60, Prefs.getCaptureMoreStepSideways(context))
        Prefs.setAppOverride(context, APP, false)
        assertEquals(60, Prefs.getCaptureMoreStepSideways(context, APP))
    }

    private companion object {
        // No app at all: nothing the tests leave behind can matter.
        const val APP = "test.scrollshot.app"
        const val OTHER_APP = "test.scrollshot.other"
    }
}
