package eu.woshicado.circletoshare.capture

import eu.woshicado.circletoshare.capture.ScrollStitcher.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ScrollStitcherTest {

    /** A screen: [header] fixed rows, a window onto a long page, [footer]
     *  fixed rows. */
    private class Screen(
        val page: Array<IntArray>,
        val header: Array<IntArray>,
        val footer: Array<IntArray>
    ) {
        /** Page rows with a stroke of ink on them that falls between the
         *  samples: only the row's extremes tell. */
        var faintInk: Set<Int> = emptySet()

        /** [covered]: screen rows where [overlay] hides the page altogether,
         *  faint ink included. */
        fun at(
            scroll: Int,
            covered: IntRange = IntRange.EMPTY,
            overlay: ((y: Int, row: IntArray) -> Unit)? = null
        ): FrameSignature {
            val cells = IntArray(HEIGHT * COLS)
            val darkest = IntArray(HEIGHT)
            val brightest = IntArray(HEIGHT)
            for (y in 0 until HEIGHT) {
                val pageRow = scroll + y - header.size
                val row = when {
                    y < header.size -> header[y]
                    y >= HEIGHT - footer.size -> footer[y - (HEIGHT - footer.size)]
                    else -> page[pageRow]
                }.copyOf()
                overlay?.invoke(y, row)
                row.copyInto(cells, y * COLS)
                val inList = y >= header.size && y < HEIGHT - footer.size
                darkest[y] = if (inList && y !in covered && pageRow in faintInk) 20 else row.min()
                brightest[y] = row.max()
            }
            return FrameSignature(HEIGHT, COLS, cells, darkest, brightest)
        }
    }

    private fun noise(rows: Int, seed: Long): Array<IntArray> {
        val random = Random(seed)
        return Array(rows) { IntArray(COLS) { random.nextInt(256) } }
    }

    private fun blank(rows: Int, value: Int = 250) = Array(rows) { IntArray(COLS) { value } }

    private fun screen(page: Array<IntArray> = noise(6000, 1)) =
        Screen(page, header = noise(120, 2), footer = noise(90, 3))

    @Test
    fun findsHowFarAListMoved() {
        val s = screen()
        for (moved in listOf(700, 655, 731)) {
            val m = ScrollStitcher.match(s.at(1000), s.at(1000 + moved), expected = 700)
            assertEquals(Verdict.MOVED, m.verdict)
            assertEquals(moved, m.offset)
            assertEquals(120, m.staticTop)
            assertEquals(90, m.staticBottom)
        }
    }

    @Test
    fun aListThatStopsShortAtItsEndIsStillFound() {
        val s = screen()
        val m = ScrollStitcher.match(s.at(1000), s.at(1130), expected = 700)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(130, m.offset)
    }

    @Test
    fun aListThatRanOnByItselfIsStillFound() {
        val s = screen()
        // Half as far again as asked.
        val m = ScrollStitcher.match(s.at(1000), s.at(2200), expected = 800)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(1200, m.offset)
    }

    @Test
    fun nothingMovingIsToldApart() {
        val s = screen()
        assertEquals(Verdict.UNCHANGED, ScrollStitcher.match(s.at(1000), s.at(1000), 700).verdict)
    }

    @Test
    fun aDetailChangingOnAStillScreenIsNotAMove() {
        val s = screen()
        // A few rows that differ (a video frame, a cursor) where the list is.
        val later = s.at(1000) { y, row -> if (y in 600..640) row.fill(17) }
        assertEquals(Verdict.UNCHANGED, ScrollStitcher.match(s.at(1000), later, 700).verdict)
    }

    @Test
    fun aTickingClockDoesNotMoveTheFixedTop() {
        val s = screen()
        // The status bar (rows 0..39) shows something else in the later frame.
        val later = s.at(1700) { y, row -> if (y in 10..20) row.fill(99) }
        val m = ScrollStitcher.match(s.at(1000), later, expected = 700, minTop = 40)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(700, m.offset)
        assertEquals(120, m.staticTop)
    }

    @Test
    fun repeatingRowsAreSettledByTheDistanceAskedFor() {
        // A list of identical items, 100 rows each: 630 and 830 line up just
        // as well as the 730 it moved. (A move by a whole number of items
        // can't be told from none at all — the two frames are the same.)
        val item = noise(100, 7)
        val s = screen(Array(6000) { item[it % 100] })
        val m = ScrollStitcher.match(s.at(1000), s.at(1730), expected = 712)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(730, m.offset)
    }

    @Test
    fun blankStretchesDoNotVote() {
        // Mostly blank, a block of content every 500 rows.
        val content = noise(6000, 5)
        val s = screen(Array(6000) { if (it % 500 < 60) content[it] else IntArray(COLS) { 250 } })
        val m = ScrollStitcher.match(s.at(1000), s.at(1640), expected = 700)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(640, m.offset)
    }

    @Test
    fun aFloatingButtonOverTheListIsTolerated() {
        val s = screen()
        // The same 150 rows are covered in both frames, wherever the list is.
        val fab: (Int, IntArray) -> Unit = { y, row ->
            if (y in 1500..1650) for (c in 48 until COLS) row[c] = 30
        }
        val m = ScrollStitcher.match(
            s.at(1000, overlay = fab), s.at(1700, overlay = fab), expected = 700
        )
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(700, m.offset)
        // The button is over the bottom of the earlier frame's list: the
        // join stays up at the top, well above it, and the later frame —
        // where the list has moved out from under the button — gives those
        // rows.
        assertEquals(120, m.seam)
    }

    @Test
    fun aFeedStillLoadingInOneFrameIsRecognisedByWhatDoesAgree() {
        // Half the rows in the earlier frame's lower part are placeholders:
        // they show something else than they will once loaded.
        val s = screen()
        val placeholder = noise(HEIGHT, 51)
        val earlier = s.at(1000) { y, row ->
            if (y > 1000 && y % 2 == 0) placeholder[y].copyInto(row)
        }
        val m = ScrollStitcher.match(earlier, s.at(1700), expected = 700)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(700, m.offset)
        assertTrue("score ${m.score}", m.score < 0.7f)
    }

    @Test
    fun aBarTheListShowsThroughOverItsLowerHalfIsSeenPast() {
        // A list of items alike in most of their rows, and over the lower
        // quarter of the screen a bar that lets it show through: there the
        // two frames never agree, and elsewhere a whole item further on
        // agrees nearly as well as the right place does.
        val shared = noise(100, 81)
        val own = noise(6000, 82)
        val s = screen(Array(6000) { if (it % 100 < 70) shared[it % 100] else own[it] })
        val bar: (Int, IntArray) -> Unit = { y, row ->
            if (y in 1700 until 2310) for (c in row.indices) row[c] = (row[c] + 90) / 2
        }
        val m = ScrollStitcher.match(
            s.at(1000, overlay = bar), s.at(1700, overlay = bar), expected = 760
        )
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(700, m.offset)
        assertTrue("score ${m.score}", m.score < 0.7f)
    }

    @Test
    fun theAppsOwnAccountIsWhereElseToLook() {
        // Stopped short at 250, with rows that repeat every 100: the app
        // says 250, and that settles which.
        val item = noise(100, 7)
        val s = screen(Array(6000) { item[it % 100] })
        val m = ScrollStitcher.match(s.at(1000), s.at(1250), expected = 700, reported = 250)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(250, m.offset)
    }

    @Test
    fun whereNothingIsRecognisedTheAppsAccountIsTheGuess() {
        val a = screen(noise(6000, 11))
        val b = Screen(noise(6000, 12), a.header, a.footer)
        val m = ScrollStitcher.match(a.at(1000), b.at(1000), expected = 700, reported = 380)
        assertEquals(Verdict.UNSURE, m.verdict)
        assertEquals(380, m.offset)
        // But not an account that can't be right.
        val wild = ScrollStitcher.match(a.at(1000), b.at(1000), expected = 700, reported = 5000)
        assertEquals(700, wild.offset)
    }

    @Test
    fun aSmallPartChangingWithoutMovingIsNotAMove() {
        val s = screen()
        // A carousel 400 rows tall shows something else; the rest is still.
        val other = noise(HEIGHT, 61)
        val later = s.at(1000) { y, row -> if (y in 800 until 1200) other[y].copyInto(row) }
        assertEquals(Verdict.UNCHANGED, ScrollStitcher.match(s.at(1000), later, 700).verdict)
    }

    @Test
    fun aHeaderThatPinsItselfAfterTheMoveIsJoinedBelow() {
        val s = screen()
        // Once scrolled, a bar covers the list's first 200 rows.
        val bar = noise(200, 21)
        val later = s.at(1700) { y, row -> if (y in 120 until 320) bar[y - 120].copyInto(row) }
        val m = ScrollStitcher.match(s.at(1000), later, expected = 700)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(700, m.offset)
        assertEquals(120, m.staticTop)
        assertEquals(320, m.seam)
    }

    @Test
    fun aHeaderEndingOnTheFaintEndOfALineIsStillCleared() {
        // A page of blank rows with a line of "text" every 100: 60 rows of
        // it, then 10 rows of descenders too thin for the samples to catch.
        val text = noise(6000, 31)
        val page = Array(6000) { if (it % 100 < 60) text[it] else IntArray(COLS) { 250 } }
        val s = Screen(page, noise(120, 2), noise(90, 3))
        s.faintInk = (0 until 6000).filter { it % 100 in 60 until 70 }.toSet()
        // The bar that appears covers screen rows 120..329: 150 rows of its
        // own content, then 60 of plain background, the page's color. In the
        // later frame its last rows lie on descenders (page rows 1960..1969
        // are at screen rows 320..329).
        val bar = noise(150, 21)
        val later = s.at(1760, covered = 120 until 330) { y, row ->
            if (y in 120 until 270) bar[y - 120].copyInto(row)
            if (y in 270 until 330) row.fill(250)
        }
        val m = ScrollStitcher.match(s.at(1000), later, expected = 760)
        assertEquals(Verdict.MOVED, m.verdict)
        assertEquals(760, m.offset)
        assertTrue("joined at ${m.seam}, inside the bar", m.seam >= 330)
    }

    @Test
    fun theJoinMovesIntoWhiteSpaceWhereThereIsSome() {
        val text = noise(6000, 41)
        // Lines of 60 rows, 40 blank rows between them; the list's first
        // row on screen is the middle of a line.
        val page = Array(6000) { if (it % 100 < 60) text[it] else IntArray(COLS) { 250 } }
        val s = Screen(page, noise(120, 2), noise(90, 3))
        val m = ScrollStitcher.match(s.at(1030), s.at(1730), expected = 700)
        assertEquals(Verdict.MOVED, m.verdict)
        // Page row 1760 is the first blank one: screen row 120 + 30.
        assertEquals(150, m.seam)
    }

    @Test
    fun disagreementAllTheWayDownTakesTheJoinOnlySoFar() {
        // Every third row of the later frame shows something else (a page
        // still loading): no stretch where the two agree for long.
        val s = screen()
        val other = noise(HEIGHT, 71)
        val later = s.at(1700) { y, row -> if (y % 3 == 0) other[y].copyInto(row) }
        val m = ScrollStitcher.match(s.at(1000), later, expected = 700, reported = 700)
        assertEquals(700, m.offset)
        // Past where a header would commonly end, and not on down to the
        // last row that disagrees.
        assertEquals(m.staticTop + (HEIGHT * 0.12f).toInt(), m.seam)
    }

    @Test
    fun withNothingInTheWayTheJoinIsRightBelowTheFixedTop() {
        val s = screen()
        val m = ScrollStitcher.match(s.at(1000), s.at(1700), expected = 700)
        assertEquals(120, m.seam)
    }

    @Test
    fun unrelatedFramesAreNotForcedTogether() {
        val a = screen(noise(6000, 11))
        val b = Screen(noise(6000, 12), a.header, a.footer)
        val m = ScrollStitcher.match(a.at(1000), b.at(1000), expected = 700)
        assertEquals(Verdict.UNSURE, m.verdict)
        assertEquals(700, m.offset)
    }

    @Test
    fun aBlankScreenHasNothingMoreToGive() {
        val s = Screen(blank(6000), noise(120, 2), noise(90, 3))
        assertEquals(Verdict.UNCHANGED, ScrollStitcher.match(s.at(1000), s.at(1700), 700).verdict)
    }

    @Test
    fun framesOfDifferentSizesAreLeftAlone() {
        val s = screen()
        val other = FrameSignature(100, COLS, IntArray(100 * COLS))
        val m = ScrollStitcher.match(s.at(1000), other, expected = 700)
        assertEquals(Verdict.UNSURE, m.verdict)
        assertTrue(m.offset in 0..100)
    }

    // --- Snap bounds in a long picture ------------------------------------------

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = intArrayOf(left, top, right, bottom)

    private fun placed(frames: List<FrameElements>) =
        ScrollSnap.boxesInPicture(frames, HEIGHT).map { it.toList() }.toSet()

    @Test
    fun anElementOfTheListIsWhereItsFrameIsInThePicture() {
        // Two frames, the second 700 rows further down; bars of 120 and 90.
        val frames = listOf(
            FrameElements(0, 120, 90, listOf(box(40, 500, 900, 620))),
            FrameElements(700, 120, 90, listOf(box(40, 1500, 900, 1640)))
        )
        assertEquals(
            setOf(listOf(40, 500, 900, 620), listOf(40, 2200, 900, 2340)),
            placed(frames)
        )
    }

    @Test
    fun anElementSeenInTwoFramesCountsOnce() {
        // The same row of the list: at 900 in the first frame, 200 in the
        // second.
        val frames = listOf(
            FrameElements(0, 120, 90, listOf(box(0, 900, 1000, 1000))),
            FrameElements(700, 120, 90, listOf(box(0, 200, 1000, 300)))
        )
        assertEquals(setOf(listOf(0, 900, 1000, 1000)), placed(frames))
    }

    @Test
    fun barsCountFromTheFrameThatShowsThemInThePicture() {
        val toolbar = box(0, 40, 1000, 120)
        val bottomBar = box(0, 2310, 1000, 2400)
        val frames = listOf(
            FrameElements(0, 120, 90, listOf(toolbar, bottomBar)),
            FrameElements(700, 120, 90, listOf(toolbar, bottomBar)),
            FrameElements(1400, 120, 90, listOf(toolbar, bottomBar))
        )
        assertEquals(
            setOf(listOf(0, 40, 1000, 120), listOf(0, 2310 + 1400, 1000, 2400 + 1400)),
            placed(frames)
        )
    }

    @Test
    fun theListsOwnFrameAndWhatStraddlesItsEdgeAreLeftOut() {
        val frames = listOf(
            FrameElements(
                0, 120, 90,
                listOf(
                    box(0, 120, 1000, 2310), // the list
                    box(0, 100, 1000, 140), // half toolbar, half list
                    box(0, 600, 500, 700)
                )
            ),
            FrameElements(700, 120, 90, emptyList())
        )
        assertEquals(setOf(listOf(0, 600, 500, 700)), placed(frames))
    }

    @Test
    fun aPictureGrownUpwardsHasItsNewestFrameOnTop() {
        // As the capture keeps them going up: the newest frame at 0, the
        // one it began with moved down by as much as the list moved.
        val frames = listOf(
            FrameElements(700, 120, 90, listOf(box(0, 40, 1000, 120), box(0, 400, 500, 500))),
            FrameElements(0, 120, 90, listOf(box(0, 40, 1000, 120), box(0, 300, 500, 380)))
        )
        assertEquals(
            setOf(
                listOf(0, 40, 1000, 120), // the toolbar, once, at the top
                listOf(0, 1100, 500, 1200),
                listOf(0, 300, 500, 380)
            ),
            placed(frames)
        )
    }

    private companion object {
        const val HEIGHT = 2400
        const val COLS = 64
    }
}
