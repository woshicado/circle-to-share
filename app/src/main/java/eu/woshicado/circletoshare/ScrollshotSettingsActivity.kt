package eu.woshicado.circletoshare

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The scrollshot settings: how far a step scrolls and whether scroll
 * handles are painted over — in general, and for the apps the user gives
 * settings of their own (those, and whether to snap there).
 *
 * The apps are asked of the system when the user opens that part of the
 * page, not before, and are kept no longer than the page is.
 */
class ScrollshotSettingsActivity : AppCompatActivity() {

    internal class App(val packageName: String, val label: String)

    // Reading the apps and their icons, off the main thread.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "scrollshot-apps") }
    // The icons last shown (not all of a long list's: they are large), the
    // apps whose icon is being read, and how many rows on screen want each
    // app's. (A count, not a set: when the list is rebuilt, the new row of
    // an app is bound before the old one is recycled, and the old one must
    // not take the want away from the new.)
    private val icons = LruCache<String, Drawable>(ICONS_KEPT)
    private val iconsLoading = HashSet<String>()
    private val iconsWanted = ConcurrentHashMap<String, Int>()

    // null until read (see loadApps); the user's apps, those with settings
    // of their own first. And what of them the search leaves.
    private var apps: List<App>? = null
    private var shown: List<App> = emptyList()
    private var appsOpen = false
    private var query = ""

