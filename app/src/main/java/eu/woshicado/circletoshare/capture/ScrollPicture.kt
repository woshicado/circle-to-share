package eu.woshicado.circletoshare.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import eu.woshicado.circletoshare.crop.SnapBounds

private typealias Outcome = ScrollCapture.Outcome
private typealias Direction = ScrollCapture.Direction

/**
 * The picture a "Capture more" run grows: frames of a list, each scrolled
 * on from the last, joined into one (see [ScrollCapture], which does the
 * scrolling and brings the frames).
 *
 * Nothing here touches the screen or the app on it, so all of it can be
 * tried on made-up frames.
 *
 * [join] reads this picture's state and is meant for a worker thread;
 * everything that changes the state ([start], [commit]) and every other
 * reader belongs to one thread, the caller's main one, and no [join] may be
 * under way while it runs.
 */
internal class ScrollPicture(
    // Whether to paint over scrollbar handles (see removeEdgeFloaters): the
    // user's to switch off, asked at every join.
    private val removeHandles: () -> Boolean = { true }
) {

    /** The picture so far; null until [start]. */
    var bitmap: Bitmap? = null
        private set

    /** The way the picture has grown, once it has: it is one frame onto
     *  the list, extended at one end, and can't be at the other without
     *  scrolling all the way back. */
    var direction: Direction? = null
        private set

    /** Whether the picture is as long as it may get (see [MAX_BYTES]). */
    var full = false
        private set

    /** The height of a frame, in the picture's pixels; 0 until [start]. */
    var frameHeight = 0
        private set

    // The last frame joined on: what the next one is lined up against, and
    // the row of [bitmap] its top row is at.
    private var lastFrame: FrameSignature? = null
    private var lastFrameTop = 0

    private var steps = 0

    /**
     * Begin with [first], whose [signature] is [signatureOf] it and whose
     * [elements] are what was on screen in it (null: not asked for).
     */
    fun start(first: Bitmap, signature: FrameSignature, elements: List<IntArray>?) {
        snapFrames.clear()
        elements?.let { snapFrames += FrameElements(0, 0, 0, it) }
        olderEdge = null
        bitmap = first
        full = false
        direction = null
        lastFrame = signature
        lastFrameTop = 0
        frameHeight = first.height
    }

    /**
     * Take over what [join] worked out for a frame taken [going] up or
     * down, with the [elements] that were on screen in it. Returns what
     * became of the step.
     */
    fun commit(going: Direction, joined: Joined, elements: List<IntArray>?): Outcome {
        joined.bitmap?.let {
            bitmap = it
            direction = going
            noteElements(going, joined, elements)
        }
        joined.signature?.let { lastFrame = it }
        lastFrameTop = joined.frameTop
        if (joined.outcome == Outcome.TOO_LONG) full = true
        return joined.outcome
    }

    class Joined(
        val outcome: Outcome,
        val bitmap: Bitmap?,
        val signature: FrameSignature?,
        val frameTop: Int,
        // Of the join, for the snap bounds: how far the list had moved, and
        // the fixed rows at either end.
        val match: ScrollStitcher.Match? = null
    )

    // --- Snap bounds ----------------------------------------------------------

    // The elements on screen in each frame of the picture, if "Snap to
    // screen content" is on: read as each frame is taken, like the one
    // screenshot's are. Main thread.
    private val snapFrames = ArrayList<FrameElements>()

    /** A frame was joined on: where its elements are, and where that puts
     *  those of the frames before it. */
    private fun noteElements(going: Direction, joined: Joined, boxes: List<IntArray>?) {
        val match = joined.match ?: return
        if (boxes == null || snapFrames.isEmpty()) return
        if (snapFrames.size == 1) {
            // The first frame's bars are only known now that there is a
            // second to tell them by.
            val first = snapFrames[0]
            snapFrames[0] = FrameElements(first.top, match.staticTop, match.staticBottom, first.boxes)
        }
        if (going == Direction.UP) {
            // The picture grew at the top: everything in it is further down.
            for (i in snapFrames.indices) {
                val f = snapFrames[i]
                snapFrames[i] = FrameElements(f.top + match.offset, f.fixedTop, f.fixedBottom, f.boxes)
            }
        }
        snapFrames += FrameElements(joined.frameTop, match.staticTop, match.staticBottom, boxes)
    }

    /** What to snap to in [bitmap]; null if no elements were handed in or
     *  there is nothing. */
    fun snapBounds(): SnapBounds? {
        val picture = bitmap ?: return null
        val rects = ScrollSnap.boxesInPicture(snapFrames, frameHeight)
            .map { Rect(it[0], it[1], it[2], it[3]) }
        Log.d(
            TAG,
            "capture more: snap: ${rects.size} boxes in a picture of ${picture.height} rows, from " +
                snapFrames.joinToString { "${it.boxes.size}@${it.top}(${it.fixedTop}/${it.fixedBottom})" } +
                "; lowest at ${rects.maxOfOrNull { it.bottom }}"
        )
        // (In the picture's own pixels already: "screen" is the picture.)
        return if (rects.isEmpty()) null else SnapBounds(rects, picture.width, picture.height)
    }

    /**
     * Line [frame] — taken after scrolling [going] up or down — up against
     * the last one and build the longer picture. Changes nothing: [commit]
     * does. Slow; not for the main thread.
     *
     * [expected] is how far the list was asked to move and [reported] how
     * far the app says it did (0: it didn't say), [minTop] and [minBottom]
     * the rows known to be bars; see [ScrollStitcher.match].
     */
    fun join(
        going: Direction, frame: Bitmap, expected: Int, reported: Int, minTop: Int, minBottom: Int
    ): Joined {
        val unchanged = Joined(Outcome.END, null, null, lastFrameTop)
        val soFar = bitmap ?: return unchanged
        val last = lastFrame ?: return unchanged
        if (frame.width != soFar.width || frame.height != frameHeight) {
            // The screen was turned mid-run.
            return Joined(Outcome.FAILED, null, null, lastFrameTop)
        }
        val started = SystemClock.uptimeMillis()
        val signature = signatureOf(frame)
        // The stitcher lines a frame up against the one showing what is
        // further up the list. Going down that is the last frame; going up,
        // the new one.
        val match = if (going == Direction.DOWN) {
            ScrollStitcher.match(last, signature, expected, minTop, minBottom, reported)
        } else {
            ScrollStitcher.match(signature, last, expected, minTop, minBottom, reported)
        }
        steps++
        val summary = "$going ${match.verdict} offset=${match.offset} expected=$expected " +
            "score=${"%.2f".format(match.score)} fixedTop=${match.staticTop} seam=${match.seam} " +
            "fixedBottom=${match.staticBottom} frame=${frame.width}x${frame.height} " +
            "soFar=${soFar.height} minTop=$minTop minBottom=$minBottom " +
            "appSays=$reported " +
            "matchMs=${SystemClock.uptimeMillis() - started}"
        Log.d(TAG, "capture more #$steps: $summary")
        if (match.verdict == ScrollStitcher.Verdict.UNCHANGED) return unchanged
        // (What removeEdgeFloaters remembers is of the join before this
        // one, and of no use to the one after a join it had no part in.)
        if (match.verdict != ScrollStitcher.Verdict.MOVED || !removeHandles()) olderEdge = null
        val outcome =
            if (match.verdict == ScrollStitcher.Verdict.MOVED) Outcome.EXTENDED else Outcome.UNSURE
        val width = soFar.width
        // Whoever asked for this step made sure there was room for the
        // distance asked for; a list that ran on may have gone further.
        if ((soFar.height + match.offset).toLong() * width * 4 > MAX_BYTES) {
            return Joined(Outcome.TOO_LONG, null, null, lastFrameTop)
        }

        if (going == Direction.UP) {
            // The mirror image of going down (below): the new frame gives
            // the top of the picture, down to the join; the picture so far
            // carries on from there. The last frame put on is always the
            // topmost, so its rows are the picture's.
            val head = match.seam + match.offset
            val height = head + soFar.height - match.seam
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                val top = Rect(0, 0, width, head)
                drawBitmap(frame, top, top, null)
                drawBitmap(
                    soFar,
                    Rect(0, match.seam, width, soFar.height),
                    Rect(0, head, width, height),
                    null
                )
            }
            if (match.verdict == ScrollStitcher.Verdict.MOVED && removeHandles()) {
                removeEdgeFloaters(going, out, Rows(frame, 0), Rows(soFar, 0), match, upperAt = 0, lowerAt = head)
            }
            return Joined(outcome, out, signature, 0, match)
        }

        // Of the picture so far, keep everything down to where the new
        // frame is joined on; of the new frame, everything from there on.
        // Each frame but the last thus gives only the rows that scroll away
        // at its top — whatever sits over the bottom of the list (a floating
        // button, the gesture bar) comes from the last alone.
        val keep = lastFrameTop + match.seam + match.offset
        val height = keep + frame.height - match.seam
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            val top = Rect(0, 0, width, keep)
            drawBitmap(soFar, top, top, null)
            drawBitmap(
                frame,
                Rect(0, match.seam, width, frame.height),
                Rect(0, keep, width, height),
                null
            )
        }
        if (match.verdict == ScrollStitcher.Verdict.MOVED && removeHandles()) {
            removeEdgeFloaters(
                going, out, Rows(soFar, lastFrameTop), Rows(frame, 0), match,
                upperAt = lastFrameTop, lowerAt = keep
            )
        }
        return Joined(outcome, out, signature, lastFrameTop + match.offset, match)
    }

    /** A frame's rows, wherever they are: row y of the frame is row
     *  [offset] + y of [bitmap]. (The last frame's are in the picture.) */
    private class Rows(val bitmap: Bitmap, val offset: Int)

    /**
     * Paint over what floats at the right edge of a list and moves with the
     * scrolling — a scrollbar's handle, as in a gallery — which would
     * otherwise be in the picture once for every frame, a little further
     * down each time.
     *
     * Where two frames overlap there are two pictures of every row of the
     * list, and a handle is in one of them only: it sits at about the same
     * place on screen in both, and the list has moved on underneath. So
     * wherever the two disagree at the edge, one of them shows what the
     * handle hides in the other. Which is which the place alone cannot say
     * (a handle at one height in the later frame and one a scroll's length
     * further down in the earlier disagree in the very same rows): what
     * tells is that a handle looks like itself. The frame whose odd patch
     * is found again in the other, at nearly the same height on screen, is
     * the one with the handle, and that patch of [out] is taken from the
     * other.
     *
     * A handle that has only just appeared has nothing to be found again
     * in; it is remembered, and painted over one step later if it turns
     * out to have been one.
     *
     * [upper] is the frame showing what is further up the list, [lower] the
     * other; their rows are in [out] from [upperAt] (row 0 of [upper]) and
     * [lowerAt] (row [ScrollStitcher.Match.seam] of [lower]). Only a handle
     * in [lower] is painted over (one lower down on screen, in [upper], is
     * below the join and not in the picture). Whatever is painted is the
     * same rows of the list as another frame has them.
     */
    private fun removeEdgeFloaters(
        going: Direction, out: Bitmap, upper: Rows, lower: Rows,
        match: ScrollStitcher.Match, upperAt: Int, lowerAt: Int
    ) {
        val h = frameHeight
        val d = match.offset
        val w = out.width
        val strip = (w * EDGE_STRIP).toInt()
        val x0 = w - strip
        val first = match.staticTop
        val end = h - match.staticBottom - d
        val before = olderEdge
        olderEdge = null
        if (strip < 8 || end - first < 2 * FLOATER_MIN_ROWS) return
        val up = IntArray(strip * h).also { upper.bitmap.getPixels(it, 0, strip, x0, upper.offset, strip, h) }
        val low = IntArray(strip * h).also { lower.bitmap.getPixels(it, 0, strip, x0, lower.offset, strip, h) }
        // [upper] as it was taken, to look for a handle's twin in: going
        // down its rows are the picture's, where its own handle may have
        // been painted over a step ago — and then every other one went
        // unrecognised.
        val upAsTaken = before?.lower?.takeIf { going == Direction.DOWN && it.size == up.size } ?: up

        // The average difference in brightness between row [ya] of [a] and
        // row [yb] of [b], over the strip's columns [x1]..[x2].
        fun rowDiff(a: IntArray, ya: Int, b: IntArray, yb: Int, x1: Int = 0, x2: Int = strip): Int {
            var sum = 0
            var n = 0
            var x = x1
            val step = if (x2 - x1 > 48) 3 else 1
            while (x < x2) {
                sum += kotlin.math.abs(brightness(a[ya * strip + x]) - brightness(b[yb * strip + x]))
                n++
                x += step
            }
            return if (n == 0) 0 else sum / n
        }
        // How unlike rows [from]..[to] of [a] are, in columns [x1]..[x2],
        // to anything at nearly the same height on screen in [b] (and how
        // much higher or lower the likeliest is): small for a handle that
        // is in both.
        fun unlike(a: IntArray, b: IntArray, from: Int, to: Int, x1: Int, x2: Int): Pair<Int, Int> {
            val reach = h / FLOATER_REACH
            var best = Int.MAX_VALUE
            var at = 0
            for (shift in -reach..reach) {
                if (from < 0 || to > h || from + shift < 0 || to + shift > h) continue
                var sum = 0
                var n = 0
                var y = from
                while (y < to) {
                    sum += rowDiff(a, y, b, y + shift, x1, x2)
                    n++
                    y += 2
                }
                if (n > 0 && sum / n < best) {
                    best = sum / n
                    at = shift
                }
            }
            return best to at
        }
        // Columns [x1]..[x2] of the strip, rows [rows] of the picture
        // counted from [at], from the rows [shift] further down in [from].
        fun paint(rows: IntRange, from: IntArray, shift: Int, at: Int, x1: Int, x2: Int) {
            val left = (x1 - FLOATER_PAD).coerceAtLeast(0)
            val width = (x2 + FLOATER_PAD).coerceAtMost(strip) - left
            for (row in rows) {
                if (row + shift < 0 || row + shift >= h || at + row < 0 || at + row >= out.height) continue
                out.setPixels(from, (row + shift) * strip + left, strip, x0 + left, at + row, width, 1)
            }
        }

        val unsettled = ArrayList<Patch>()
        var y = first
        while (y < end) {
            if (rowDiff(low, y, up, y + d) <= FLOATER_DIFF) {
                y++
                continue
            }
            // A run of rows that disagree, small gaps included.
            var to = y + 1
            var gap = 0
            while (to < end && gap <= FLOATER_GAP) {
                if (rowDiff(low, to, up, to + d) > FLOATER_DIFF) gap = 0 else gap++
                to++
            }
            to -= gap
            val from = y
            y = to
            if (to - from < FLOATER_MIN_ROWS || to - from > h / FLOATER_MAX_SHARE) continue
            // Above the join the picture is [upper]'s: nothing of [lower]
            // there to paint over.
            if (to <= match.seam) continue
            val a = (from - FLOATER_PAD).coerceAtLeast(first)
            val b = (to + FLOATER_PAD).coerceAtMost(end)
            // The columns the two disagree in: the thing itself, with as
            // little as possible of the list beside it, which would drown
            // out everything looked for below.
            var x1 = strip
            var x2 = 0
            for (x in 0 until strip step 2) {
                var sum = 0
                var n = 0
                var row = from
                while (row < to) {
                    sum += kotlin.math.abs(
                        brightness(low[row * strip + x]) - brightness(up[(row + d) * strip + x])
                    )
                    n++
                    row += 2
                }
                if (sum / n > FLOATER_DIFF) {
                    if (x < x1) x1 = x
                    x2 = minOf(x + 2, strip)
                }
            }
            if (x2 - x1 < FLOATER_MIN_COLS) continue
            // A handle is a narrow thing at the edge. What reaches across
            // the strip's inner edge is a bar across the screen — a toolbar
            // that was there in one frame and had slid away in the next —
            // and the other frame's picture of these rows is a picture of
            // that bar, not of what a handle hides.
            if (x1 < FLOATER_INSET) continue
            // And it is something to look at: a patch of plain background
            // has a twin wherever there is more plain background.
            fun contrast(of: IntArray, shift: Int): Int {
                var darkest = 255
                var brightest = 0
                for (row in from + shift until to + shift step 2) {
                    for (x in x1 until x2 step 2) {
                        val v = brightness(of[row * strip + x])
                        if (v < darkest) darkest = v
                        if (v > brightest) brightest = v
                    }
                }
                return brightest - darkest
            }
            if (contrast(low, 0) < FLOATER_CONTRAST) continue
            // Is it [lower] that has something here that [upper] has at
            // the same height too — and [upper], [d] rows further down,
            // clearly nothing of the kind? Either because what it has
            // there is plain background, or because that is found nowhere
            // near the same height in [lower]. (In a list of rows that all
            // look the same, every row is every other's twin at either
            // height, and what would be painted in from [upper] is as
            // likely as not something floating over it: the player bar
            // above a music app's tabs went into the picture that way.)
            val (inLower, shift) = unlike(low, upAsTaken, from, to, x1, x2)
            val (inUpper, _) = unlike(upAsTaken, low, from + d, to + d, x1, x2)
            val upperIsPlain = contrast(up, d) < FLOATER_CONTRAST
            val lowerHasIt = inLower <= FLOATER_ALIKE &&
                (upperIsPlain || (inUpper >= FLOATER_UNLIKE && inLower * 10 <= inUpper * FLOATER_CLEARLY))
            Log.d(
                TAG,
                "capture more:   edge rows $from..$to cols $x1..$x2: unlike its twin by " +
                    "$inLower (new part) / $inUpper (part before)"
            )
            when {
                lowerHasIt -> {
                    // In the picture from [lower], from the join down: show
                    // what [upper] has there.
                    paint(maxOf(a, match.seam) until b, up, d, lowerAt - match.seam, x1, x2)
                    Log.d(TAG, "capture more:   handle in the new part, painted over")
                    // And where [upper] has it: left alone a step ago, for
                    // want of anything to know it by.
                    if (before != null) {
                        for (old in before.unsettled) {
                            if (old.rows.first > to + shift + FLOATER_PAD ||
                                old.rows.last < from + shift - FLOATER_PAD
                            ) {
                                continue
                            }
                            paint(
                                maxOf(old.rows.first - FLOATER_PAD, before.seam)..old.rows.last + FLOATER_PAD,
                                before.strip, before.offset, upperAt, old.x1, old.x2
                            )
                            Log.d(TAG, "capture more:   and at rows ${old.rows} of the part before, where it first showed")
                        }
                    }
                }
                // Unclear — or new: a handle that has only just appeared
                // has no twin yet.
                else -> unsettled += Patch(from until to, x1, x2)
            }
        }
        // (Only going down: going up, the frame a handle first shows in is
        // joined on before the one that could tell, not after.)
        if (going == Direction.DOWN) olderEdge = OlderEdge(up, low, d, match.seam, unsettled)
    }

    /** What [removeEdgeFloaters] keeps for one step: the edge of the frame
     *  before the last ([strip]) and of the last as it was taken ([lower]),
     *  how far the last had moved on, where the two were joined, and the
     *  rows of the last that disagreed at the edge without it being clear
     *  why. */
    private class OlderEdge(
        val strip: IntArray, val lower: IntArray, val offset: Int, val seam: Int,
        val unsettled: List<Patch>
    )

    /** Rows of a frame and columns of its edge strip. */
    private class Patch(val rows: IntRange, val x1: Int, val x2: Int)

    private var olderEdge: OlderEdge? = null

    /** [bitmap] as the stitcher wants it: per row, the brightness at
     *  [SAMPLE_COLS] places across. */
    fun signatureOf(bitmap: Bitmap): FrameSignature {
        val w = bitmap.width
        val h = bitmap.height
        // Not the outer edges: a scrollbar shows up there for as long as the
        // list moves and a little after, and is not part of the content.
        val xs = IntArray(SAMPLE_COLS) {
            (w * (SAMPLE_INSET + (1 - 2 * SAMPLE_INSET) * (it + 0.5f) / SAMPLE_COLS)).toInt()
                .coerceIn(0, w - 1)
        }
        val cells = IntArray(h * SAMPLE_COLS)
        val darkest = IntArray(h)
        val brightest = IntArray(h)
        val rows = IntArray(w * ROWS_AT_ONCE)
        var y = 0
        while (y < h) {
            val count = minOf(ROWS_AT_ONCE, h - y)
            bitmap.getPixels(rows, 0, w, 0, y, w, count)
            for (r in 0 until count) {
                val base = (y + r) * SAMPLE_COLS
                for (c in 0 until SAMPLE_COLS) {
                    cells[base + c] = brightness(rows[r * w + xs[c]])
                }
                var lo = 255
                var hi = 0
                for (x in xs.first()..xs.last()) {
                    val v = brightness(rows[r * w + x])
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                darkest[y + r] = lo
                brightest[y + r] = hi
            }
            y += count
        }
        return FrameSignature(h, SAMPLE_COLS, cells, darkest, brightest)
    }

    private fun brightness(pixel: Int): Int =
        (((pixel shr 16) and 0xFF) * 30 + ((pixel shr 8) and 0xFF) * 59 + (pixel and 0xFF) * 11) / 100

    companion object {
        // A hardware canvas refuses to draw a bitmap of 100 MB or more, and
        // the crop tool draws this one.
        const val MAX_BYTES = 96L * 1024 * 1024

        private const val TAG = "CircleToShare"

        // What counts as a handle floating at the edge of a list (see
        // removeEdgeFloaters): how much of the width is looked at; how
        // different two rows must be there to disagree and how alike to be
        // the same thing (average brightness, of 255); how small and (as a
        // share 1/n of the frame) how tall such a thing may be; how far
        // (1/n of the frame) it may move on screen between two frames; and
        // how many rows around it are painted over with it, for its shadow.
        private const val EDGE_STRIP = 0.16f
        private const val FLOATER_DIFF = 10
        private const val FLOATER_MIN_COLS = 8

        // How far in from the strip's inner edge a handle begins at the
        // least, and how much its brightest and darkest pixels differ.
        private const val FLOATER_INSET = 4
        private const val FLOATER_CONTRAST = 40

        // A frame has the handle if its patch is no more unlike its twin
        // in the other frame than this (average brightness, of 255), and
        // at most this many tenths as unlike as the other frame's is.
        private const val FLOATER_ALIKE = 30
        private const val FLOATER_CLEARLY = 5

        // And only if the other frame's patch is at least this unlike its
        // own twin: no handle there, for certain.
        private const val FLOATER_UNLIKE = 30
        private const val FLOATER_GAP = 4
        private const val FLOATER_MIN_ROWS = 16
        private const val FLOATER_MAX_SHARE = 5
        private const val FLOATER_REACH = 6
        private const val FLOATER_PAD = 16

        private const val SAMPLE_COLS = 64
        private const val SAMPLE_INSET = 0.03f
        private const val ROWS_AT_ONCE = 64
    }
}
