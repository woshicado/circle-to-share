package eu.woshicado.circletoshare

import org.junit.Assert.assertEquals
import org.junit.Test

/** When a service named in the settings but not running counts as stale
 *  (see [MainActivity.staleAfterGrace]). */
class StaleStatusTest {

    @Test
    fun notStale_whenEverythingRuns() {
        assertEquals(false to 0L, MainActivity.staleAfterGrace(looksStale = false, since = 0L, now = 5000L))
        // And a stale spell that ended is forgotten.
        assertEquals(false to 0L, MainActivity.staleAfterGrace(looksStale = false, since = 1000L, now = 5000L))
    }

    @Test
    fun staleOnlyOnceItHasLasted() {
        // First seen: remembered, not yet stale.
        assertEquals(false to 5000L, MainActivity.staleAfterGrace(looksStale = true, since = 0L, now = 5000L))
        // Still so a moment later: not yet.
        assertEquals(false to 5000L, MainActivity.staleAfterGrace(looksStale = true, since = 5000L, now = 6000L))
        // Still so after the grace: stale.
        assertEquals(
            true to 5000L,
            MainActivity.staleAfterGrace(looksStale = true, since = 5000L, now = 5000L + MainActivity.STALE_GRACE_MS)
        )
    }
}
