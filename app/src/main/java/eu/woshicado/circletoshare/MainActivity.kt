package eu.woshicado.circletoshare

import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import eu.woshicado.circletoshare.assist.AssistInteractionService
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import eu.woshicado.circletoshare.tile.CaptureTileService
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // True while the switches are being written by anything but the user, so
    // the listeners can tell programmatic sync from real input. (Checking
    // view.isPressed instead would also drop toggles made via TalkBack or a
    // keyboard.)
    //
    // It starts out true, and stays so until the first updateStatus() has
    // run: when the activity is recreated (rotation, dark mode, font size)
    // the framework restores each switch's saved state between onCreate and
    // onResume, and that calls the listeners exactly like a tap would. For
    // the bubble switch a phantom "turned on" resets the bubble's position.
    private var syncingSwitches = true

    // True while a finger is on the opacity slider: the bubble previews the
    // value, which is saved only when the touch ends (see onCreate).
    private var opacityTracking = false

    // Tells the status lines when either setup step changes in Settings
    // while this screen is in view (see onStart).
    private val setupObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = updateStatus()
    }

    // The accessibility disclosure, asked from two places with two things to
    // do next. From the settings button, agreeing carries on to where the
    // button was going.
    private val disclosureBeforeSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) openAccessibilitySettings() else turnOffUnconsentedService()
        }

    // And for a service found switched on with no answer on record — an
    // update from a version that didn't ask, or one enabled straight from
    // Settings. Agreeing needs nothing more; the service is already there.
    private val disclosureForRunningService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) turnOffUnconsentedService()
        }

    // That second case is put to the user once per time the service is found
    // on, not on every resume: a "no" switches the service off, but the
    // setting that says so changes a moment later.
    private var disclosureOffered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disclosureOffered = savedInstanceState?.getBoolean(STATE_DISCLOSURE_OFFERED) ?: false
        // Android 15+ lays the activity out edge to edge whether we ask or
        // not (targetSdk 36); ask for the same below that, so every version
        // takes the one code path and looks alike. The theme makes the bars
        // transparent to match.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Edge to edge, nothing keeps the content clear of the status bar,
        // the navigation bar or a display cutout unless we inset it
        // ourselves — and adjustResize alone does
        // nothing, so the keyboard would cover the hex field at the bottom
        // with no way to scroll it into view. Pad the scroll container by the
        // bars (and by the IME when it is taller) so content rests and clips
        // inside the safe area and the focused field can scroll above the
        // keyboard.
        val scroll = findViewById<ScrollView>(R.id.root_scroll)
        // Somewhere for focus to land when the hex field clears it on Done —
        // clearFocus() on the window's only focusable bounces right back.
        scroll.isFocusableInTouchMode = true
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // Padding makes the field reachable, but doesn't move it — and
            // requestRectangleOnScreen can't help, because ScrollView's
            // visibility math ignores its own bottom padding and considers
            // the covered field "already on screen". Scroll the focused
            // field above the keyboard by explicit screen geometry instead.
            if (ime.bottom > 0) {
                v.post {
                    val focus = currentFocus ?: return@post
                    val focusLoc = IntArray(2).also { focus.getLocationOnScreen(it) }
                    val scrollLoc = IntArray(2).also { v.getLocationOnScreen(it) }
                    val focusBottom = focusLoc[1] + focus.height - scrollLoc[1]
                    val visibleBottom = v.height - ime.bottom
                    val margin = (16 * resources.displayMetrics.density).toInt()
                    val delta = focusBottom - visibleBottom + margin
                    if (delta > 0) (v as ScrollView).smoothScrollBy(0, delta)
                }
            }
            insets
        }

        findViewById<Button>(R.id.btn_assistant_settings).setOnClickListener {
            openFirstAvailable(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            )
        }
        findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener {
            if (Prefs.hasAccessibilityConsent(this)) {
                openAccessibilitySettings()
            } else {
                disclosureBeforeSettings.launch(Intent(this, DisclosureActivity::class.java))
            }
        }
        attachSetupHelp(
            findViewById(R.id.help_assistant),
            R.string.help_title_assistant, R.raw.help_assistant, R.string.help_steps_assistant
        )
        attachSetupHelp(
            findViewById(R.id.help_fallback),
            R.string.help_title_fallback, R.raw.help_fallback, R.string.help_steps_fallback
        )
        attachSetupHelp(
            findViewById(R.id.help_tile),
            R.string.help_title_tile, R.raw.help_tile, R.string.help_steps_tile
        )
        attachSetupHelp(
            findViewById(R.id.help_trigger_power),
            R.string.help_title_trigger_power, R.raw.help_trigger_power, R.string.help_steps_trigger_power
        )
        attachSetupHelp(
            findViewById(R.id.help_trigger_corner),
            R.string.help_title_trigger_corner, R.raw.help_trigger_corner, R.string.help_steps_trigger_corner
        )
        attachSetupHelp(
            findViewById(R.id.help_trigger_tile),
            R.string.help_title_trigger_tile, R.raw.help_trigger_tile, R.string.help_steps_trigger_tile
        )
        attachSetupHelp(
            findViewById(R.id.help_trigger_bubble),
            R.string.help_title_trigger_bubble, R.raw.help_trigger_bubble, R.string.help_steps_trigger_bubble
        )

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            val service = AssistInteractionService.instance
            if (service == null) {
                Toast.makeText(this, R.string.error_not_assistant, Toast.LENGTH_LONG).show()
            } else {
                service.launchCaptureSession()
            }
        }

        val addTile = findViewById<Button>(R.id.btn_add_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.setOnClickListener {
                // Refuse at the source like the bubble/snap switches, rather
                // than letting the user place a tile that can only ever bounce
                // them back here.
                if (!fallbackUsable()) {
                    Toast.makeText(
                        this, R.string.error_tile_needs_accessibility, Toast.LENGTH_LONG
                    ).show()
                } else {
                    requestAddTile()
                }
            }
        } else {
            // Pre-33 the tile can only be placed by hand from the shade's edit
            // screen, which the tile's line under "How to open it" explains.
            addTile.visibility = View.GONE
        }

        findViewById<MaterialSwitch>(R.id.switch_bubble).setOnCheckedChangeListener { view, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && !fallbackUsable()) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_bubble_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            ScreenshotAccessibilityService.setBubblePref(this, checked)
            setOpacityRowVisible(checked)
        }

        findViewById<Slider>(R.id.slider_bubble_opacity).apply {
            // Range and step come from the service, which snaps stored values
            // to exactly these.
            valueFrom = ScreenshotAccessibilityService.BUBBLE_OPACITY_MIN.toFloat()
            valueTo = ScreenshotAccessibilityService.BUBBLE_OPACITY_MAX.toFloat()
            stepSize = ScreenshotAccessibilityService.BUBBLE_OPACITY_STEP.toFloat()
            value = valueTo
            setLabelFormatter { getString(R.string.bubble_opacity_value, it.roundToInt()) }
            // While a finger is on the slider the bubble only previews the
            // value; it is saved once, on release (or cancel).
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    opacityTracking = true
                }

                override fun onStopTrackingTouch(slider: Slider) = saveBubbleOpacity()
            })
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                if (opacityTracking) {
                    ScreenshotAccessibilityService.instance?.previewBubbleOpacity(value.roundToInt())
                } else {
                    // TalkBack and keyboard adjustments come without a touch
                    // to end: each one is a finished change.
                    ScreenshotAccessibilityService.setBubbleOpacity(this@MainActivity, value.roundToInt())
                }
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_freeform).setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            Prefs.setFreeformCrop(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_snap).setOnCheckedChangeListener { view, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && !fallbackUsable()) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_snap_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            Prefs.setSnapEnabled(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_qr).setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            Prefs.setQrDetectEnabled(this, checked)
        }

        setupQrColorField()

        findViewById<Button>(R.id.btn_kofi).setOnClickListener { openUrl(R.string.url_kofi) }
        findViewById<Button>(R.id.btn_feedback).setOnClickListener { composeFeedback() }
        findViewById<Button>(R.id.btn_privacy).setOnClickListener { openUrl(R.string.url_privacy) }
        findViewById<Button>(R.id.btn_imprint).setOnClickListener { openUrl(R.string.url_imprint) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DISCLOSURE_OFFERED, disclosureOffered)
    }

    // Guards the color field's TextWatcher against programmatic setText
    // (initial render, Done-normalize, focus-loss revert).
    private var syncingColorText = false

    /**
     * Hex field + live swatch for the QR highlight color. Every edit that
     * forms a valid color commits immediately (and recolors the swatch);
     * partial or invalid text simply doesn't commit — the last valid color
     * stays stored. Enter/Done on a valid color normalizes the text to
     * "#RRGGBB", drops focus, and closes the keyboard; on an invalid one it
     * toasts and keeps the field open for fixing. Focus loss reverts the
     * display to whatever is stored.
     */
    private fun setupQrColorField() {
        val input = findViewById<EditText>(R.id.qr_color_input)
        val swatch = findViewById<View>(R.id.qr_color_swatch)
        fun renderSwatch() {
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Prefs.getQrColor(this@MainActivity))
            }
        }
        fun showStored() {
            syncingColorText = true
            input.setText(Prefs.getQrColorHex(this))
            syncingColorText = false
            renderSwatch()
        }
        showStored()

        // Keep the XML LengthFilter; android:digits can't be used for the
        // hex restriction (see the layout comment), so add our own filter —
        // ahead of it: filters run in order, and pasted text has to lose the
        // characters that don't count before it is cut to length, not after
        // (" #FFCA28" would otherwise come out one digit short).
        val hexOnly = InputFilter { source, start, end, _, _, _ ->
            // ('0'..'9', not isDigit(): that one takes the digits of every
            // script, which a hex code doesn't.)
            fun allowed(c: Char) = c == '#' || c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if ((start until end).all { allowed(source[it]) }) {
                null
            } else {
                // The others cut out — and the keyboard's composing spans
                // carried over to what is left: they mark the word being
                // typed, and without them the next keystroke adds the word a
                // second time instead of replacing it. (By hand, because the
                // copying constructors skip exactly those: NoCopySpan. And
                // only those: styling pasted along with text has no business
                // here, and a paragraph span set down where the line breaks
                // used to be throws.)
                val kept = StringBuilder()
                // keptBefore[i]: how many kept characters precede source[start + i].
                val keptBefore = IntArray(end - start + 1)
                for (i in start until end) {
                    keptBefore[i - start] = kept.length
                    if (allowed(source[i])) kept.append(source[i])
                }
                keptBefore[end - start] = kept.length
                val filtered = SpannableString(kept)
                if (source is Spanned) {
                    for (span in source.getSpans(start, end, Any::class.java)) {
                        val flags = source.getSpanFlags(span)
                        if (flags and Spanned.SPAN_COMPOSING == 0) continue
                        val from = keptBefore[source.getSpanStart(span).coerceIn(start, end) - start]
                        val to = keptBefore[source.getSpanEnd(span).coerceIn(start, end) - start]
                        if (from < to) filtered.setSpan(span, from, to, flags)
                    }
                }
                filtered
            }
        }
        input.filters = arrayOf(hexOnly) + input.filters

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (syncingColorText) return
                if (Prefs.setQrColorHex(this@MainActivity, s.toString()) != null) {
                    renderSwatch()
                }
            }
        })

        // IME_NULL is what a hardware Enter arrives as — the soft keyboard's
        // Done button sends IME_ACTION_DONE.
        input.setOnEditorActionListener { _, actionId, event ->
            if (event?.action == KeyEvent.ACTION_UP) {
                // A hardware Enter is offered twice, going down and coming
                // up. It was dealt with going down.
                true
            } else if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_NULL
            ) {
                val stored = Prefs.setQrColorHex(this, input.text.toString())
                if (stored == null) {
                    Toast.makeText(this, R.string.qr_color_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    showStored()
                    input.clearFocus()
                    getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(input.windowToken, 0)
                }
                true
            } else {
                false
            }
        }

        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) showStored() }
    }

    override fun onStart() {
        super.onStart()
        // Both setup steps are done in Settings, which may well be open
        // beside this screen (split-screen, a foldable, a desktop window)
        // rather than over it — and then nothing resumes this activity when
        // they change. Watch the settings themselves while visible.
        for (name in WATCHED_SETTINGS) {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(name), false, setupObserver
            )
        }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(setupObserver)
        // Leaving mid-drag (rotation, another window taking over) may not end
        // the touch first: keep what the bubble is previewing rather than
        // leave it showing a value the setting doesn't have.
        if (opacityTracking) saveBubbleOpacity()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // Coming back from the window beside this one: catch whatever the
        // observer has no way of seeing (the bubble switch, say, after the
        // service was turned on over there).
        if (isTopResumedActivity) updateStatus()
    }

    private fun updateStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true ||
            Settings.Secure.getString(contentResolver, VOICE_INTERACTION_SERVICE)
                ?.contains(packageName) == true

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val fallbackEnabled = enabledServices.contains(packageName)

        showStatus(
            R.id.status_assistant, isAssistant,
            R.string.status_assistant_on, R.string.status_assistant_off
        )
        showStatus(
            R.id.status_accessibility, fallbackEnabled,
            R.string.status_fallback_on, R.string.status_fallback_off
        )

        if (!fallbackEnabled) {
            disclosureOffered = false
        } else if (!Prefs.hasAccessibilityConsent(this) && !disclosureOffered) {
            disclosureOffered = true
            disclosureForRunningService.launch(Intent(this, DisclosureActivity::class.java))
        }

        syncingSwitches = true
        val bubbleEnabled = ScreenshotAccessibilityService.isBubbleEnabled(this)
        findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked = bubbleEnabled
        // Not under the user's finger: this also runs when a watched setting
        // changes, which can be mid-drag.
        if (!opacityTracking) {
            findViewById<Slider>(R.id.slider_bubble_opacity).value =
                ScreenshotAccessibilityService.getBubbleOpacity(this).toFloat()
        }
        setOpacityRowVisible(bubbleEnabled)

        findViewById<MaterialSwitch>(R.id.switch_freeform).isChecked =
            Prefs.isFreeformCrop(this)

        findViewById<MaterialSwitch>(R.id.switch_snap).isChecked =
            Prefs.isSnapEnabled(this)

        findViewById<MaterialSwitch>(R.id.switch_qr).isChecked =
            Prefs.isQrDetectEnabled(this)
        syncingSwitches = false
    }

    /** The opacity slider only means something while the bubble is on. */
    private fun setOpacityRowVisible(visible: Boolean) {
        findViewById<View>(R.id.bubble_opacity_row).visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /** End a slider drag: store the value the bubble has been previewing. */
    private fun saveBubbleOpacity() {
        opacityTracking = false
        ScreenshotAccessibilityService.setBubbleOpacity(
            this, findViewById<Slider>(R.id.slider_bubble_opacity).value.roundToInt()
        )
    }

    /** A setup step's state line. The ✓/✗ in the text carries the meaning;
     *  the color only makes it scannable among the body text around it. */
    private fun showStatus(viewId: Int, done: Boolean, doneText: Int, pendingText: Int) {
        val view = findViewById<TextView>(viewId)
        view.setText(if (done) doneText else pendingText)
        view.setTextColor(
            MaterialColors.getColor(
                view,
                if (done) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError
            )
        )
    }

    /**
     * Ask the system to show its "add this tile?" prompt. The system silently
     * returns TILE_ALREADY_ADDED without a dialog when the tile is in place,
     * so that case needs its own feedback or the button looks broken.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        // Nullable like the RoleManager lookup above — absent on form factors
        // and stripped AOSP images with no status bar service.
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            Toast.makeText(this, R.string.tile_add_failed, Toast.LENGTH_LONG).show()
            return
        }
        statusBar.requestAddTileService(
            ComponentName(this, CaptureTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_app_glyph),
            mainExecutor
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    Toast.makeText(this, R.string.tile_already_added, Toast.LENGTH_SHORT).show()
                // The user saw the dialog and answered (added, or declined /
                // dismissed) — the outcome is on screen already, so stay quiet.
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit
                // Everything else is a TILE_ADD_REQUEST_ERROR_* (1000+), e.g.
                // a request already in progress from a double-tap. No dialog is
                // shown for those, so without this the button looks dead.
                else -> Toast.makeText(this, R.string.tile_add_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The accessibility service is running and may be used: the tile, the
     *  bubble and snapping all need both. */
    private fun fallbackUsable(): Boolean =
        ScreenshotAccessibilityService.instance != null && Prefs.hasAccessibilityConsent(this)

    private fun openAccessibilitySettings() =
        openFirstAvailable(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** The disclosure was declined. A service that is on anyway holds back
     *  for want of consent (see ScreenshotAccessibilityService), but leaving
     *  it enabled would show a setup step as done that does nothing. */
    private fun turnOffUnconsentedService() {
        val service = ScreenshotAccessibilityService.instance ?: return
        service.disableSelf()
        Toast.makeText(this, R.string.fallback_turned_off, Toast.LENGTH_SHORT).show()
    }

    private fun openFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.error_no_settings, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        // Settings.Secure.VOICE_INTERACTION_SERVICE: readable, but hidden
        // from the SDK.
        const val VOICE_INTERACTION_SERVICE = "voice_interaction_service"

        const val STATE_DISCLOSURE_OFFERED = "disclosure_offered"

        val WATCHED_SETTINGS = listOf(
            VOICE_INTERACTION_SERVICE,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }
}
