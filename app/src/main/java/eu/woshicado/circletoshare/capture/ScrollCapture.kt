package eu.woshicado.circletoshare.capture

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.crop.SnapBounds
import java.util.concurrent.Executors

/**
 * "Capture more": a capture that grows, one step at a time. Each [step]
 * scrolls the app on screen part of a page — by a drag injected as a finger
 * would make it — takes another screenshot, and joins what is new in it to
 * the picture so far ([bitmap]) — at its lower end or its upper one, as
 * asked; a chat opens at its newest message and has nothing further down.
 *
 * Nothing of this app's may be on screen during a step: it would take the
 * drag meant for the app underneath, and be in the screenshot.
 *
 * EXPERIMENTAL. What it cannot do, among other things: tell a bar that
 * lets the list show through from the list (it is repeated at every join),
 * wait for pictures that load late, scroll sideways.
 */
internal class ScrollCapture(
    private val service: ScreenshotAccessibilityService,
    private val ui: Handler
) {

    enum class Outcome {
        /** [bitmap] is longer than it was. */
        EXTENDED,

        /** Longer, but the join was placed by the distance asked for rather
         *  than by recognising the picture — it may be off. */
        UNSURE,

        /** Nothing moved: the end of the content, or nothing that scrolls. */
        END,

        /**
         * Nothing moved, and the app shows this service next to nothing of
         * itself: one that keeps services that are no accessibility tools
         * out (see [scroll]). It cannot be scrolled from here.
         */
        BLOCKED,

        /** At the size limit; nothing was scrolled. */
        TOO_LONG,

        /** A screenshot or the drag failed. [bitmap] is what it was (null if
         *  not even the first screenshot came). */
        FAILED
    }

    // The joining of the frames, apart from everything to do with getting
    // them. Main thread, but for its join().
    private val picture = ScrollPicture(
        removeHandles = { Prefs.isCaptureMoreHandleRemovalEnabled(service, ownSettingsOf) }
    )

    // The app this run is of, if it is one with settings of its own (see
    // Prefs.setAppOverride): looked up as the run starts, on the main
    // thread, and read from the worker's too.
    @Volatile
    private var ownSettingsOf: String? = null

    /** The app whose list is about to be scrolled — in split screen that
     *  need not be the one in front — or, where no list is found, the one
     *  in front; null if that app has no settings of its own. */
    private fun appWithOwnSettings(): String? {
        // (No looking at the screen while no app has any.)
        val apps = Prefs.getAppOverrides(service)
        if (apps.isEmpty()) return null
        val scrolled = findScrollRegion(displayBounds())?.node?.packageName?.toString()
        return (scrolled ?: service.frontAppPackage())?.takeIf { it in apps }
    }

    /** The picture so far; null until the first step has its first frame. */
    val bitmap: Bitmap? get() = picture.bitmap

    /** The way this run has grown, once it has: only that way can it go on. */
    val direction: Direction? get() = picture.direction

    /** What to snap to in [bitmap]; null if snapping is off or there is
     *  nothing. */
    fun snapBounds(): SnapBounds? = picture.snapBounds()


    // What the app reported scrolling by while the last drag was under way:
    // a second witness for the join (and, in the log, whether a drag that
    // moved nothing was ignored, or taken and undone). Main thread.
    private var scrollEvents = 0
    private var scrolledBy = 0

    /** The app on screen reported a scroll (see the service's event types). */
    fun noteScrolled(deltaY: Int) {
        scrollEvents++
        if (deltaY != -1) scrolledBy += deltaY
    }

    /** Which way to grow the picture: towards what is further down the
     *  list (the finger drags up), or towards what is further up. */
    enum class Direction { DOWN, UP }

    /**
     * Grow the picture by one step, [going] up or down. [alive] is asked
     * before every stage: once it says no, the step stops where it is and
     * [onDone] is never called. [onDone] runs on the main thread, as this
     * must be called.
     */
    fun step(going: Direction, alive: () -> Boolean, onDone: (Outcome) -> Unit) {
        if (bitmap != null) return scroll(going, alive, onDone)
        ownSettingsOf = appWithOwnSettings()
        Log.d(
            TAG,
            "capture more: settings of ${ownSettingsOf ?: "every app"}: " +
                "step ${Prefs.getCaptureMoreStep(service, ownSettingsOf)}%, " +
                "handles removed ${Prefs.isCaptureMoreHandleRemovalEnabled(service, ownSettingsOf)}, " +
                "snap ${Prefs.isSnapEnabled(service, ownSettingsOf)}"
        )
        // The first step starts from a screenshot of its own, not the one
        // the crop tool was showing: that one may be seconds old — or, from
        // the assistant, taken by the system before its zoom animation — and
        // the next frame has to line up with it row for row.
        shoot(alive) { first ->
            if (first == null) return@shoot onDone(Outcome.FAILED)
            val firstElements = elementsOnScreen(first)
            worker.execute {
                val signature = runCatching { picture.signatureOf(first) }.getOrNull()
                ui.post {
                    if (!alive()) return@post
                    if (signature == null) return@post onDone(Outcome.FAILED)
                    picture.start(first, signature, firstElements)
                    scroll(going, alive, onDone)
                }
            }
        }
    }

    private fun scroll(going: Direction, alive: () -> Boolean, onDone: (Outcome) -> Unit) {
        if (direction.let { it != null && it != going }) return onDone(Outcome.END)
        // A list that says it has nothing further this way, and something
        // the other, is believed: no need to drag at it to find out.
        val region = findScrollRegion(displayBounds())
        val other = if (going == Direction.DOWN) Direction.UP else Direction.DOWN
        if (region != null && !region.canGo(going) && region.canGo(other)) {
            Log.d(TAG, "capture more: the list says it is at its end going $going")
            return onDone(Outcome.END)
        }
        // Up only where something says there is an up to go to: dragged
        // down at its top, many a list reloads instead (pull to refresh),
        // and on a screen that says nothing there is no telling.
        if (going == Direction.UP && (region == null || !region.canGoUp)) {
            Log.d(TAG, "capture more: nothing on screen says it can go up")
            return onDone(Outcome.END)
        }
        scrollOnce(going, 0, region, alive) { outcome ->
            if (outcome != Outcome.END) return@scrollOnce onDone(outcome)
            // Nothing moved. That is the end of the list — or a drag that
            // didn't get through (seen in a chat, cause unknown: the same
            // drag a second later did). One more, from another spot, before
            // saying so.
            Log.d(TAG, "capture more: nothing moved going $going, once more from another spot")
            scrollOnce(going, 1, null, alive) { again ->
                // An app can mark its views as for accessibility tools only
                // (View.setAccessibilityDataSensitive, or by filtering
                // touches when obscured). A service that is none — this one
                // — is then shown none of them and its gestures are dropped:
                // GitHub's whole screen is like that. Seen from here: a
                // window with nothing in it that won't move.
                val hidden = lastWalkNodes <= HIDDEN_NODES && lastWalkScrollables == 0
                onDone(if (again == Outcome.END && hidden) Outcome.BLOCKED else again)
            }
        }
    }

    // What the last look at the screen's elements found (see findScrollRegion).
    private var lastWalkNodes = 0
    private var lastWalkScrollables = 0

    private fun displayBounds(): Rect =
        service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            .let { Rect(0, 0, it.width(), it.height()) }

    /** [attempt]: 0 for the usual drag, 1 for one placed elsewhere.
     *  [known]: the list on screen, if it has just been looked for. */
    private fun scrollOnce(
        going: Direction, attempt: Int, known: Region?, alive: () -> Boolean, onDone: (Outcome) -> Unit
    ) {
        val soFar = bitmap ?: return onDone(Outcome.FAILED)
        val metrics = service.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val display = displayBounds()
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val found = known ?: findScrollRegion(display)
        val screen = Rect(0, bars.top, display.width(), display.height() - bars.bottom)
        // The second attempt goes for the whole screen where what was found
        // is only a small part of it: some apps tell of a side sheet or a
        // carousel and not of the list itself.
        val small = found != null && found.bounds.height() < display.height() * SMALL_REGION
        val region = if (attempt > 0 && small) screen else found?.bounds ?: screen
        val travel = (region.height() * Prefs.getCaptureMoreStep(service, ownSettingsOf) / 100f).toInt()
        // Screenshots are in display pixels on every device seen so far, but
        // nothing promises it.
        val scale = picture.frameHeight.toFloat() / display.height()
        if (picture.full ||
            (soFar.height + travel * scale).toLong() * soFar.width * 4 > ScrollPicture.MAX_BYTES
        ) {
            return onDone(Outcome.TOO_LONG)
        }
        if (travel < MIN_TRAVEL_PX) return onDone(Outcome.END)

        // Centered on the list: a drag from near its bottom edge starts on
        // bottom bars and floating buttons in many apps, or in the system's
        // own gesture zone. The finger goes the other way than the picture
        // grows.
        val sign = if (going == Direction.DOWN) -1 else 1
        // The second attempt: off to one side, and begun nearer the middle,
        // in case it was what lay under the first one's start that took it.
        val x = if (attempt == 0) region.exactCenterX() else region.left + region.width() * RETRY_X
        val center = region.exactCenterY() + if (attempt == 0) 0f else sign * region.height() * RETRY_SHIFT
        val from = center - sign * travel / 2f
        val end = from + sign * travel
        Log.d(
            TAG,
            "capture more: going $going, drag x=$x y=$from..$end in $region " +
                "(${found?.source ?: "no scrollable found: screen"})"
        )
        scrollEvents = 0
        scrolledBy = 0
        drag(x, from, end - sign * CREEP_PX, end) { dragged ->
            Log.d(
                TAG,
                "capture more: drag ${if (dragged) "completed" else "cancelled"}, " +
                    "app reported $scrollEvents scrolls by $scrolledBy px so far"
            )
            if (!alive()) return@drag
            if (!dragged) return@drag onDone(Outcome.FAILED)
            ui.postDelayed({
                if (!alive()) return@postDelayed
                shoot(alive) { frame ->
                    if (frame == null) return@shoot onDone(Outcome.FAILED)
                    // Lists take up the first few pixels of a drag before
                    // they start to move.
                    val slop = ViewConfiguration.get(service).scaledTouchSlop
                    val expected = ((travel - slop) * scale).toInt()
                    val reported = (kotlin.math.abs(scrolledBy) * scale).toInt()
                    val elements = elementsOnScreen(frame)
                    val minTop = (maxOf(bars.top, region.top) * scale).toInt()
                    val minBottom = (bars.bottom * scale).toInt()
                    worker.execute {
                        val joined = try {
                            picture.join(going, frame, expected, reported, minTop, minBottom)
                        } catch (t: Throwable) {
                            // Out of memory, most likely: the picture stays
                            // as long as it was.
                            Log.w(TAG, "capture more: join failed", t)
                            null
                        }
                        ui.post {
                            if (!alive()) return@post
                            if (joined == null) return@post onDone(Outcome.FAILED)
                            onDone(picture.commit(going, joined, elements))
                        }
                    }
                }
            }, SETTLE_MS)
        }
    }

    /** The elements on screen now, in the frame's pixels; null if snapping
     *  is off. For a frame just taken — the app must still be showing it. */
    private fun elementsOnScreen(frame: Bitmap): List<IntArray>? {
        if (!Prefs.isSnapEnabled(service, ownSettingsOf)) return null
        val snap = service.collectSnapBounds() ?: return emptyList()
        val sx = frame.width / snap.screenWidth.toFloat()
        val sy = frame.height / snap.screenHeight.toFloat()
        return snap.rects.map {
            intArrayOf(
                (it.left * sx).toInt(), (it.top * sy).toInt(),
                (it.right * sx).toInt(), (it.bottom * sy).toInt()
            )
        }
    }

    /**
     * Take a screenshot, no sooner after the last one than the system allows
     * (it turns down requests that come faster). Null if it failed.
     */
    private fun shoot(alive: () -> Boolean, onResult: (Bitmap?) -> Unit) {
        // (Counted from the service's last, whoever asked for it: the shot
        // the crop tool opened with may be a moment old.)
        val wait = service.lastScreenshotUptime + SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        ui.postDelayed({
            if (!alive()) return@postDelayed
            service.capture { bitmap -> ui.post { if (alive()) onResult(bitmap) } }
        }, wait.coerceAtLeast(0L))
    }

    /**
     * Drag a finger at [x] from [from] to [to], let it creep on to [end],
     * lift.
     *
     * The creep is the point. Lifted while moving, the drag is a flick: the
     * list keeps going by itself, by however much it likes, and is still
     * moving when the screenshot is taken. Lifted from a near standstill, it
     * stops where the finger did — the distance is then known beforehand,
     * near enough, which is what the joining goes by.
     *
     * A creep, not a hold: a finger that doesn't move at all sends nothing
     * (the system injects a move only where the position changed), and a
     * list that hears nothing goes by the last speed it measured. ScrollView
     * does — it flung on by half the drag again after a motionless hold —
     * and so does whatever else measures speed for itself. Crawling, the
     * finger keeps reporting how slow it is.
     */
    private fun drag(x: Float, from: Float, to: Float, end: Float, onDone: (Boolean) -> Unit) {
        val move = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, from); lineTo(x, to) }, 0, DRAG_MS, true
        )
        val hold = move.continueStroke(
            Path().apply { moveTo(x, to); lineTo(x, end) }, 0, CREEP_MS, false
        )
        val lifted = object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) = onDone(true)
            override fun onCancelled(gesture: GestureDescription?) = onDone(false)
        }
        val moved = object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                val sent = service.dispatchGesture(
                    GestureDescription.Builder().addStroke(hold).build(), lifted, ui
                )
                if (!sent) onDone(false)
            }

            override fun onCancelled(gesture: GestureDescription?) = onDone(false)
        }
        val sent = service.dispatchGesture(
            GestureDescription.Builder().addStroke(move).build(), moved, ui
        )
        if (!sent) onDone(false)
    }

    /**
     * Where on screen the list to scroll is: the largest thing that scrolls
     * up and down in the app windows. Null if nothing says it does — games,
     * and toolkits that tell accessibility services little.
     *
     * The largest, not the first: a page often has small things in it that
     * scroll too.
     */
    /** The list to drag at: where it is, which ways it or a list inside or
     *  around it says it can still go (both false if none says), and what
     *  it is, for the log. */
    private class Region(
        val bounds: Rect, val canGoDown: Boolean, val canGoUp: Boolean, val source: String,
        // The list itself: which app's it is (see appWithOwnSettings).
        val node: AccessibilityNodeInfo? = null
    ) {
        fun canGo(way: Direction) = if (way == Direction.DOWN) canGoDown else canGoUp
    }

    private fun findScrollRegion(display: Rect): Region? {
        var best: Rect? = null
        var bestNode: AccessibilityNodeInfo? = null
        var source = ""
        // Which ways any of the lists found can go, not just the largest:
        // a screen is often a list inside a frame that scrolls a little
        // itself (to fold a header away), and once that frame is at its end
        // the list inside is far from it.
        var canGoDown = false
        var canGoUp = false
        var windowCount = 0
        var scrollables = 0
        var budget = MAX_NODES
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0) return
            if (node.isScrollable && node.isVisibleToUser && scrollsVertically(node)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                scrollables++
                Log.d(
                    TAG,
                    "capture more:   scrollable ${node.className} $r depth=$depth " +
                        "actions=${node.actionList.map { it.id }.filter { it in SCROLL_ACTION_IDS }}" +
                        if (android.os.Build.VERSION.SDK_INT >= 35) " granular=${node.isGranularScrollingSupported}" else ""
                )
                if (r.intersect(display) && r.height() >= display.height() / 4) {
                    val actions = node.actionList
                    if (AccessibilityAction.ACTION_SCROLL_FORWARD in actions ||
                        AccessibilityAction.ACTION_SCROLL_DOWN in actions
                    ) {
                        canGoDown = true
                    }
                    if (AccessibilityAction.ACTION_SCROLL_BACKWARD in actions ||
                        AccessibilityAction.ACTION_SCROLL_UP in actions
                    ) {
                        canGoUp = true
                    }
                    if (best.let { it == null || r.width() * r.height() > it.width() * it.height() }) {
                        best = r
                        bestNode = node
                        source = "${node.className} in ${node.packageName}"
                    }
                }
            }
            if (depth >= MAX_DEPTH) return
            for (i in 0 until node.childCount) {
                if (budget <= 0) return
                walk(node.getChild(i) ?: continue, depth + 1)
            }
        }
        for (window in service.appWindows()) {
            windowCount++
            walk(window.root ?: continue, 0)
        }
        Log.d(
            TAG,
            "capture more: $windowCount app windows, $scrollables scrollables, " +
                "${MAX_NODES - budget} nodes visited"
        )
        lastWalkNodes = MAX_NODES - budget
        lastWalkScrollables = scrollables
        val bounds = best ?: return null
        // Cut short, the walk may have missed the list that can still go:
        // then nothing is known about where the ends are.
        val complete = budget > 0
        return Region(bounds, canGoDown || !complete, canGoUp || !complete, source, bestNode)
    }

    /** A node that says which way it scrolls is believed; one that doesn't
     *  (older toolkits only say "forward" and "backward") counts. */
    private fun scrollsVertically(node: AccessibilityNodeInfo): Boolean {
        val actions = node.actionList
        if (AccessibilityAction.ACTION_SCROLL_DOWN in actions ||
            AccessibilityAction.ACTION_SCROLL_UP in actions
        ) {
            return true
        }
        return AccessibilityAction.ACTION_SCROLL_LEFT !in actions &&
            AccessibilityAction.ACTION_SCROLL_RIGHT !in actions
    }

    private companion object {
        const val TAG = "CircleToShare"

        // One at a time, in order.
        val worker = Executors.newSingleThreadExecutor { Thread(it, "capture-more") }

        const val MIN_TRAVEL_PX = 48

        // Where a second attempt is placed: a quarter of the way across,
        // and shifted by this share of the list's height.
        const val RETRY_X = 0.25f
        const val RETRY_SHIFT = 0.12f

        // An app window showing no more elements than this shows nothing.
        const val HIDDEN_NODES = 3

        // A list taking up less of the screen's height than this is small.
        const val SMALL_REGION = 0.6f

        const val DRAG_MS = 450L

        // The crawl before the finger lifts: long enough to be all a list
        // remembers of the drag (they look back about 100 ms), and far too
        // slow to count as a flick anywhere.
        const val CREEP_PX = 6f
        const val CREEP_MS = 250L

        // After the finger lifts: for the list to draw where it ended up,
        // and an overscroll stretch to spring back.
        const val SETTLE_MS = 350L

        // The system turns down a screenshot within 333 ms of the last
        // (a whole second on Android 11).
        val SHOT_INTERVAL_MS = if (android.os.Build.VERSION.SDK_INT >= 31) 400L else 1100L

        val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id
        )

        // As in the snap-bounds walk: node access is IPC.
        const val MAX_NODES = 400
        const val MAX_DEPTH = 25
    }
}
