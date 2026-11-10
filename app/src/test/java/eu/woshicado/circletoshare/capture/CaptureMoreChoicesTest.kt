package eu.woshicado.circletoshare.capture

import eu.woshicado.circletoshare.capture.ScrollCapture.Direction
import eu.woshicado.circletoshare.crop.CropScreen.More
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which scrollshot buttons are left after what a run has found (see
 *  [ScreenshotAccessibilityService.captureMoreChoices]). */
class CaptureMoreChoicesTest {

    private fun choices(grown: Direction?, vararg ended: Direction) =
        ScreenshotAccessibilityService.captureMoreChoices(grown, ended.toSet())

    @Test
    fun bothToBeginWith_oneOnceThePictureHasGrown() {
        assertEquals(setOf(More.ABOVE, More.BELOW), choices(null))
        assertEquals(setOf(More.BELOW), choices(Direction.DOWN))
        assertEquals(setOf(More.ABOVE), choices(Direction.UP))
    }

    @Test
    fun noneForAWayThatIsAtItsEnd() {
        // Told at the first tap, before the picture grew: the other remains.
        assertEquals(setOf(More.ABOVE), choices(null, Direction.DOWN))
        // Grown down and then at the end: nothing is left.
        assertEquals(emptySet<More>(), choices(Direction.DOWN, Direction.DOWN))
        // Blocked: both ended.
        assertEquals(emptySet<More>(), choices(null, Direction.DOWN, Direction.UP))
    }

    @Test
    fun sideways_theSameWithItsOwnButtons() {
        fun side(grown: Direction?, vararg ended: Direction) =
            ScreenshotAccessibilityService.captureMoreChoices(grown, ended.toSet(), sideways = true)
        assertEquals(setOf(More.LEFT, More.RIGHT), side(null))
        assertEquals(setOf(More.RIGHT), side(Direction.DOWN))
        assertEquals(setOf(More.LEFT), side(Direction.UP))
        assertEquals(emptySet<More>(), side(Direction.DOWN, Direction.DOWN))
    }
}
