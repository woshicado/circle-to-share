package eu.woshicado.circletoshare

import android.view.View
import android.widget.Button
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** A row that is tapped like a button, though it is none, is one to a
 *  screen reader too. */
internal fun View.readAsButton() {
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    })
}

/** A header that opens and closes what is under it, shown as [open]: by its
 *  [chevron], and in what a screen reader says of it. */
internal fun showExpanded(header: View, chevron: View, open: Boolean) {
    chevron.rotation = if (open) 180f else 0f
    ViewCompat.setStateDescription(
        header,
        header.context.getString(if (open) R.string.setup_expanded else R.string.setup_collapsed)
    )
}
