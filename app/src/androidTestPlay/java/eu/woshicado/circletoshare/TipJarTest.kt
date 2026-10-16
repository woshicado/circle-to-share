package eu.woshicado.circletoshare

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The Play build keeps the billing library's telemetry off (see its manifest). */
@RunWith(AndroidJUnit4::class)
class TipJarTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theOnlyPermissionIsBilling() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(listOf("com.android.vending.BILLING"), info.requestedPermissions.orEmpty().toList())
    }

    @Test
    fun noTelemetryComponentIsDeclared() {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
        )
        val declared = info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name }
        assertEquals(emptyList<String>(), declared.filter { "datatransport" in it })
    }

    @Test
    fun connectingSchedulesNoTelemetryUpload() {
        // Connecting makes the billing library log (also when setup fails, as
        // with no Google account). A stored event would get an upload job,
        // which without ACCESS_NETWORK_STATE throws on Android 14+.
        val jobs = context.getSystemService(JobScheduler::class.java)
        jobs.cancelAll()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application),
            )[TipJar::class.java]
        }
        try {
            repeat(40) {
                SystemClock.sleep(250)
                assertEquals(emptyList<JobInfo>(), jobs.allPendingJobs)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
