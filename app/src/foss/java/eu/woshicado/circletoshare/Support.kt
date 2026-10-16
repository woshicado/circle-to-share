package eu.woshicado.circletoshare

import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * F-Droid / GitHub build: the support button opens the Ko-fi page in the
 * browser. The Play build takes tips through Google Play instead (its own
 * Support.kt), as Play doesn't allow outside donation links.
 */
internal fun AppCompatActivity.setupSupportButton(button: Button) {
    button.setOnClickListener { openUrl(R.string.url_kofi) }
}
