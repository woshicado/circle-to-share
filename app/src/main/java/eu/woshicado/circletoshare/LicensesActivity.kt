package eu.woshicado.circletoshare

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.IOException

/** A license some of the app's components are used under. */
internal enum class License(
    val title: String,
    @DrawableRes val icon: Int,
    // The license's text, shipped with the app, or (for one that is an
    // agreement with Google rather than a license to pass on) its page.
    @RawRes val text: Int? = null,
    @StringRes val url: Int? = null,
    // What the section says before its components, if anything.
    @StringRes val note: Int? = null,
) {
    APACHE_2("Apache License 2.0", R.drawable.ic_section_components, text = R.raw.license_apache_2_0),
    ANDROID_SDK(
        "Android Software Development Kit License", R.drawable.ic_section_android,
        url = R.string.url_android_sdk_license, note = R.string.licenses_sdk_note,
    ),
}

/** An open-source component the app is built with. */
internal class Component(val name: String, val holder: String, val license: License)

/**
 * What the app is built with, in every build. The store's own components
 * are [storeComponents] (src/foss or src/play). Keep both in step with
 * THIRD_PARTY_NOTICES.md, and with the build's dependencies:
 * ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
 */
internal val components = listOf(
    Component("AndroidX (Jetpack) libraries", "The Android Open Source Project", License.APACHE_2),
    Component("Material Components for Android", "The Android Open Source Project", License.APACHE_2),
    Component("Material Icons and Material Symbols", "Google LLC", License.APACHE_2),
    Component("ZXing", "ZXing authors", License.APACHE_2),
    Component(
        "Kotlin standard library", "JetBrains s.r.o. and Kotlin Programming Language contributors",
        License.APACHE_2,
    ),
    Component("kotlinx.coroutines", "JetBrains s.r.o. and contributors", License.APACHE_2),
    Component("Guava ListenableFuture", "The Guava Authors", License.APACHE_2),
    Component("Error Prone annotations", "The Error Prone Authors", License.APACHE_2),
    Component("JSpecify", "The JSpecify Authors", License.APACHE_2),
    Component("JetBrains Java Annotations", "JetBrains s.r.o.", License.APACHE_2),
)

/**
 * The open-source licenses page: the app's own license and source, then
 * each license its components are used under, with the components and the
 * license's text (or, for one the app does not ship, a link to it).
 */
class LicensesActivity : AppCompatActivity() {

    // The folds open, by key (see fold): kept across a rotation.
    private val opened = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge like the main screen (see MainActivity.onCreate).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_licenses)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.licenses_root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val reopen = savedInstanceState?.getStringArray(STATE_OPENED)?.toSet() ?: emptySet()
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_source_code).setOnClickListener { openUrl(R.string.url_source) }
        fold(findViewById(R.id.app_license_text), "gpl", reopen) { rawText(R.raw.license_gpl_3_0) }

        val groups = findViewById<LinearLayout>(R.id.licenses_groups)
        val inflater = LayoutInflater.from(this)
        fun heading(title: CharSequence, @DrawableRes icon: Int) {
            inflater.inflate(R.layout.item_license_group, groups, true)
            (groups.getChildAt(groups.childCount - 1) as TextView).apply {
                text = title
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            }
        }
        fun note(@StringRes textRes: Int) {
            groups.addView(inflater.inflate(R.layout.item_license_note, groups, false).apply {
                (this as TextView).setText(textRes)
            })
        }
        (components + storeComponents).groupBy { it.license }.forEach { (license, used) ->
            heading(license.title, license.icon)
            license.note?.let { note(it) }
            used.forEach { component ->
                groups.addView(inflater.inflate(R.layout.item_license, groups, false).apply {
                    findViewById<TextView>(R.id.license_component).text = component.name
                    findViewById<TextView>(R.id.license_holder).text =
                        getString(R.string.licenses_copyright, component.holder)
                })
            }
            val text = license.text
            if (text != null) {
                groups.addView(inflater.inflate(R.layout.item_license_text, groups, false).also {
                    fold(it, license.name, reopen) { rawText(text) }
                })
            } else {
                groups.addView(inflater.inflate(R.layout.item_license_link, groups, false).apply {
                    setOnClickListener { license.url?.let { openUrl(it) } }
                })
            }
        }

        // The Play build's: what Google compiled into its libraries, as
        // their notices name it (see GooglePlayNoticesTask in
        // app/build.gradle.kts), one fold each.
        val bundled = bundledNotices() ?: return
        heading(getString(R.string.licenses_bundled_title), R.drawable.ic_section_components)
        note(R.string.licenses_bundled_note)
        bundled.notices.forEachIndexed { i, (name, texts) ->
            groups.addView(inflater.inflate(R.layout.item_license_text, groups, false).also {
                it.findViewById<TextView>(R.id.license_text_title).text = name
                fold(it, "bundled$i", reopen) {
                    texts.joinToString(NOTICES_APART) { reflow(bundled.texts[it]) }
                }
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_OPENED, opened.toTypedArray())
    }

    /**
     * A text folded under its header: [view] (an item_license_text) opens
     * and closes on a tap there, [key] naming it among the page's folds.
     * The text is made when first opened: tens of kilobytes nobody may ask
     * for. Open from the start if [reopen] has it (it was before a
     * rotation).
     */
    private fun fold(view: View, key: String, reopen: Set<String>, text: () -> String) {
        val header = view.findViewById<View>(R.id.license_text_header)
        val chevron = view.findViewById<View>(R.id.license_text_chevron)
        val body = view.findViewById<TextView>(R.id.license_text)
        fun show(open: Boolean) {
            if (open && body.text.isEmpty()) body.text = text()
            body.visibility = if (open) View.VISIBLE else View.GONE
            showExpanded(header, chevron, open)
            if (open) opened.add(key) else opened.remove(key)
        }
        header.readAsButton()
        header.setOnClickListener { show(body.visibility != View.VISIBLE) }
        show(key in reopen)
    }

    private fun rawText(@RawRes res: Int) =
        reflow(resources.openRawResource(res).bufferedReader().use { it.readText() })

    /** The Play build's gathered notices; null in a build without them. */
    private fun bundledNotices(): BundledNotices? {
        val json = try {
            assets.open(BUNDLED_NOTICES).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return null
        }
        val root = JSONObject(json)
        val texts = root.getJSONArray("texts").let { a -> List(a.length()) { a.getString(it) } }
        val notices = root.getJSONArray("notices").let { a ->
            List(a.length()) {
                a.getJSONArray(it).let { n ->
                    n.getString(0) to n.getJSONArray(1).let { t -> List(t.length()) { t.getInt(it) } }
                }
            }
        }
        return BundledNotices(texts, notices)
    }

    // Each notice a name and its texts (indices into texts).
    private class BundledNotices(val texts: List<String>, val notices: List<Pair<String, List<Int>>>)

    private companion object {
        const val STATE_OPENED = "opened"
        const val BUNDLED_NOTICES = "google_play_notices.json"
        // Between the texts of a notice that has several.
        const val NOTICES_APART = "\n\n* * *\n\n"
    }
}

