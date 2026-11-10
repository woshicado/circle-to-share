package eu.woshicado.circletoshare.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A screenshot reduced to what lining two of them up needs: [cols] brightness
 * samples (0..255) for each of its [height] rows, row after row in [cells].
 *
 * [darkest] and [brightest] are each row's extremes over all of its pixels,
 * not just the sampled ones: the thin end of a letter between two samples
 * shows up there and nowhere else. Left out, they are taken from the samples.
 *
 * Plain numbers rather than a bitmap, so the matching can be tested without a
 * device.
 */
internal class FrameSignature(
    val height: Int,
    val cols: Int,
    val cells: IntArray,
    darkest: IntArray? = null,
    brightest: IntArray? = null
) {

    init {
        require(height > 0 && cols > 0 && cells.size == height * cols)
        require(darkest == null || darkest.size == height)
        require(brightest == null || brightest.size == height)
    }

    private val darkest = darkest ?: IntArray(height) { y ->
        var lo = 255
        for (c in 0 until cols) lo = minOf(lo, cells[y * cols + c])
        lo
    }
    private val brightest = brightest ?: IntArray(height) { y ->
        var hi = 0
        for (c in 0 until cols) hi = maxOf(hi, cells[y * cols + c])
        hi
    }

    // Per row: the sum of its samples, and whether there is anything in it
    // to recognise it by. A blank row looks like every other blank row, at
    // any offset, and must not get a say in where two frames line up.
    private val sums = IntArray(height)
    private val informative = BooleanArray(height)

    init {
        for (y in 0 until height) {
            var sum = 0
            var lo = 255
            var hi = 0
            val base = y * cols
            for (c in 0 until cols) {
                val v = cells[base + c]
                sum += v
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            sums[y] = sum
            informative[y] = hi - lo >= FLAT_SPREAD
        }
    }

    fun isInformative(y: Int): Boolean = informative[y]

    /** Whether row [y] is one flat color from end to end. */
    fun isBlank(y: Int): Boolean = brightest[y] - darkest[y] < FLAT_SPREAD

    /** Whether row [y] here and row [otherY] of [other] have the same
     *  extremes — which a row with a stroke of ink on it and one without do
     *  not, however little of it there is. */
    fun sameExtremes(y: Int, other: FrameSignature, otherY: Int): Boolean =
        abs(darkest[y] - other.darkest[otherY]) <= EXTREME_TOLERANCE &&
            abs(brightest[y] - other.brightest[otherY]) <= EXTREME_TOLERANCE

    /** Whether row [y] here shows what row [otherY] of [other] shows: their
     *  samples differ by no more than [tolerance] on average. */
    fun rowMatches(y: Int, other: FrameSignature, otherY: Int, tolerance: Int): Boolean {
        val budget = tolerance * cols
        // The sums differ by no more than the samples do, and cost nothing
        // to compare: most rows that don't match are settled here.
        if (abs(sums[y] - other.sums[otherY]) > budget) return false
        var diff = 0
        val a = y * cols
        val b = otherY * cols
        for (c in 0 until cols) {
            diff += abs(cells[a + c] - other.cells[b + c])
            if (diff > budget) return false
        }
        return true
    }

    private companion object {
        // Brightest minus darkest sample below which a row counts as blank.
        const val FLAT_SPREAD = 12
        const val EXTREME_TOLERANCE = 16
    }
}

/**
 * Works out how far the content moved between two screenshots of a list that
 * was scrolled in between — the one question stitching them together turns
 * on. Everything else (what to keep of which frame) follows from the answer.
 */
internal object ScrollStitcher {

    enum class Verdict {
        /** The content moved up by [Match.offset] rows. */
        MOVED,

        /** Nothing moved: the end of the content, or nothing that scrolls. */
        UNCHANGED,

        /** Something moved, but not recognisably. [Match.offset] is the
         *  distance asked for, to go by for want of anything better. */
        UNSURE
    }

    /**
     * [staticTop] and [staticBottom]: how many rows at either end show the
     * same in both frames — bars that stay put while the list moves.
     *
     * [seam] is where to join the two: from that row down, the later frame
     * shows what the earlier one did [offset] rows further down. It is
     * [staticTop] or below — below, when the later frame has something at
     * its top that the earlier one's list did not (see [seamRow]).
     */
    data class Match(
        val verdict: Verdict,
        val offset: Int,
        val staticTop: Int,
        val staticBottom: Int,
        val score: Float,
        val seam: Int = staticTop
    )

    /**
     * Line [cur] up against [prev], the frame before it.
     *
     * [expected] is how far the content was asked to move. Real lists keep
     * close to it (see ScrollCapture: the gesture is built so they do), which
     * is what tells one row of a repeating pattern from the next — so the
     * search starts there, and only looks further afield when nothing near
     * it fits: at the end of the content, where the list stops short, or
     * where a list ran on by itself after all.
     *
     * [minTop] and [minBottom] are rows known to be fixed whatever they show
     * (the status bar's clock ticks; the list doesn't reach up there).
     * [reported] is how far the app itself says it scrolled, 0 if it didn't
     * say: a second place to look, and a better guess than [expected] where
     * looking finds nothing.
     */
    fun match(
        prev: FrameSignature,
        cur: FrameSignature,
        expected: Int,
        minTop: Int = 0,
        minBottom: Int = 0,
        reported: Int = 0
    ): Match {
        val h = cur.height
        if (prev.height != h || prev.cols != cur.cols) {
            // A rotation, a resolution change: nothing to compare.
            return Match(Verdict.UNSURE, expected.coerceIn(0, h), 0, 0, 0f)
        }
        var top = minTop.coerceIn(0, h)
        while (top < h && prev.rowMatches(top, cur, top, EQUAL_TOLERANCE)) top++
        var bottom = minBottom.coerceIn(0, h - top)
        while (bottom < h - top && prev.rowMatches(h - 1 - bottom, cur, h - 1 - bottom, EQUAL_TOLERANCE)) {
            bottom++
        }
        val band = h - top - bottom
        val minRows = max(MIN_ROWS, h / 50)
        if (band < 2 * minRows) return Match(Verdict.UNCHANGED, 0, top, bottom, 1f)

        // Something differs between the fixed ends. If it is only a detail —
        // a blinking cursor, a playing video — the list still hasn't moved.
        val still = score(prev, cur, 0, top, bottom, minRows)
        if (still >= STILL_SCORE) return Match(Verdict.UNCHANGED, 0, top, bottom, still)

        val farthest = band - minRows
        val want = expected.coerceIn(1, farthest)
        // What to go by where nothing can be recognised: what the app says
        // it scrolled by, if it says and that is believable (a list that
        // stopped short at its end knows by how much); else what was asked.
        val told = reported in 1..(expected * REPORTED_MAX).toInt()
        val guess = if (told) reported.coerceIn(1, farthest) else want

        // First where the app says it went, if it says — however far, as
        // long as the frames can still be compared there: rows that repeat
        // line up in several places, and the app knows which is the one;
        // and a pager snaps to a whole page, far beyond what was asked.
        if (reported in 1..farthest) {
            val there = best(
                prev, cur, top, bottom, minRows, reported,
                from = max(1, reported - REPORTED_SLACK), to = min(farthest, reported + REPORTED_SLACK)
            )
            // (Where the picture's best guess and the app's account agree
            // to the pixel, that is two witnesses: less will do — for an
            // account near what was asked. One far beyond it may be a
            // pager's whole page, or two nested lists both reporting: only
            // frames that plainly agree there settle it.)
            if (there.convincing() ||
                (told && there.score >= ACCEPT_SCORE_LONE && abs(there.offset - reported) <= REPORTED_EXACT)
            ) {
                return moved(prev, cur, there, top, bottom)
            }
        }
        val near = best(
            prev, cur, top, bottom, minRows, want,
            from = max(1, (expected * NEAR_FROM).toInt()),
            to = min(farthest, (expected * NEAR_TO).toInt() + NEAR_SLACK)
        )
        // (As with the app's account above: the picture's best guess
        // landing on the very distance asked for is two witnesses. Lists
        // that say nothing — Compose's — keep to it to the pixel.)
        if (near.convincing() ||
            (near.score >= ACCEPT_SCORE_LONE && abs(near.offset - want) <= REPORTED_EXACT)
        ) {
            return moved(prev, cur, near, top, bottom)
        }
        // What the two frames share is the earlier one's lower half, and
        // that is where bars float over a list that shows through them (a
        // player bar, tabs with a fade): half the rows may disagree there
        // for good, at the right offset too. The upper half of what they
        // share is clear of that; if it agrees nearly row for row, that
        // will do.
        if (near.score > 0f &&
            score(prev, cur, near.offset, top, bottom, 2 * minRows, upperHalf = true) >= ACCEPT_SCORE_UPPER
        ) {
            return moved(prev, cur, near, top, bottom)
        }
        // Further afield only on better evidence, and not to the very end:
        // the few rows two frames share at a large offset agree by chance
        // often enough.
        val farRows = max(minRows, h / FAR_ROWS)
        val anywhere = best(
            prev, cur, top, bottom, farRows, guess,
            from = 1, to = min(band - farRows, (expected * FAR_TO).toInt() + NEAR_SLACK)
        )
        if (anywhere.score >= ACCEPT_SCORE_FAR) {
            return moved(prev, cur, anywhere, top, bottom)
        }
        // Nothing fits well. If "hasn't moved" fits better than any move,
        // believe that: appending a guess would repeat what is already there.
        if (still >= MOSTLY_STILL_SCORE && still >= anywhere.score) {
            return Match(Verdict.UNCHANGED, 0, top, bottom, still)
        }
        // Likewise where only a small part of the screen changed at all,
        // and not by moving: a carousel turning, a video playing.
        if (band < h / SMALL_BAND) return Match(Verdict.UNCHANGED, 0, top, bottom, still)
        // (The join is placed as if the guess were right: if it is, a
        // header at the top is cleared as usual; if not, nothing is lost.
        // Asked for a whole page — a pager with no account of its own —
        // the new frame goes on after the old, with nothing in common.
        // Where the app says how far it went, that is the best guess
        // there is, however far beyond what was asked, as long as it is
        // within the frame: a pager snaps to the next page, whose frames
        // share nothing, and a join at the distance asked would take the
        // start of that page for the end of the last.)
        val placed = when {
            expected >= band -> band
            reported in 1..band -> reported
            else -> guess
        }
        return Match(
            Verdict.UNSURE, placed, top, bottom, max(near.score, anywhere.score),
            seamRow(prev, cur, placed, top, bottom)
        )
    }

    /** [runnerUp]: the best score among the offsets tried that are not
     *  right next to [offset]. */
    private class Candidate(val offset: Int, val score: Float, val runnerUp: Float) {

        /**
         * Most rows agree — or fewer do, but at this offset and no other. A
         * feed whose pictures were still loading in one frame agrees only in
         * its text, yet nowhere else at all: a peak that stands out is as
         * good as a high one. (Rows that repeat give several peaks, and none
         * stands out.)
         */
        fun convincing(): Boolean =
            score >= ACCEPT_SCORE || (score >= ACCEPT_SCORE_LONE && score - runnerUp >= LONE_MARGIN)
    }


    private fun moved(
        prev: FrameSignature, cur: FrameSignature, found: Candidate, top: Int, bottom: Int
    ) = Match(
        Verdict.MOVED, found.offset, top, bottom, found.score,
        seamRow(prev, cur, found.offset, top, bottom)
    )

    /**
     * The row of [cur] to join at, given that its content moved up by [d].
     *
     * Any row would do from which [cur] really shows what [prev] did [d]
     * further down. Right below the fixed top is the first candidate, but
     * not always one: a header that only pins itself to the top once the
     * list has moved (a search bar, a collapsed title), or one the list
     * shows through, covers rows there in [cur] that [prev] had the list's
     * own content for. Joined above it, the header would be in the picture
     * a second time, in place of what it hides.
     *
     * So: below the last row near the top where the two disagree. Whatever
     * [cur] is not taken for there, [prev] has. "Disagree" is meant strictly
     * here — the tail of a comma is enough — or the header's lower edge
     * slices through whatever line of text it happens to end on.
     *
     * And from there, on to the next blank row if one is near: a join in
     * the white space between two lines cannot cut a letter in half, should
     * [d] be a pixel off.
     */
    private fun seamRow(prev: FrameSignature, cur: FrameSignature, d: Int, top: Int, bottom: Int): Int {
        val h = cur.height
        // Not far down: headers are at the top, and the further down the
        // join, the closer to its own bottom edge [prev] is taken — where
        // floating buttons and bars sit over its list.
        val until = min(top + (h * HEADER_ZONE).toInt(), h - bottom - d - (h * SEAM_GUARD).toInt())
        fun same(y: Int) =
            cur.rowMatches(y, prev, y + d, MATCH_TOLERANCE) && cur.sameExtremes(y, prev, y + d)
        // Only what begins at the top: once the two have agreed for a good
        // stretch, the header is over, and a disagreement further down is
        // something else — as a rule a button floating over the bottom of
        // [prev]'s list, which the join must stay above, not go below.
        val enough = h / CLEAN_RUN
        var seam = top
        var clean = 0
        var over = false
        for (y in top until until) {
            if (same(y)) {
                if (++clean >= enough) {
                    over = true
                    break
                }
            } else {
                seam = y + 1
                clean = 0
            }
        }
        // Disagreement all the way down is not all header: a page that
        // changes as it goes (pictures loading, a video), or an offset that
        // is only a guess. Going below all of it would take the join far
        // down, into whatever floats over the bottom of [prev]; staying at
        // the top would leave in a header that is there after all. So: as
        // far down as headers commonly go, and no further.
        if (!over) seam = min(seam, top + (h * NOISY_HEADER).toInt())
        val blankUntil = min(seam + h / BLANK_SEARCH, h - bottom - d)
        for (y in seam until blankUntil) {
            if (cur.isBlank(y) && same(y)) return y
        }
        return seam
    }

    /** The offset in [from]..[to] at which the frames agree best; of several
     *  that agree as well as each other, the one closest to [want]. */
    private fun best(
        prev: FrameSignature, cur: FrameSignature,
        top: Int, bottom: Int, minRows: Int, want: Int,
        from: Int, to: Int
    ): Candidate {
        if (from > to) return Candidate(want, -1f, 0f)
        val scores = FloatArray(to - from + 1)
        var bestOffset = want
        var bestScore = -1f
        for (d in from..to) {
            val s = score(prev, cur, d, top, bottom, minRows)
            scores[d - from] = s
            if (s > bestScore + TIE ||
                (s > bestScore - TIE && abs(d - want) < abs(bestOffset - want))
            ) {
                bestScore = max(s, bestScore)
                bestOffset = d
            }
        }
        var runnerUp = 0f
        for (d in from..to) {
            if (abs(d - bestOffset) > PEAK_WIDTH) runnerUp = max(runnerUp, scores[d - from])
        }
        return Candidate(bestOffset, bestScore, runnerUp)
    }

    /**
     * How well the frames agree if the content moved up by [d] rows: the
     * share of [cur]'s recognisable rows between the fixed ends that show
     * what [prev] showed [d] rows further down — with [upperHalf], of the
     * upper half of those rows only. 0 if too few rows are left to tell.
     */
    private fun score(
        prev: FrameSignature, cur: FrameSignature,
        d: Int, top: Int, bottom: Int, minRows: Int, upperHalf: Boolean = false
    ): Float {
        val all = cur.height - bottom - d
        val end = if (upperHalf) top + (all - top) / 2 else all
        var compared = 0
        var matched = 0
        for (y in top until end) {
            if (!cur.isInformative(y)) continue
            compared++
            if (cur.rowMatches(y, prev, y + d, MATCH_TOLERANCE)) matched++
        }
        return if (compared < minRows) 0f else matched.toFloat() / compared
    }

    // Average difference per sample (of 255) up to which two rows at the
    // same place count as showing the same thing.
    private const val EQUAL_TOLERANCE = 2

    // The same for rows that have moved: more generous, because a list need
    // not land on a whole pixel, and text then renders a shade differently.
    private const val MATCH_TOLERANCE = 10

    // The fewest recognisable rows an answer may rest on.
    private const val MIN_ROWS = 24

    // Where to look first, as a share of the distance asked for. Lopsided:
    // lists swallow the first few pixels of a drag before they start moving,
    // and never move further than the finger did.
    private const val NEAR_FROM = 0.75f
    private const val NEAR_TO = 1.05f
    private const val NEAR_SLACK = 8

    // How far the wider search goes, as a share of the distance asked for
    // (lists that run on by themselves have been seen to reach 1.4), and the
    // share of the frame's height it wants to compare at the least.
    private const val FAR_TO = 1.8f
    private const val FAR_ROWS = 16

    // Up to what share of the distance asked for the app's own account of
    // how far it scrolled is believed, and how far around it to look.
    private const val REPORTED_MAX = 1.2f
    private const val REPORTED_SLACK = 12
    private const val REPORTED_EXACT = 3

    // The share of rows that must agree. Short of all of them: a floating
    // button, a picture still loading and the like sit in some.
    private const val ACCEPT_SCORE = 0.7f

    // A lower score that will do if no other offset comes within this much
    // of it; offsets this close to the best count as the same peak.
    private const val ACCEPT_SCORE_LONE = 0.45f
    private const val LONE_MARGIN = 0.25f
    private const val PEAK_WIDTH = 6
    private const val ACCEPT_SCORE_FAR = 0.85f
    private const val ACCEPT_SCORE_UPPER = 0.9f
    private const val STILL_SCORE = 0.97f
    private const val MOSTLY_STILL_SCORE = 0.8f

    // How far below the fixed top a header may reach that the join has to
    // clear, and how much of the earlier frame's bottom end to leave out of
    // it, as shares of the frame's height.
    private const val HEADER_ZONE = 0.3f
    private const val SEAM_GUARD = 0.1f

    // The share of the frame's height (1/n) the two must agree for without
    // a break for a header at the top to count as over.
    private const val CLEAN_RUN = 20

    // How far below the fixed top the join goes at most where the two never
    // agree for that long, as a share of the frame's height.
    private const val NOISY_HEADER = 0.12f

    // A band of change smaller than this share of the frame (1/n) in which
    // nothing lines up is a detail changing, not a list moving.
    private const val SMALL_BAND = 4

    // How far past that to look for white space to join in: a 20th of the
    // frame's height.
    private const val BLANK_SEARCH = 20

    // Scores this close count as equal.
    private const val TIE = 0.005f
}

/**
 * The elements that were on screen in one frame of a long picture: [boxes]
 * as left, top, right, bottom in the frame's own pixels; [top], the row of
 * the picture the frame's row 0 is at; and how many rows at either end of
 * the frame were fixed bars rather than list ([fixedTop], [fixedBottom]).
 */
internal class FrameElements(
    val top: Int,
    val fixedTop: Int,
    val fixedBottom: Int,
    val boxes: List<IntArray>
)

/** Where the elements of a long picture's frames are in the picture: what
 *  "snap to screen content" snaps to there. */
internal object ScrollSnap {

    /**
     * The boxes of all [frames], in the picture's pixels.
     *
     * An element of the list is at the same place in the picture whichever
     * frame it was seen in, so every frame's count (one seen in two frames
     * comes out twice, at the same place or within a pixel of it). The
     * bars are another matter: a toolbar is in every frame and in the
     * picture once, so what lies above the list is taken from the topmost
     * frame only, what lies below from the bottommost, and what straddles
     * the edge of the list from none. Nor is the list itself taken — or
     * anything else nearly as tall as its window: that is a frame around
     * what scrolls, and would leave a line across the picture at every
     * step.
     */
    fun boxesInPicture(frames: List<FrameElements>, frameHeight: Int): List<IntArray> {
        if (frames.isEmpty()) return emptyList()
        val topmost = frames.minOf { it.top }
        val bottommost = frames.maxOf { it.top }
        val seen = HashSet<List<Int>>()
        val out = ArrayList<IntArray>()
        for (frame in frames) {
            val listTop = frame.fixedTop
            val listBottom = frameHeight - frame.fixedBottom
            val tall = (listBottom - listTop) * TALL
            for (box in frame.boxes) {
                val (left, top, right, bottom) = box
                if (frames.size > 1 && bottom - top >= tall) continue
                val inList = top >= listTop && bottom <= listBottom
                val above = bottom <= listTop && frame.top == topmost
                val below = top >= listBottom && frame.top == bottommost
                if (!inList && !above && !below) continue
                val placed = listOf(left, top + frame.top, right, bottom + frame.top)
                if (seen.add(placed)) out += placed.toIntArray()
            }
        }
        return out
    }

    // The share of the list's window above which an element is a frame
    // around the list rather than something in it.
    private const val TALL = 0.8f
}
