package eu.woshicado.circletoshare

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt

/** Small app-wide preferences (same store the accessibility service uses). */
object Prefs {
    private const val NAME = "cts"
    private const val KEY_FREEFORM = "freeform_crop"
    private const val KEY_SNAP = "snap_to_content"
    private const val KEY_SNAP_EDGES = "snap_to_edges"
    private const val KEY_QR = "qr_detect"
    private const val KEY_QR_COLOR = "qr_color"
    private const val KEY_A11Y_CONSENT = "a11y_consent"
    private const val KEY_CAPTURE_MORE = "capture_more"
    private const val KEY_TRIGGERS_EXPANDED = "triggers_expanded"
    private const val KEY_CAPTURE_MORE_STEP = "capture_more_step"
    private const val KEY_CAPTURE_MORE_STEP_SIDEWAYS = "capture_more_step_sideways"
    private const val KEY_CAPTURE_MORE_HANDLES = "capture_more_handles"

    // The apps with settings of their own (see setAppOverride), and where
    // each of an app's is kept.
    private const val KEY_APP_OVERRIDES = "scrollshot_apps"
    private fun appKey(packageName: String, setting: String) = "scrollshot_app.$packageName.$setting"

    /** Where [setting] is kept for a capture of [packageName], if that is
     *  an app with settings of its own; null if the general one counts. */
    private fun ownKey(prefs: SharedPreferences, packageName: String?, setting: String): String? =
        packageName
            ?.takeIf { prefs.getStringSet(KEY_APP_OVERRIDES, null)?.contains(it) == true }
            ?.let { appKey(it, setting) }
    private const val APP_STEP = "step"
    private const val APP_STEP_SIDEWAYS = "step_sideways"
    private const val APP_HANDLES = "handles"
    private const val APP_SNAP = "snap"

    // How much of the list one "Capture more" step scrolls, in percent of
    // the list's height. Half by default: the other half is on screen in
    // both frames, to line them up by. Less is surer (more to go by) and
    // slower; more is the other way round, and at the top end leaves too
    // little in common to join pages that change as they go.
    const val CAPTURE_MORE_STEP_MIN = 20
    const val CAPTURE_MORE_STEP_MAX = 80
    const val CAPTURE_MORE_STEP_STEP = 5
    const val CAPTURE_MORE_STEP_DEFAULT = 50
    // Sideways, a row is a pager more often than not, and a pager let go of
    // before its middle slides back to the page it was on: well past it.
    const val CAPTURE_MORE_STEP_SIDEWAYS_DEFAULT = 70

    /** Default QR highlight color (gold). */
    const val QR_COLOR_DEFAULT = "#FFCA28"
    // Last delivered crop, in bitmap pixels, plus the source dimensions it was
    // taken against. Only a rectangle is stored — never any image data.
    private const val KEY_CROP_L = "last_crop_l"
    private const val KEY_CROP_T = "last_crop_t"
    private const val KEY_CROP_R = "last_crop_r"
    private const val KEY_CROP_B = "last_crop_b"
    private const val KEY_CROP_W = "last_crop_w"
    private const val KEY_CROP_H = "last_crop_h"

    /**
     * True once the user has agreed to the accessibility disclosure
     * ([DisclosureActivity]). The accessibility service does nothing without
     * it — being switched on in Settings is not an answer to what it reads.
     */
    fun hasAccessibilityConsent(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_A11Y_CONSENT, false)

