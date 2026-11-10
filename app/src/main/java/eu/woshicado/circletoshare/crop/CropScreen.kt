package eu.woshicado.circletoshare.crop

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnLayout
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.qr.QrDetector
import eu.woshicado.circletoshare.qr.qrOpenUri
import eu.woshicado.circletoshare.share.Delivery
import kotlin.math.ceil

/**
 * The full crop overlay UI — frozen screenshot, draggable crop rectangle, and
 * the Cancel-or-Save/Copy/Share pill bar. Shared by both the assistant session and the
 * floating-bubble path so the two behave identically.
 */
class CropScreen(context: Context) : FrameLayout(context) {

    interface Callbacks {
        /** [rect] is in bitmap coordinates, or null to share the whole screen. */
        fun onDeliver(action: Delivery, rect: Rect?)
        fun onCancel()
    }

    var callbacks: Callbacks? = null

    /**
     * What Back does, for a host whose window gets no Back handling of its
     * own (a bare overlay window; the assistant session's dialog has its own
     * and leaves this unset).
     */
    var onBackKey: (() -> Unit)? = null

    /** The two ways to capture more: what is further up the list on screen,
     *  or further down. */
    enum class More { ABOVE, BELOW, LEFT, RIGHT }

    /**
     * EXPERIMENTAL. What the "Capture more" buttons do, for a host that can
     * scroll the app underneath and come back with a longer picture; they
     * are only there while this is set.
     */
    var onCaptureMore: ((More) -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    /** Which of the buttons there is anything behind: the up-and-down pair,
     *  or (a sideways run) the left-and-right pair. */
    var captureMoreChoices: Set<More> = setOf(More.ABOVE, More.BELOW)
        set(value) {
            field = value
            updateMoreButton()
        }

    /**
     * EXPERIMENTAL. What the "↔" button does, for a host that can find the
     * parts of the screen that scroll sideways and grow a picture of one:
     * the button is there, between "More above" and "More below", while
     * this is set and no run has begun.
     */
    var onCaptureSideways: (() -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    private val cropView = CropOverlayView(context)
    private val hintView: TextView
    private val buttonBar: ActionRow
    // Cancel while nothing is selected, Save once something is.
    private val dismissButton: TextView
    private val shareButton: TextView
    private val moreRow: ActionRow
    private val moreAboveButton: TextView
    private val moreSidewaysButton: TextView
    private val moreBelowButton: TextView
    private val sideRow: ActionRow
    private val moreLeftButton: TextView
    private val moreRightButton: TextView
    private val areaPicker: AreaPickerView
    private val qrPanel: LinearLayout
    private val qrPanelText: TextView
    private val qrPanelError: TextView
    private val qrOpenButton: TextView

    // Saved crop offered by the tappable hint, or null when none is available
    // for the current capture.
    private var pendingRestore: Rect? = null
    private var scrollshot = false

    // The capture currently on screen — identity-checked when an async QR scan
    // finishes, so a result for a dismissed or replaced capture is dropped.
    private var currentBitmap: android.graphics.Bitmap? = null

    // The tapped code whose actions the panel is showing.
    private var activeQr: QrCode? = null

    private var hasSelection = false

    // The QR scan of [currentBitmap], while it runs.
    private var qrScan: QrDetector.Scan? = null

    init {
        cropView.visibility = View.GONE
        cropView.onZoomChanged = { areaPicker.invalidate() }
        cropView.listener = object : CropOverlayView.Listener {
            override fun onSelectionChanged(hasSelection: Boolean) {
                showSelectionActions(hasSelection)
                showPicking()
            }

            override fun onDoubleTapInside() = deliver(Delivery.SHARE)
            override fun onLongPressInside() = deliver(Delivery.COPY)
            override fun onDoubleTapEmpty() {
                callbacks?.onCancel()
            }

            override fun onQrTapped(code: QrCode) = showQrPanel(code)
        }
        addView(
            cropView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        hintView = TextView(context).apply {
            text = context.getString(R.string.crop_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = pill(Color.argb(200, 30, 30, 30), 24, outlined = true)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            compoundDrawablePadding = dp(ICON_PADDING_DP)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(
            hintView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(64)
                // Keep a long text (or a large font) off the screen's edges.
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        )

        buttonBar = ActionRow(context).apply { visibility = View.GONE }
        dismissButton = pillButton(
            context.getString(R.string.action_cancel), R.drawable.ic_close, filled = false
        ) {
            if (hasSelection) deliver(Delivery.SAVE) else callbacks?.onCancel()
        }
        val copy = pillButton(
            context.getString(R.string.action_copy), R.drawable.ic_copy, filled = false
        ) {
            deliver(Delivery.COPY)
        }
        shareButton = pillButton(
            context.getString(R.string.action_share_full), R.drawable.ic_share, filled = true
        ) {
            deliver(Delivery.SHARE)
        }
        buttonBar.addAction(
            dismissButton,
            context.getString(R.string.action_cancel), context.getString(R.string.action_save)
        )
        buttonBar.addAction(copy)
        // (Both of its labels: the row must not re-arrange itself every time
        // a selection comes or goes.)
        buttonBar.addAction(
            shareButton,
            context.getString(R.string.action_share), context.getString(R.string.action_share_full)
        )
        addView(
            buttonBar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(BAR_BOTTOM_MARGIN_DP) }
        )

        // A row of their own above the bar, not more buttons in it: the
        // bar's three are a tight fit already.
        moreAboveButton = pillButton(
            context.getString(R.string.action_capture_more_above), R.drawable.ic_arrow_upward,
            filled = false
        ) {
            onCaptureMore?.invoke(More.ABOVE)
        }
        moreBelowButton = pillButton(
            context.getString(R.string.action_capture_more_below), R.drawable.ic_arrow_downward,
            filled = false
        ) {
            onCaptureMore?.invoke(More.BELOW)
        }
        // Between the two, the icon alone: what it does is a mode, not a
        // step, and a word for it would make the row too wide for one line.
        moreSidewaysButton = pillButton("", R.drawable.ic_swap_horiz, filled = false) {
            onCaptureSideways?.invoke()
        }.apply {
            contentDescription = context.getString(R.string.action_capture_sideways)
            compoundDrawablePadding = 0
            setPaddingRelative(dp(14), dp(13), dp(14), dp(13))
        }
        moreRow = ActionRow(context).apply {
            visibility = View.GONE
            addAction(moreAboveButton)
            addAction(moreSidewaysButton)
            addAction(moreBelowButton)
        }
        val moreRowParams = {
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(BAR_BOTTOM_MARGIN_DP + 48 + MORE_GAP_DP) }
        }
        addView(moreRow, moreRowParams())

        // A sideways run has a row of its own in the same place.
        moreLeftButton = pillButton(
            context.getString(R.string.action_capture_more_left), R.drawable.ic_arrow_back,
            filled = false
        ) {
            sideways(More.LEFT)
        }
        moreRightButton = pillButton(
            context.getString(R.string.action_capture_more_right), R.drawable.ic_arrow_forward,
            filled = false
        ) {
            sideways(More.RIGHT)
        }
        sideRow = ActionRow(context).apply {
            visibility = View.GONE
            addAction(moreLeftButton)
            addAction(moreRightButton)
        }
        addView(sideRow, moreRowParams())

        // Over the picture while a row is being chosen (see pickArea).
        areaPicker = AreaPickerView(context).apply { visibility = View.GONE }
        addView(areaPicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Actions for a tapped QR code: the payload plus Copy/Open pills.
        // Sits above the main button bar; dismissed by tapping anywhere else.
        qrPanelText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        // Why a tapped code could not be opened — shown here rather than in a
        // toast, which is drawn beneath this tool's window where nobody sees
        // it.
        qrPanelError = TextView(context).apply {
            text = context.getString(R.string.qr_open_failed)
            // Material's error tone for dark surfaces.
            setTextColor(Color.rgb(255, 180, 171))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
            // Spoken by a screen reader when it appears.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        qrOpenButton = pillButton(
            context.getString(R.string.qr_open), R.drawable.ic_open_in_new, filled = true
        ) {
            activeQr?.let { openQr(it.text) }
        }
        val qrCopyButton = pillButton(
            context.getString(R.string.action_copy), R.drawable.ic_copy, filled = false
        ) {
            activeQr?.let { copyQr(it.text) }
        }
        qrPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Color.argb(235, 30, 30, 30), 20, outlined = true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            // Swallow every touch in the panel's area — without this, a drag
            // starting on the panel body falls through to the crop view and
            // draws a selection behind the (still open) panel.
            isClickable = true
            addView(
                qrPanelText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                qrPanelError,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
            addView(
                ActionRow(context).apply {
                    addAction(qrCopyButton)
                    addAction(qrOpenButton)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL }
            )
        }
        addView(
            qrPanel,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(BAR_BOTTOM_MARGIN_DP + 48 + QR_PANEL_GAP_DP)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The QR panel sits a fixed gap above the button bar — however tall
        // the bar has turned out (a large font size, or stacked buttons).
        // "Capture more" likewise.
        if (buttonBar.visibility == View.GONE) return
        val params = qrPanel.layoutParams as LayoutParams
        val above = dp(BAR_BOTTOM_MARGIN_DP + QR_PANEL_GAP_DP) + buttonBar.measuredHeight
        val moreParams = moreRow.layoutParams as LayoutParams
        val sideParams = sideRow.layoutParams as LayoutParams
        val moreAbove = dp(BAR_BOTTOM_MARGIN_DP + MORE_GAP_DP) + buttonBar.measuredHeight
        if (params.bottomMargin != above || moreParams.bottomMargin != moreAbove ||
            sideParams.bottomMargin != moreAbove
        ) {
            params.bottomMargin = above
            moreParams.bottomMargin = moreAbove
            sideParams.bottomMargin = moreAbove
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /**
     * [scrollshot]: the picture is a "Capture more" picture — longer than
     * the screen, or a sideways strip — and a crop of it is not remembered
     * (see [deliver]): the memory keeps the crop of the screen before.
     */
    fun setBitmap(bitmap: android.graphics.Bitmap, snap: SnapBounds? = null, scrollshot: Boolean = false) {
        this.scrollshot = scrollshot
        // Apply the current crop-gesture preference (freeform circle vs. box).
        val freeform = eu.woshicado.circletoshare.Prefs.isFreeformCrop(context)
        cropView.freeform = freeform
        cropView.snapToEdges = eu.woshicado.circletoshare.Prefs.isSnapToEdgesEnabled(context)

        setBackgroundColor(Color.BLACK)
        setOnClickListener(null)
        isClickable = false
        currentBitmap = bitmap
        qrScan?.cancel()
        qrScan = null
        hideQrPanel()
        // A new capture starts afresh: not in the middle of choosing a row
        // for the one before (this screen is kept from one capture to the
        // next by the assistant session), and with the "↔" button back.
        sidewaysUnavailable = false
        if (pickingArea) leavePicking()
        cropView.setBitmap(bitmap)
        cropView.setSnapBounds(snap)
        // Scan the capture for QR codes off the main thread; the identity
        // check drops the result if the tool was dismissed (or reloaded with
        // a new capture) while the scan ran. Both trigger paths — assistant
        // session and bubble — funnel through here, so this covers them all.
        cropView.setQrHighlightColor(eu.woshicado.circletoshare.Prefs.getQrColor(context))
        if (eu.woshicado.circletoshare.Prefs.isQrDetectEnabled(context)) {
            qrScan = QrDetector.detect(bitmap) { codes ->
                if (currentBitmap === bitmap) cropView.setQrCodes(codes)
            }
        }
        cropView.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        buttonBar.visibility = View.VISIBLE
        updateMoreButton()
        showSelectionActions(false)

        // Offer to restore the last crop only when it fits this exact capture.
        pendingRestore = eu.woshicado.circletoshare.Prefs
            .getLastCropFor(context, bitmap.width, bitmap.height)
        showHint()
    }

    /**
     * Show [message] in place of the tool — for a host that has nothing to
     * crop and nowhere else to say why. A touch anywhere asks the host to
     * close ([Callbacks.onCancel]); with [onTap], a touch on the message
     * itself runs that instead. Undone by [setBitmap] and [clear].
     */
    fun showMessage(message: CharSequence, onTap: (() -> Unit)? = null) {
        // Nothing behind the message — not the black the picture stood on,
        // if there was one: the screen underneath shows through instead.
        background = null
        currentBitmap = null
        qrScan?.cancel()
        qrScan = null
        hideQrPanel()
        cropView.visibility = View.GONE
        buttonBar.visibility = View.GONE
        updateMoreButton()
        hintView.text = message
        hintView.setCompoundDrawablesRelative(null, null, null, null)
        // (In this order — see setBitmap.)
        hintView.setOnClickListener(onTap?.let { tap -> View.OnClickListener { tap() } })
        hintView.isClickable = onTap != null
        hintView.visibility = View.VISIBLE
        setOnClickListener { callbacks?.onCancel() }
    }

    /** Drop the frozen screenshot and hide the UI — the reverse of [setBitmap].
     *  Called when the overlay is dismissed so a reused instance can't flash
     *  the previous session's image. */
    fun clear() {
        sidewaysUnavailable = false
        if (pickingArea) leavePicking()
        background = null
        currentBitmap = null
        qrScan?.cancel()
        qrScan = null
        setOnClickListener(null)
        isClickable = false
        hideQrPanel()
        cropView.clear()
        // Drop the snap lines too — a reused instance must not snap to a
        // previous session's layout.
        cropView.setSnapBounds(null)
        cropView.visibility = View.GONE
        hintView.visibility = View.GONE
        buttonBar.visibility = View.GONE
        updateMoreButton()
    }

    /** The "Capture more" buttons show with the button bar, where there is
     *  something for them to do, and only while nothing is selected: a
     *  longer picture replaces the one the box was drawn on, and the box
     *  with it. They also make way for the QR panel, which opens in the same
     *  place. */
    private fun updateMoreButton() {
        val choices = captureMoreChoices
        val sideways = More.LEFT in choices || More.RIGHT in choices
        // The one there is nothing behind keeps its place, unseen: the other
        // gets tapped again and again, and must not move from under the
        // finger between two taps.
        moreAboveButton.visibility = if (More.ABOVE in choices) View.VISIBLE else View.INVISIBLE
        moreBelowButton.visibility = if (More.BELOW in choices) View.VISIBLE else View.INVISIBLE
        // The way sideways is open while the picture has not grown yet; its
        // place is kept after, like the other's (see above).
        moreSidewaysButton.visibility = when {
            onCaptureSideways == null || sidewaysUnavailable -> View.GONE
            More.ABOVE in choices && More.BELOW in choices -> View.VISIBLE
            else -> View.INVISIBLE
        }
        moreLeftButton.visibility = if (More.LEFT in choices) View.VISIBLE else View.INVISIBLE
        moreRightButton.visibility = if (More.RIGHT in choices) View.VISIBLE else View.INVISIBLE
        val shown = onCaptureMore != null && choices.isNotEmpty() &&
            buttonBar.visibility == View.VISIBLE &&
            !hasSelection && qrPanel.visibility != View.VISIBLE
        // A selection, where no run has begun and a host can grow a strip of
        // it: "More left" and "More right" start one — the row chosen by a
        // tap on an outline (see pickArea) or any box the user drew.
        if (hasSelection && !sideways && onSidewaysRun != null && onCaptureMore != null) {
            moreLeftButton.visibility = View.VISIBLE
            moreRightButton.visibility = View.VISIBLE
            moreRow.visibility = View.GONE
            sideRow.visibility =
                if (buttonBar.visibility == View.VISIBLE && qrPanel.visibility != View.VISIBLE) View.VISIBLE
                else View.GONE
            return
        }
        moreRow.visibility = if (shown && !sideways && !pickingArea) View.VISIBLE else View.GONE
        sideRow.visibility = if (shown && sideways) View.VISIBLE else View.GONE
    }

    /** "More left" or "More right": in a sideways run a step of it; over a
     *  selection, the start of one from it. */
    private fun sideways(more: More) {
        val sidewaysRun = More.LEFT in captureMoreChoices || More.RIGHT in captureMoreChoices
        if (sidewaysRun) return onCaptureMore?.invoke(more) ?: Unit
        val area = cropView.getCropRect() ?: return
        // Remembered like a crop that was shared: the hint offers it back
        // the next time the same screen is captured.
        if (!scrollshot) cropView.getBitmapSize()?.let { (w, h) ->
            eu.woshicado.circletoshare.Prefs.saveLastCrop(context, area, w, h)
        }
        if (pickingArea) leavePicking()
        onSidewaysRun?.invoke(area, more)
    }

    /**
     * EXPERIMENTAL. Outline [areas] — the parts of the picture that scroll
     * sideways, in the picture's pixels — for a tap on one to make it the
     * selection. (A box drawn by hand does as well, with or without this.)
     * With a selection, "More left" and "More right" are offered, and one
     * of them hands the selection and the way to [onSidewaysRun]. Back
     * leaves the outlines.
     */
    fun pickArea(areas: List<Rect>) {
        if (areas.isEmpty()) {
            // Nothing to outline: the tool stays as it is, says so, and
            // keeps the "↔" button away — pressing it again would find no
            // more. A box drawn by hand still has "More left" and "More
            // right".
            sidewaysUnavailable = true
            showPicking()
            return
        }
        pickingArea = true
        areaPicker.show(areas, cropView::mapToView) { cropView.applyCropRect(it) }
        showPicking()
    }

    // The "↔" button found nothing on this capture (see pickArea).
    private var sidewaysUnavailable = false

    private fun leavePicking() {
        pickingArea = false
        areaPicker.hide()
        showPicking()
    }

    /** The hint and the buttons as the choosing has them (see [pickArea]);
     *  or back as they were. */
    private fun showPicking() {
        if (pickingArea) {
            hintView.setText(if (hasSelection) R.string.capture_sideways_go else R.string.capture_sideways_pick)
            // (Not the restore offer's icon and tap, if that was showing.)
            hintView.setCompoundDrawablesRelative(null, null, null, null)
            hintView.setOnClickListener(null)
            hintView.isClickable = false
            hintView.visibility = View.VISIBLE
            // The outlines are for choosing; chosen, the box shows what is.
            areaPicker.visibility = if (hasSelection) View.INVISIBLE else View.VISIBLE
        } else {
            hintView.visibility = if (hasSelection) View.GONE else View.VISIBLE
            showHint()
        }
        updateMoreButton()
    }

    /** The hint over the picture: the saved crop to restore, if one fits,
     *  else how to crop — and that no row scrolls sideways, once the "↔"
     *  button has found none (see [pickArea]). */
    private fun showHint() {
        if (pendingRestore != null) {
            hintView.setText(
                if (sidewaysUnavailable) R.string.capture_sideways_none_restore else R.string.crop_hint_restore
            )
            hintView.setCompoundDrawablesRelative(icon(R.drawable.ic_restore), null, null, null)
            hintView.isClickable = true
            hintView.setOnClickListener { pendingRestore?.let { cropView.applyCropRect(it) } }
        } else {
            hintView.setText(
                when {
                    sidewaysUnavailable -> R.string.capture_sideways_none
                    cropView.freeform -> R.string.crop_hint_freeform
                    else -> R.string.crop_hint
                }
            )
            hintView.setCompoundDrawablesRelative(null, null, null, null)
            // In this order: setOnClickListener makes a view clickable, even
            // when handed null. A clickable hint swallows every touch that
            // starts on it — no crop could begin there, and a QR code
            // underneath couldn't be tapped.
            hintView.setOnClickListener(null)
            hintView.isClickable = false
        }
    }

    /** Whether a row to capture sideways is being chosen (see [pickArea]). */
    var pickingArea: Boolean = false
        private set

    /**
     * EXPERIMENTAL. What "More left" and "More right" do over a selection
     * while no run has begun: the selection, in the picture's pixels, and
     * the way to grow a picture of it. Set, every selection offers them.
     */
    var onSidewaysRun: ((Rect, More) -> Unit)? = null
        set(value) {
            field = value
            updateMoreButton()
        }

    override fun onDetachedFromWindow() {
        // Taken off the screen for good (an overlay window is not reused):
        // nobody is left to show what the scan finds.
        qrScan?.cancel()
        qrScan = null
        super.onDetachedFromWindow()
    }

    /** Make [rect], in the picture's pixels, the selection (see
     *  [CropOverlayView.applyCropRect]): a selection given back after a
     *  sideways run that got nowhere, for the user to adjust and try again. */
    fun applyCropRect(rect: Rect) {
        // (Where the picture lies is only known once this is laid out; a
        // screen just made waits for that — and for the layout to be over:
        // the buttons a selection brings out must not come out in the
        // middle of one, where they would be left unmeasured.)
        if (cropView.isLaidOut) cropView.applyCropRect(rect)
        else cropView.doOnLayout { cropView.post { cropView.applyCropRect(rect) } }
    }

    /** See [CropOverlayView.setDisplayFrame]. */
    fun setDisplayFrame(frame: RectF?) = cropView.setDisplayFrame(frame)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onBack = onBackKey
        if (onBack != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Taken here, ahead of the children: a ViewGroup hands a key to
            // its focused child only, so once keyboard focus sits on one of
            // the buttons, a key listener on this view would never see Back.
            // (Escape arrives here as Back too, as the keyboard's fallback.)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                // Picking an area, Back only leaves the picking.
                if (pickingArea) leavePicking() else onBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // --- QR actions ---------------------------------------------------------

    /** A touch anywhere outside the QR panel dismisses it; the touch still
     *  falls through so it can start a crop gesture (or open another code). */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (qrPanel.visibility == View.VISIBLE &&
            ev.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            val hit = Rect()
            qrPanel.getHitRect(hit)
            if (!hit.contains(ev.x.toInt(), ev.y.toInt())) hideQrPanel()
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun showQrPanel(code: QrCode) {
        activeQr = code
        qrPanelText.text = code.text
        qrPanelError.visibility = View.GONE
        qrOpenButton.visibility = if (qrOpenUri(code.text) != null) View.VISIBLE else View.GONE
        qrPanel.visibility = View.VISIBLE
        updateMoreButton()
    }

    private fun hideQrPanel() {
        activeQr = null
        qrPanel.visibility = View.GONE
        updateMoreButton()
    }

    private fun copyQr(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("qr", text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        callbacks?.onCancel()
    }

    private fun openQr(text: String) {
        val uri = qrOpenUri(text) ?: return
        // Intent filters match schemes case-sensitively, and QR codes often
        // spell theirs in capitals ("HTTPS://…" packs denser) — normalize, or
        // perfectly ordinary links find no app.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri).normalizeScheme())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // The payload is untrusted input and this runs in the process
            // hosting the assistant and accessibility services, so nothing it
            // provokes may escape: no handler (ActivityNotFoundException), a
            // handler we aren't allowed to start (SecurityException), a URI
            // the platform refuses to send (FileUriExposedException)…
            // Said in the panel (see qrPanelError), and Open withdrawn: it
            // would only fail again. Copy remains.
            qrPanelError.visibility = View.VISIBLE
            qrOpenButton.visibility = View.GONE
            return
        }
        callbacks?.onCancel()
    }

    /** The bar's labels (and icons) for a selection present or not. */
    private fun showSelectionActions(selected: Boolean) {
        hasSelection = selected
        updateMoreButton()
        shareButton.text = context.getString(
            if (selected) R.string.action_share else R.string.action_share_full
        )
        dismissButton.text = context.getString(
            if (selected) R.string.action_save else R.string.action_cancel
        )
        dismissButton.setCompoundDrawablesRelative(
            icon(if (selected) R.drawable.ic_save else R.drawable.ic_close), null, null, null
        )
    }

    private fun deliver(action: Delivery) {
        val rect = cropView.getCropRect()
        // Remember an actual selection so it can be restored next time; a
        // full-screen share (null rect) leaves the previous memory untouched,
        // and so does a crop of a scrollshot (its picture is no screen's).
        if (rect != null && !scrollshot) {
            cropView.getBitmapSize()?.let { (w, h) ->
                eu.woshicado.circletoshare.Prefs.saveLastCrop(context, rect, w, h)
            }
        }
        callbacks?.onDeliver(action, rect)
    }

    /** [res] at the size the pills' icons share, ready for a compound
     *  drawable (tinted by the view it goes on). */
    private fun icon(res: Int) = context.getDrawable(res)!!.mutate().apply {
        setBounds(0, 0, dp(ICON_SIZE_DP), dp(ICON_SIZE_DP))
    }

    private fun pillButton(label: String, iconRes: Int, filled: Boolean, onClick: () -> Unit): TextView =
        PillButton(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val content = if (filled) Color.BLACK else Color.WHITE
            setTextColor(content)
            setCompoundDrawablesRelative(icon(iconRes), null, null, null)
            compoundDrawablePadding = dp(ICON_PADDING_DP)
            compoundDrawableTintList = ColorStateList.valueOf(content)
            // Pressed feedback, clipped to the pill's own shape (no mask
            // layer: the ripple is masked by the content beneath it).
            background = RippleDrawable(
                ColorStateList.valueOf(
                    if (filled) Color.argb(48, 0, 0, 0) else Color.argb(64, 255, 255, 255)
                ),
                pill(
                    if (filled) Color.WHITE else Color.argb(230, 45, 45, 45), 26,
                    outlined = !filled
                ),
                null
            )
            gravity = Gravity.CENTER
            minHeight = dp(48)
            // Less on the icon's side, as Material's buttons with an icon.
            setPaddingRelative(dp(18), dp(13), dp(24), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** A rounded surface. [outlined] adds the hairline light edge every dark
     *  surface of the tool carries (the bubble has the same one): without it
     *  dark chrome dissolves into a dark screenshot. */
    private fun pill(color: Int, radiusDp: Int, outlined: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (outlined) setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BAR_BOTTOM_MARGIN_DP = 48
        const val QR_PANEL_GAP_DP = 24
        const val MORE_GAP_DP = 12
        const val ICON_SIZE_DP = 18
        const val ICON_PADDING_DP = 8
    }
}

/**
 * A pill whose leading icon stays beside its label, the two centered as one.
 * A plain TextView pins a compound drawable to its edge and centers only the
 * text — fine while the pill wraps its content, but a stacked [ActionRow]
 * stretches it, which would strand the icon at the far edge.
 *
 * A plain TextView, like the crop screen's other views: it is built in
 * service contexts (assistant session, accessibility overlay) that carry no
 * AppCompat theme, and needs nothing AppCompat would add.
 */
@SuppressLint("AppCompatCustomView")
private class PillButton(context: Context) : TextView(context) {

    // Placed at each draw rather than layout: a new label in a pill of fixed
    // width (stacked) is only redrawn, not laid out again.
    override fun onDraw(canvas: Canvas) {
        placeIcon()
        super.onDraw(canvas)
    }

    private fun placeIcon() {
        val icon = compoundDrawablesRelative[0] ?: return
        // The room beside the label, split evenly: the label is centered in
        // the space after the icon, so the icon moves along by half of it.
        // (Bounds, not padding: moving the drawing needs no new layout.)
        val textArea = width - compoundPaddingLeft - compoundPaddingRight
        val textWidth = ceil(paint.measureText(text.toString())).toInt().coerceAtMost(textArea)
        val shift = (textArea - textWidth) / 2
        val offset = if (layoutDirection == LAYOUT_DIRECTION_RTL) -shift else shift
        val size = icon.bounds.width()
        if (icon.bounds.left != offset) icon.setBounds(offset, 0, offset + size, icon.bounds.height())
    }
}

/**
 * A row of pill buttons, main action last. Where the buttons don't fit side
 * by side with their labels on one line — a narrow screen at a large font or
 * display size — it becomes a stack of equally wide buttons instead, main
 * action first, rather than squeeze the last one into a sliver that breaks
 * its label a letter at a time.
 */
/**
 * The parts of the picture that scroll sideways, outlined over it while a
 * row is being chosen (see CropScreen.pickArea): a tap on one makes it the
 * selection. Every other touch passes through to the crop view, where the
 * user draws and resizes a box as usual. Placed by the crop view's mapping
 * of the picture, so that the outlines lie where the picture is drawn.
 */
private class AreaPickerView(context: Context) : View(context) {

    private var areas: List<Rect> = emptyList()
    private var toView: ((Rect) -> RectF)? = null
    private var onTapped: ((Rect) -> Unit)? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
    private val glyph = context.getDrawable(R.drawable.ic_swap_horiz)!!.mutate().apply {
        setTint(Color.WHITE)
    }
    private val glyphBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 30, 30) }

    // The outline a touch began on, if any: a tap on it picks it, a drag
    // from it is let go of (the crop view did not see its start).
    private var pressed: Rect? = null
    private var downX = 0f
    private var downY = 0f

    fun show(areas: List<Rect>, toView: (Rect) -> RectF, onTapped: (Rect) -> Unit) {
        this.areas = areas
        this.toView = toView
        this.onTapped = onTapped
        visibility = View.VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = View.GONE
        pressed = null
    }

    override fun onDraw(canvas: Canvas) {
        val map = toView ?: return
        val radius = dp(10f)
        val glyphSize = dp(28f)
        for (area in areas) {
            val r = map(area)
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
            // The glyph in the middle, on a disc of its own, where the
            // picture does not show through it.
            canvas.drawCircle(r.centerX(), r.centerY(), glyphSize, glyphBack)
            glyph.setBounds(
                (r.centerX() - glyphSize / 2).toInt(), (r.centerY() - glyphSize / 2).toInt(),
                (r.centerX() + glyphSize / 2).toInt(), (r.centerY() + glyphSize / 2).toInt()
            )
            glyph.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val map = toView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = areas.firstOrNull { map(it).contains(event.x, event.y) }
                // Not on an outline: the crop view's touch, from the start.
                return pressed != null
            }
            MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                val tapped = pressed?.takeIf {
                    kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop
                }
                pressed = null
                if (tapped != null) onTapped?.invoke(tapped)
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return pressed != null || event.actionMasked == MotionEvent.ACTION_UP
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private class ActionRow(context: Context) : LinearLayout(context) {

    // Every label a button may come to show, to judge the fit by the widest:
    // side by side or stacked must not flip when a label changes.
    private val labels = HashMap<TextView, List<String>>()
    private var stacked = false

    init {
        orientation = HORIZONTAL
    }

    fun addAction(button: TextView, vararg labels: String) {
        this.labels[button] = labels.toList()
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyItemLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            setStacked(widthSideBySide() > MeasureSpec.getSize(widthMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The width the row needs with every label on a single line. */
    private fun widthSideBySide(): Int {
        var width = paddingLeft + paddingRight
        for (i in 0 until childCount) {
            val button = getChildAt(i) as TextView
            val widest = (labels[button].orEmpty() + button.text.toString())
                .maxOf { button.paint.measureText(it) }
            // (Compound padding: the padding plus the icon beside the label.)
            width += button.compoundPaddingLeft + button.compoundPaddingRight +
                ceil(widest).toInt() + 2 * dp(ITEM_MARGIN_DP)
        }
        return width
    }

    private fun setStacked(stack: Boolean) {
        if (stacked == stack) return
        stacked = stack
        orientation = if (stack) VERTICAL else HORIZONTAL
        // Reverse the order: the main action is last in a row, first in a stack.
        for (i in childCount - 2 downTo 0) bringChildToFront(getChildAt(i))
        applyItemLayout()
    }

    private fun applyItemLayout() {
        for (i in 0 until childCount) {
            val params = getChildAt(i).layoutParams as LayoutParams
            // Stacked, each button is as wide as the widest of them.
            params.width = if (stacked) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            params.marginStart = dp(ITEM_MARGIN_DP)
            params.marginEnd = dp(ITEM_MARGIN_DP)
            params.topMargin = if (stacked && i > 0) dp(STACK_GAP_DP) else 0
        }
        requestLayout()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ITEM_MARGIN_DP = 6
        const val STACK_GAP_DP = 8
    }
}
