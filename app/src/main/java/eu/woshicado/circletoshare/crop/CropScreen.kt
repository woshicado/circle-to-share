package eu.woshicado.circletoshare.crop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.qr.QrDetector
import eu.woshicado.circletoshare.qr.qrOpenUri
import kotlin.math.ceil

/**
 * The full crop overlay UI — frozen screenshot, draggable crop rectangle, and
 * the Cancel/Copy/Share pill bar. Shared by both the assistant session and the
 * floating-bubble path so the two behave identically.
 */
class CropScreen(context: Context) : FrameLayout(context) {

    interface Callbacks {
        /** [rect] is in bitmap coordinates, or null to share the whole screen. */
        fun onDeliver(share: Boolean, rect: Rect?)
        fun onCancel()
    }

    var callbacks: Callbacks? = null

    /**
     * What Back does, for a host whose window gets no Back handling of its
     * own (a bare overlay window; the assistant session's dialog has its own
     * and leaves this unset).
     */
    var onBackKey: (() -> Unit)? = null

    private val cropView = CropOverlayView(context)
    private val hintView: TextView
    private val buttonBar: ActionRow
    private val shareButton: TextView
    private val qrPanel: LinearLayout
    private val qrPanelText: TextView
    private val qrPanelError: TextView
    private val qrOpenButton: TextView

    // Saved crop offered by the tappable hint, or null when none is available
    // for the current capture.
    private var pendingRestore: Rect? = null

    // The capture currently on screen — identity-checked when an async QR scan
    // finishes, so a result for a dismissed or replaced capture is dropped.
    private var currentBitmap: android.graphics.Bitmap? = null

    // The tapped code whose actions the panel is showing.
    private var activeQr: QrCode? = null

    init {
        cropView.visibility = View.GONE
        cropView.listener = object : CropOverlayView.Listener {
            override fun onSelectionChanged(hasSelection: Boolean) {
                shareButton.text = context.getString(
                    if (hasSelection) R.string.action_share else R.string.action_share_full
                )
                hintView.visibility = if (hasSelection) View.GONE else View.VISIBLE
            }

            override fun onDoubleTapInside() = deliver(share = true)
            override fun onLongPressInside() = deliver(share = false)
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
        val cancel = pillButton(context.getString(R.string.action_cancel), filled = false) {
            callbacks?.onCancel()
        }
        val copy = pillButton(context.getString(R.string.action_copy), filled = false) {
            deliver(share = false)
        }
        shareButton = pillButton(context.getString(R.string.action_share_full), filled = true) {
            deliver(share = true)
        }
        buttonBar.addAction(cancel)
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
        qrOpenButton = pillButton(context.getString(R.string.qr_open), filled = true) {
            activeQr?.let { openQr(it.text) }
        }
        val qrCopyButton = pillButton(context.getString(R.string.action_copy), filled = false) {
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
        if (buttonBar.visibility == View.GONE) return
        val params = qrPanel.layoutParams as LayoutParams
        val above = dp(BAR_BOTTOM_MARGIN_DP + QR_PANEL_GAP_DP) + buttonBar.measuredHeight
        if (params.bottomMargin != above) {
            params.bottomMargin = above
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun setBitmap(bitmap: android.graphics.Bitmap, snap: SnapBounds? = null) {
        // Apply the current crop-gesture preference (freeform circle vs. box).
        val freeform = eu.woshicado.circletoshare.Prefs.isFreeformCrop(context)
        cropView.freeform = freeform

        setBackgroundColor(Color.BLACK)
        setOnClickListener(null)
        isClickable = false
        currentBitmap = bitmap
        hideQrPanel()
        cropView.setBitmap(bitmap)
        cropView.setSnapBounds(snap)
        // Scan the capture for QR codes off the main thread; the identity
        // check drops the result if the tool was dismissed (or reloaded with
        // a new capture) while the scan ran. Both trigger paths — assistant
        // session and bubble — funnel through here, so this covers them all.
        cropView.setQrHighlightColor(eu.woshicado.circletoshare.Prefs.getQrColor(context))
        if (eu.woshicado.circletoshare.Prefs.isQrDetectEnabled(context)) {
            QrDetector.detect(bitmap) { codes ->
                if (currentBitmap === bitmap) cropView.setQrCodes(codes)
            }
        }
        cropView.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        buttonBar.visibility = View.VISIBLE
        shareButton.text = context.getString(R.string.action_share_full)

        // Offer to restore the last crop only when it fits this exact capture.
        pendingRestore = eu.woshicado.circletoshare.Prefs
            .getLastCropFor(context, bitmap.width, bitmap.height)
        if (pendingRestore != null) {
            hintView.setText(R.string.crop_hint_restore)
            hintView.isClickable = true
            hintView.setOnClickListener { pendingRestore?.let { cropView.applyCropRect(it) } }
        } else {
            hintView.setText(if (freeform) R.string.crop_hint_freeform else R.string.crop_hint)
            // In this order: setOnClickListener makes a view clickable, even
            // when handed null. A clickable hint swallows every touch that
            // starts on it — no crop could begin there, and a QR code
            // underneath couldn't be tapped.
            hintView.setOnClickListener(null)
            hintView.isClickable = false
        }
    }

    /**
     * Show [message] in place of the tool — for a host that has nothing to
     * crop and nowhere else to say why. A touch anywhere asks the host to
     * close ([Callbacks.onCancel]). Undone by [setBitmap] and [clear].
     */
    fun showMessage(message: String) {
        // Nothing behind the message — not the black the picture stood on,
        // if there was one: the screen underneath shows through instead.
        background = null
        currentBitmap = null
        hideQrPanel()
        cropView.visibility = View.GONE
        buttonBar.visibility = View.GONE
        hintView.text = message
        hintView.setOnClickListener(null)
        hintView.isClickable = false
        hintView.visibility = View.VISIBLE
        setOnClickListener { callbacks?.onCancel() }
    }

    /** Drop the frozen screenshot and hide the UI — the reverse of [setBitmap].
     *  Called when the overlay is dismissed so a reused instance can't flash
     *  the previous session's image. */
    fun clear() {
        background = null
        currentBitmap = null
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
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
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
    }

    private fun hideQrPanel() {
        activeQr = null
        qrPanel.visibility = View.GONE
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

    private fun deliver(share: Boolean) {
        val rect = cropView.getCropRect()
        // Remember an actual selection so it can be restored next time; a
        // full-screen share (null rect) leaves the previous memory untouched.
        if (rect != null) {
            cropView.getBitmapSize()?.let { (w, h) ->
                eu.woshicado.circletoshare.Prefs.saveLastCrop(context, rect, w, h)
            }
        }
        callbacks?.onDeliver(share, rect)
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
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
            setPadding(dp(24), dp(13), dp(24), dp(13))
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
    }
}

/**
 * A row of pill buttons, main action last. Where the buttons don't fit side
 * by side with their labels on one line — a narrow screen at a large font or
 * display size — it becomes a stack of equally wide buttons instead, main
 * action first, rather than squeeze the last one into a sliver that breaks
 * its label a letter at a time.
 */
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
            width += button.paddingLeft + button.paddingRight + ceil(widest).toInt() + 2 * dp(ITEM_MARGIN_DP)
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
