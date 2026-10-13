package eu.woshicado.circletoshare

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService

/**
 * What the accessibility service reads and what becomes of it, put to the
 * user before the service is used — a screen of its own, apart from the
 * privacy policy, that only an explicit tap on Agree gets past.
 *
 * RESULT_OK means the user agreed (and the answer is stored); leaving any
 * other way — No thanks, Back — is a no, and stores nothing.
 */
class DisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge like the main screen (see MainActivity.onCreate).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_disclosure)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.disclosure_root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Button>(R.id.btn_disclosure_privacy).setOnClickListener {
            openUrl(R.string.url_privacy)
        }
        findViewById<Button>(R.id.btn_disclosure_decline).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.btn_disclosure_agree).setOnClickListener {
            Prefs.setAccessibilityConsent(this, true)
            // A service that was already switched on has been holding the
            // bubble back for this answer.
            ScreenshotAccessibilityService.instance?.applyBubbleState()
            setResult(RESULT_OK)
            finish()
        }
    }
}