/**
 * A license text, wrapped for 80 columns, made for a phone's fewer: each
 * paragraph's lines are joined for the view to wrap (and two spaces after a
 * sentence become one). Kept line by line:
 * - a title (every line far indented, centered on the 80 columns);
 * - after the terms, a sample notice to copy. Prose there is told from the
 *   sample by its indent: the GPL indents a paragraph's first line, the
 *   Apache license indents its prose deeper than its "END OF TERMS AND
 *   CONDITIONS" line, its sample not. (A notice can hold several licenses:
 *   new terms start at a "TERMS AND CONDITIONS", after the Apache sample's
 *   last line, and at a title once a sample has been.);
 * - within a paragraph, a line that starts an item ("1.", "(a)", "*") or is
 *   commented ("//", "#"), and a rule ("-----") and the line it underlines.
 */
internal fun reflow(text: String): String {
    var endIndent: Int? = null
    var sampleSeen = false
    return text.trim('\n').split(PARAGRAPH_BREAK).joinToString("\n\n") { para ->
        val lines = para.lines().filter { it.isNotBlank() }
        val indents = lines.map { line -> line.indexOfFirst { !it.isWhitespace() } }
        val end = endIndent
        val title = indents.all { it >= TITLE_INDENT }
        val sample = !title && end != null &&
            indents.first() <= (indents.drop(1).minOrNull() ?: indents.first()) &&
            indents.min() <= end
        when {
            title -> lines.joinToString("\n") { it.trim() }
            sample -> para.trimIndent().trim('\n')
            else -> joinLines(lines.map { it.trim() })
        }.also {
            if (sample) sampleSeen = true
            if (para.contains("TERMS AND CONDITIONS") || (sample && para.trimEnd().endsWith(APACHE_SAMPLE_END)) ||
                (title && sampleSeen)
            ) {
                endIndent = if (para.contains("END OF TERMS AND CONDITIONS")) indents.min() else null
                sampleSeen = false
            }
        }
    }
}

// A paragraph's lines joined, but for those that start a line of their own
// (see reflow).
private fun joinLines(lines: List<String>): String = buildString {
    lines.forEachIndexed { i, line ->
        val own = i > 0 && (OWN_LINE.containsMatchIn(line) || RULE.matches(line) ||
            RULE.matches(lines[i - 1]) || COMMENT.containsMatchIn(lines[i - 1]))
        if (i > 0) append(if (own) "\n" else " ")
        append(line.replace(SPACES, " "))
    }
}

// How far a title's lines are indented, at the least (the terms run to 6).
private const val TITLE_INDENT = 8
private const val APACHE_SAMPLE_END = "limitations under the License."
private val PARAGRAPH_BREAK = Regex("\n\\s*\n")
private val SPACES = Regex(" {2,}")
private val COMMENT = Regex("^(//|#)")
private val OWN_LINE = Regex("^(//|#|[*•-] |\\(?([0-9]{1,3}|[a-z]|[ivx]{1,4})[.)] )")
private val RULE = Regex("^[-=_*]{3,}$")
