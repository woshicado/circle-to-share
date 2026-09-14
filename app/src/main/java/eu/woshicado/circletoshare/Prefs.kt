package eu.woshicado.circletoshare

import android.content.Context
import android.graphics.Color
import android.graphics.Rect

/** Small app-wide preferences (same store the accessibility service uses). */
object Prefs {
    private const val NAME = "cts"
    private const val KEY_FREEFORM = "freeform_crop"
    private const val KEY_SNAP = "snap_to_content"
    private const val KEY_QR = "qr_detect"
    private const val KEY_QR_COLOR = "qr_color"

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

    /** True = draw a freeform circle (default); false = drag a rectangle. */
    fun isFreeformCrop(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FREEFORM, true)

    fun setFreeformCrop(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FREEFORM, enabled).apply()
    }

    /** True = crop edges snap to on-screen element bounds (needs the
     *  accessibility service). Off by default. */
    fun isSnapEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SNAP, false)

    fun setSnapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SNAP, enabled).apply()
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
