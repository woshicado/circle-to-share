package eu.woshicado.circletoshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a card goes beside its button (see [AnchoredCard.cardTop]). */
class AnchoredCardTest {

    // A button 100 high, a card 400 high, room from 50 (under a status bar)
    // to 2000 (over a navigation bar).
    private fun top(anchorTop: Int, preferUnder: Boolean, cardHeight: Int = 400) =
        AnchoredCard.cardTop(anchorTop, 100, cardHeight, 50, 2000, preferUnder)

    @Test
    fun onTheSideAskedFor_whereItFits() {
        assertEquals(1100, top(1000, preferUnder = true))
        assertEquals(600, top(1000, preferUnder = false))
    }

    @Test
    fun onTheOtherSide_whereItHasRunOutOfRoom() {
        // Asked for under, with the button near the bottom: over it.
        assertEquals(1300, top(1700, preferUnder = true))
        // Asked for over, with the button near the top: under it. (Over
        // would reach under the status bar: 440 - 400 < 50.)
        assertEquals(540, top(440, preferUnder = false))
    }

    @Test
    fun rightUpToTheEdges_andNoFurther() {
        assertEquals(1600, top(1500, preferUnder = true))   // ends at 2000
        assertEquals(1101, top(1501, preferUnder = true))   // would end at 2001
        assertEquals(50, top(450, preferUnder = false))     // starts at 50
        assertEquals(549, top(449, preferUnder = false))    // would start at 49
    }

    @Test
    fun nowhere_whereItFitsOnNeitherSide() {
        assertNull(top(1000, preferUnder = true, cardHeight = 1200))
        assertNull(top(1000, preferUnder = false, cardHeight = 1200))
    }
}
