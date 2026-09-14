package eu.woshicado.circletoshare.crop

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import eu.woshicado.circletoshare.R
import eu.woshicado.circletoshare.qr.QrCode
import eu.woshicado.circletoshare.qr.QrDetector

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

    private val cropView = CropOverlayView(context)
    private val hintView: TextView
    private val buttonBar: LinearLayout
    private val shareButton: TextView
    private val qrPanel: LinearLayout
    private val qrPanelText: TextView
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
            background = pill(Color.argb(200, 30, 30, 30), 24)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
        }
        addView(
            hintView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(64) }
        )

        buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val cancel = pillButton(context.getString(R.string.action_cancel), filled = false) {
            callbacks?.onCancel()
        }
        val copy = pillButton(context.getString(R.string.action_copy), filled = false) {
            deliver(share = false)
        }
        shareButton = pillButton(context.getString(R.string.action_share_full), filled = true) {
            deliver(share = true)
        }
        buttonBar.addView(cancel, barItem())
        buttonBar.addView(copy, barItem())
        buttonBar.addView(shareButton, barItem())
        addView(
            buttonBar,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(48) }
        )

        // Actions for a tapped QR code: the payload plus Copy/Open pills.
        // Sits above the main button bar; dismissed by tapping anywhere else.
        qrPanelText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        qrOpenButton = pillButton(context.getString(R.string.qr_open), filled = true) {
            activeQr?.let { openQr(it.text) }
        }
        val qrCopyButton = pillButton(context.getString(R.string.action_copy), filled = false) {
            activeQr?.let { copyQr(it.text) }
        }
        qrPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Color.argb(235, 30, 30, 30), 20)
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
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(qrCopyButton, barItem())
                    addView(qrOpenButton, barItem())
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
                bottomMargin = dp(120)
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )
    }

    fun setBitmap(bitmap: android.graphics.Bitmap, snap: SnapBounds? = null) {
        // Apply the current crop-gesture preference (freeform circle vs. box).
        val freeform = eu.woshicado.circletoshare.Prefs.isFreeformCrop(context)
        cropView.freeform = freeform

        setBackgroundColor(Color.BLACK)
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
            hintView.isClickable = false
            hintView.setOnClickListener(null)
        }
    }

    /** Drop the frozen screenshot and hide the UI — the reverse of [setBitmap].
     *  Called when the overlay is dismissed so a reused instance can't flash
     *  the previous session's image. */
    fun clear() {
        background = null
        currentBitmap = null
        hideQrPanel()
        cropView.clear()
        // Drop the snap lines too — a reused instance must not snap to a
        // previous session's layout.
        cropView.setSnapBounds(null)
        cropView.visibility = View.GONE
        hintView.visibility = View.GONE
        buttonBar.visibility = View.GONE
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
        qrOpenButton.visibility = if (looksOpenable(code.text)) View.VISIBLE else View.GONE
        qrPanel.visibility = View.VISIBLE
    }

    private fun hideQrPanel() {
        activeQr = null
        qrPanel.visibility = View.GONE
    }

    /** True when the payload is a URI an app could plausibly ACTION_VIEW —
     *  http(s), mailto, tel, geo, sms… Wi-Fi join codes are excluded (nothing
     *  handles them as a VIEW intent); they can still be copied. */
    private fun looksOpenable(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:\\S.*$", RegexOption.DOT_MATCHES_ALL))) {
            return false
        }
        return !trimmed.startsWith("wifi:", ignoreCase = true)
    }

    private fun copyQr(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("qr", text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        callbacks?.onCancel()
    }

    private fun openQr(text: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text.trim()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.qr_open_failed, Toast.LENGTH_SHORT).show()
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
            background = pill(if (filled) Color.WHITE else Color.argb(230, 45, 45, 45), 26)
            setPadding(dp(24), dp(13), dp(24), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun barItem() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(6)
        marginEnd = dp(6)
    }

    private fun pill(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