    private lateinit var header: View
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge like the main screen (see MainActivity.onCreate).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_scrollshot)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.scrollshot_root)) { v, insets ->
            // (The keyboard too: the list ends above it.)
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            // (The page's side margins are the title row's and the list's
            // own: a card in the list reaches into them, and must not be
            // clipped there.)
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.apps_list)
        list.layoutManager = LinearLayoutManager(this)
        header = LayoutInflater.from(this).inflate(R.layout.header_scrollshot, list, false)
        // (Rows come and go with every letter typed into the search.)
        list.itemAnimator = null
        list.adapter = adapter

        setUpStep(
            header.findViewById(R.id.slider_capture_more_step), Prefs.getCaptureMoreStep(this)
        ) { Prefs.setCaptureMoreStep(this, it) }
        header.findViewById<MaterialSwitch>(R.id.switch_capture_more_handles).apply {
            isChecked = Prefs.isCaptureMoreHandleRemovalEnabled(this@ScrollshotSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setCaptureMoreHandleRemovalEnabled(this@ScrollshotSettingsActivity, checked)
            }
        }

        val search = header.findViewById<EditText>(R.id.apps_search)
        header.findViewById<View>(R.id.apps_header).apply {
            readAsButton()
            setOnClickListener {
                appsOpen = !appsOpen
                if (appsOpen && apps == null) loadApps()
                if (!appsOpen) {
                    // The field being typed in goes, and its keyboard with it.
                    search.clearFocus()
                    getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(search.windowToken, 0)
                }
                renderApps()
            }
        }
        // Typed in, the field goes to the top of the list: the keyboard
        // takes the lower half of the screen, and on a small one that is
        // where the field and everything it finds would be.
        search.setOnFocusChangeListener { _, focused ->
            if (focused) {
                list.post {
                    (list.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(0, -search.top)
                }
            }
        }
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
        search.apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    renderApps()
                }
            })
        }

        // Left open, the list is open again after a rotation.
        appsOpen = savedInstanceState?.getBoolean(STATE_APPS_OPEN) ?: false
        if (appsOpen) loadApps()
        renderApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_APPS_OPEN, appsOpen)
        outState.putString(STATE_QUERY, query)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    /** The slider for a step, as the main one and every app's are. */
    private fun setUpStep(slider: Slider, value: Int, onChange: (Int) -> Unit) {
        slider.clearOnChangeListeners()
        slider.valueFrom = Prefs.CAPTURE_MORE_STEP_MIN.toFloat()
        slider.valueTo = Prefs.CAPTURE_MORE_STEP_MAX.toFloat()
        slider.stepSize = Prefs.CAPTURE_MORE_STEP_STEP.toFloat()
        slider.setLabelFormatter { getString(R.string.capture_more_step_value, it.roundToInt()) }
        slider.value = value.toFloat()
        slider.addOnChangeListener { _, changed, fromUser ->
            if (fromUser) onChange(changed.roundToInt())
        }
    }

    /**
     * Ask the system for the apps the user can open — what a launcher
     * shows — and put those that have settings of their own first. One
     * that has them and is not among those any more (uninstalled, say) is
     * listed all the same, by its package name: so that it can be seen, and
     * switched off.
     */
    private fun loadApps() {
        val overridden = Prefs.getAppOverrides(this)
        val pm = packageManager
        worker.execute {
            val found = LinkedHashMap<String, App>()
            val launchers = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                )
            }.getOrDefault(emptyList())
            for (launcher in launchers) {
                val info = launcher.activityInfo?.applicationInfo ?: continue
                if (info.packageName in found) continue
                found[info.packageName] = App(info.packageName, info.loadLabel(pm).toString())
            }
            for (packageName in overridden) {
                if (packageName !in found) found[packageName] = App(packageName, packageName)
            }
            val sorted = found.values.sortedWith(
                compareBy<App> { it.packageName !in overridden }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = sorted
                renderApps()
            }
        }
    }

    /** The header of the apps and the rows under it, as things are now. */
    private fun renderApps() {
        renderCount()
        showExpanded(
            header.findViewById(R.id.apps_header), header.findViewById(R.id.apps_chevron), appsOpen
        )
        val all = apps
        header.findViewById<TextView>(R.id.apps_note).apply {
            setText(
                if (appsOpen && all == null) R.string.scrollshot_apps_loading
                else R.string.scrollshot_apps_note
            )
            visibility = if (appsOpen && all != null) View.GONE else View.VISIBLE
        }
        header.findViewById<View>(R.id.apps_search).visibility =
            if (appsOpen && all != null) View.VISIBLE else View.GONE

        val was = shown.size
        shown = if (!appsOpen || all == null) {
            emptyList()
        } else {
            val wanted = query.trim()
            all.filter { it.label.contains(wanted, ignoreCase = true) }
        }
        // (Not the header's row: the search field in it is being typed in.)
        adapter.notifyItemRangeRemoved(1, was)
        adapter.notifyItemRangeInserted(1, shown.size)
    }

    /** How many apps have settings of their own, in the header. */
    private fun renderCount() {
        val overridden = Prefs.getAppOverrides(this).size
        header.findViewById<TextView>(R.id.apps_title).text =
            if (overridden == 0) getString(R.string.scrollshot_apps)
            else getString(R.string.scrollshot_apps_count, overridden)
    }

    /** The packages of the apps listed now, top to bottom (for tests). */
    internal fun shownApps(): List<String> = shown.map { it.packageName }

    private class AppHolder(row: View) : RecyclerView.ViewHolder(row) {
        val card: MaterialCardView = row.findViewById(R.id.app_card)
        val icon: ImageView = row.findViewById(R.id.app_icon)
        val label: TextView = row.findViewById(R.id.app_label)
        val override: MaterialSwitch = row.findViewById(R.id.app_switch)
        val options: View = row.findViewById(R.id.app_options)
        val step: Slider = row.findViewById(R.id.app_step)
        val handles: MaterialSwitch = row.findViewById(R.id.app_handles)
        val snap: MaterialSwitch = row.findViewById(R.id.app_snap)
        var packageName: String? = null
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 1 + shown.size

        override fun getItemViewType(position: Int): Int = if (position == 0) HEADER else APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == APP) {
                return AppHolder(layoutInflater.inflate(R.layout.item_scrollshot_app, parent, false))
            }
            // The one header there is, with what was typed and set in it:
            // never recycled (scrolled away, its holder is dropped and the
            // view left with no parent), and handed out again when wanted.
            return object : RecyclerView.ViewHolder(header) {}.apply { setIsRecyclable(false) }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is AppHolder) return
            val app = shown[position - 1]
            val context = this@ScrollshotSettingsActivity
            // (A row shown another app before, without being recycled in
            // between, wants that one's icon no longer.)
            holder.packageName?.takeIf { it != app.packageName }?.let(::unwantIcon)
            holder.packageName = app.packageName
            holder.label.text = app.label
            showIcon(holder, app.packageName)

            // (No listener while a recycled row is set to its new app.)
            holder.override.setOnCheckedChangeListener(null)
            holder.handles.setOnCheckedChangeListener(null)
            holder.snap.setOnCheckedChangeListener(null)

            val overridden = app.packageName in Prefs.getAppOverrides(context)
            holder.override.contentDescription = getString(R.string.scrollshot_app_override, app.label)
            holder.override.isChecked = overridden
            showOptions(holder, app.packageName, overridden)
            holder.override.setOnCheckedChangeListener { _, checked ->
                Prefs.setAppOverride(context, app.packageName, checked)
                showOptions(holder, app.packageName, checked)
                renderCount()
            }
            // The row is the switch, to a finger.
            holder.itemView.findViewById<View>(R.id.app_row).setOnClickListener { holder.override.toggle() }
        }

        /** An app's own settings under its switch, while that is on. */
        private fun showOptions(holder: AppHolder, packageName: String, overridden: Boolean) {
            val context = this@ScrollshotSettingsActivity
            holder.options.visibility = if (overridden) View.VISIBLE else View.GONE
            // On a card of its own with them (Material's outlined card: a
            // hairline and a touch of fill), a plain row without.
            holder.card.strokeWidth = if (overridden) dp(1) else 0
            holder.card.setCardBackgroundColor(
                if (overridden) {
                    MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSurfaceContainerLow)
                } else {
                    Color.TRANSPARENT
                }
            )
            if (!overridden) return
            holder.handles.setOnCheckedChangeListener(null)
            holder.snap.setOnCheckedChangeListener(null)
            setUpStep(holder.step, Prefs.getCaptureMoreStep(context, packageName)) {
                Prefs.setCaptureMoreStep(context, it, packageName)
            }
            holder.handles.isChecked = Prefs.isCaptureMoreHandleRemovalEnabled(context, packageName)
            holder.handles.setOnCheckedChangeListener { _, checked ->
                Prefs.setCaptureMoreHandleRemovalEnabled(context, checked, packageName)
            }
            holder.snap.isChecked = Prefs.isSnapEnabled(context, packageName)
            holder.snap.setOnCheckedChangeListener { _, checked ->
                Prefs.setSnapEnabled(context, checked, packageName)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is AppHolder) holder.packageName?.let(::unwantIcon)
        }

        private fun unwantIcon(packageName: String) {
            iconsWanted.computeIfPresent(packageName) { _, n -> if (n > 1) n - 1 else null }
        }

        /** The app's icon: read off the main thread, once at a time per
         *  app, and not at all for a row that has left the screen by the
         *  time its turn comes (a fling passes hundreds). */
        private fun showIcon(holder: AppHolder, packageName: String) {
            val known = icons.get(packageName)
            holder.icon.setImageDrawable(known)
            if (known != null || worker.isShutdown) return
            iconsWanted.merge(packageName, 1, Int::plus)
            if (!iconsLoading.add(packageName)) return
            worker.execute {
                val icon = if ((iconsWanted[packageName] ?: 0) > 0) {
                    runCatching { packageManager.getApplicationIcon(packageName) }
                        .getOrElse { packageManager.defaultActivityIcon }
                } else {
                    null
                }
                runOnUiThread {
                    iconsLoading.remove(packageName)
                    if (icon == null || isDestroyed) return@runOnUiThread
                    icons.put(packageName, icon)
                    // The row that asked, if it still shows the app — it may
                    // not be on screen yet: a list that is flung binds rows
                    // ahead and keeps them ready, and shows them later as
                    // they are, without binding them again...
                    if (holder.packageName == packageName) holder.icon.setImageDrawable(icon)
                    // ...and whichever row on screen shows the app now: not
                    // always the one that asked.
                    val list = findViewById<RecyclerView>(R.id.apps_list)
                    for (i in 0 until list.childCount) {
                        val row = list.getChildViewHolder(list.getChildAt(i)) as? AppHolder ?: continue
                        if (row.packageName == packageName) row.icon.setImageDrawable(icon)
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_APPS_OPEN = "apps_open"
        const val STATE_QUERY = "query"
        const val HEADER = 0

        // Three screens' worth of rows.
        const val ICONS_KEPT = 48
        const val APP = 1
    }
}