    fun setAccessibilityConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_A11Y_CONSENT, granted).apply()
    }

    /** True = draw a freeform circle (default); false = drag a rectangle. */
    fun isFreeformCrop(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FREEFORM, true)

    fun setFreeformCrop(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FREEFORM, enabled).apply()
    }

    /**
     * True = crop edges snap to on-screen element bounds (needs the
     * accessibility service). Off by default.
     *
     * With [packageName]: for a capture of that app — its own setting if it
     * has one (see [setAppOverride]), this one otherwise.
     */
    fun isSnapEnabled(context: Context, packageName: String? = null): Boolean {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val general = prefs.getBoolean(KEY_SNAP, false)
        return ownKey(prefs, packageName, APP_SNAP)?.let { prefs.getBoolean(it, general) } ?: general
    }

    /** True if some app's own snap setting is not the general one: only
     *  then does it matter which app a capture is of. */
    fun snapDependsOnApp(context: Context): Boolean {
        val general = isSnapEnabled(context)
        return getAppOverrides(context).any { isSnapEnabled(context, it) != general }
    }

    /** [packageName] null: the general setting. Else that app's own, which
     *  only counts while the app has an override. */
    fun setSnapEnabled(context: Context, enabled: Boolean, packageName: String? = null) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(packageName?.let { appKey(it, APP_SNAP) } ?: KEY_SNAP, enabled)
            .apply()
    }

    // --- Settings of an app's own ----------------------------------------------

    /** The apps (package names) that have settings of their own for what
     *  can differ from app to app: the scrollshot step, the removal of
     *  scroll handles, and snapping. */
    fun getAppOverrides(context: Context): Set<String> =
        // (A copy: the set SharedPreferences hands out must not be changed.)
        HashSet(
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_APP_OVERRIDES, emptySet()) ?: emptySet()
        )

    /**
     * Give [packageName] settings of its own, starting out as the general
     * ones are now — or, with [enabled] false, take them away again: the
     * app is back on the general ones, and what it had is forgotten.
     */
    fun setAppOverride(context: Context, packageName: String, enabled: Boolean) {
        val apps = getAppOverrides(context).toMutableSet()
        val editor = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
        if (enabled) {
            if (!apps.add(packageName)) return
            editor.putInt(appKey(packageName, APP_STEP), getCaptureMoreStep(context))
                .putInt(appKey(packageName, APP_STEP_SIDEWAYS), getCaptureMoreStepSideways(context))
                .putBoolean(appKey(packageName, APP_HANDLES), isCaptureMoreHandleRemovalEnabled(context))
                .putBoolean(appKey(packageName, APP_SNAP), isSnapEnabled(context))
        } else {
            apps.remove(packageName)
            editor.remove(appKey(packageName, APP_STEP))
                .remove(appKey(packageName, APP_STEP_SIDEWAYS))
                .remove(appKey(packageName, APP_HANDLES))
                .remove(appKey(packageName, APP_SNAP))
        }
        editor.putStringSet(KEY_APP_OVERRIDES, apps).apply()
    }

    /** True = the crop tool offers scrollshots: the "More above" and "More
     *  below" buttons (EXPERIMENTAL; needs the accessibility service). Off by
     *  default: who has them has switched them on, and been told then what
     *  experimental means (see ExperimentalNoticeDialog). */
    fun isCaptureMoreEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAPTURE_MORE, false)

    fun setCaptureMoreEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CAPTURE_MORE, enabled).apply()
    }

    /**
     * Always a value the settings slider accepts: in range and on a step.
     * With [packageName]: for a scrollshot of that app — its own step if it
     * has one (see [setAppOverride]), the general one otherwise.
     */
    fun getCaptureMoreStep(context: Context, packageName: String? = null): Int =
        step(context, packageName, KEY_CAPTURE_MORE_STEP, APP_STEP, CAPTURE_MORE_STEP_DEFAULT)

    /** As [getCaptureMoreStep], for a row that scrolls sideways: a share of
     *  the row's width. */
    fun getCaptureMoreStepSideways(context: Context, packageName: String? = null): Int =
        step(context, packageName, KEY_CAPTURE_MORE_STEP_SIDEWAYS, APP_STEP_SIDEWAYS, CAPTURE_MORE_STEP_SIDEWAYS_DEFAULT)

    private fun step(context: Context, packageName: String?, key: String, appSetting: String, default: Int): Int {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val general = prefs.getInt(key, default)
        val stored = ownKey(prefs, packageName, appSetting)?.let { prefs.getInt(it, general) } ?: general
        val steps = ((stored - CAPTURE_MORE_STEP_MIN).toFloat() / CAPTURE_MORE_STEP_STEP).roundToInt()
        return (CAPTURE_MORE_STEP_MIN + steps * CAPTURE_MORE_STEP_STEP)
            .coerceIn(CAPTURE_MORE_STEP_MIN, CAPTURE_MORE_STEP_MAX)
    }

    /** [packageName] as for [setSnapEnabled]. */
    fun setCaptureMoreStep(context: Context, percent: Int, packageName: String? = null) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(packageName?.let { appKey(it, APP_STEP) } ?: KEY_CAPTURE_MORE_STEP, percent)
            .apply()
    }

    /** [packageName] as for [setSnapEnabled]. */
    fun setCaptureMoreStepSideways(context: Context, percent: Int, packageName: String? = null) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(packageName?.let { appKey(it, APP_STEP_SIDEWAYS) } ?: KEY_CAPTURE_MORE_STEP_SIDEWAYS, percent)
            .apply()
    }

    /**
     * True = a scrollshot paints over a scrollbar's handle where it finds
     * one in a frame (default). A best guess, so it can be wrong: off,
     * every frame goes into the picture as it was taken. [packageName] as
     * for [getCaptureMoreStep].
     */
    fun isCaptureMoreHandleRemovalEnabled(context: Context, packageName: String? = null): Boolean {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val general = prefs.getBoolean(KEY_CAPTURE_MORE_HANDLES, true)
        return ownKey(prefs, packageName, APP_HANDLES)?.let { prefs.getBoolean(it, general) } ?: general
    }

    /** [packageName] as for [setSnapEnabled]. */
    fun setCaptureMoreHandleRemovalEnabled(
        context: Context, enabled: Boolean, packageName: String? = null
    ) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(packageName?.let { appKey(it, APP_HANDLES) } ?: KEY_CAPTURE_MORE_HANDLES, enabled)
            .apply()
    }

    /** True = a crop edge near the picture's border is pulled onto it
     *  (default): a finger seldom reaches the very edge of the screen. */
    fun isSnapToEdgesEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_SNAP_EDGES, true)

    fun setSnapToEdgesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SNAP_EDGES, enabled).apply()
    }

    /** Whether the settings screen shows its "How to open it" section
     *  unfolded: as the user left it, open to begin with. */
    fun isTriggersExpanded(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRIGGERS_EXPANDED, true)

    fun setTriggersExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRIGGERS_EXPANDED, expanded).apply()
    }

    /** True = scan each capture for QR codes and highlight them (default on).
     *  Decoding runs on-device (ZXing); the scan is per-capture, in memory
     *  only, and nothing is stored or sent anywhere. */
    fun isQrDetectEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_QR, true)

    fun setQrDetectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_QR, enabled).apply()
    }

    /** The QR highlight color as stored ("#RRGGBB"), for display in the
     *  settings field. */
    fun getQrColorHex(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_QR_COLOR, QR_COLOR_DEFAULT) ?: QR_COLOR_DEFAULT

    /** The QR highlight color as a color int. Falls back to the default if
     *  the stored value somehow doesn't parse. */
    fun getQrColor(context: Context): Int = try {
        Color.parseColor(getQrColorHex(context))
    } catch (e: IllegalArgumentException) {
        Color.parseColor(QR_COLOR_DEFAULT)
    }

    /**
     * Normalizes and stores [hex] as the QR highlight color. Accepts "RRGGBB"
     * with or without the leading "#", any case. Returns the stored "#RRGGBB"
     * form, or null (nothing stored) when the input isn't a valid color.
     */
    fun setQrColorHex(context: Context, hex: String): String? {
        val normalized = "#" + hex.trim().removePrefix("#").uppercase()
        if (!normalized.matches(Regex("^#[0-9A-F]{6}$"))) return null
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_QR_COLOR, normalized).apply()
        return normalized
    }

    /** Remember the last crop so it can be re-applied to a matching capture. */
    fun saveLastCrop(context: Context, rect: Rect, srcWidth: Int, srcHeight: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CROP_L, rect.left)
            .putInt(KEY_CROP_T, rect.top)
            .putInt(KEY_CROP_R, rect.right)
            .putInt(KEY_CROP_B, rect.bottom)
            .putInt(KEY_CROP_W, srcWidth)
            .putInt(KEY_CROP_H, srcHeight)
            .apply()
    }

    /**
     * The saved crop, but only if it was taken against a capture of the given
     * dimensions — a rectangle from a differently-sized screen is meaningless.
     * Returns null when nothing is stored or the dimensions don't match.
     */
    fun getLastCropFor(context: Context, srcWidth: Int, srcHeight: Int): Rect? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (p.getInt(KEY_CROP_W, 0) != srcWidth || p.getInt(KEY_CROP_H, 0) != srcHeight) {
            return null
        }
        val rect = Rect(
            p.getInt(KEY_CROP_L, 0),
            p.getInt(KEY_CROP_T, 0),
            p.getInt(KEY_CROP_R, 0),
            p.getInt(KEY_CROP_B, 0)
        )
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }
}
