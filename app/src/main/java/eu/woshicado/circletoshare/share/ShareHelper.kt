package eu.woshicado.circletoshare.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.core.content.FileProvider
import eu.woshicado.circletoshare.R
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What to do with a crop. */
enum class Delivery { SHARE, COPY, SAVE }

/**
 * Shared and copied crops live only in the app's cache dir — never in the
 * gallery. Old files are swept on every session so nothing needs manual
 * cleanup. Only [Delivery.SAVE] puts a crop in the gallery, where it stays.
 */
object ShareHelper {

    private const val AUTHORITY = "eu.woshicado.circletoshare.fileprovider"
    private const val DIR = "shots"
    private const val MAX_AGE_MS = 60L * 60L * 1000L // 1 hour

    /**
     * Crops [source] to [rect] (null = whole bitmap) and opens the share sheet,
     * copies to the clipboard or saves to the gallery. Returns where the image
     * now is (for a save, its gallery entry), or null if there was nothing
     * valid to deliver. Throws only on unexpected failure — an
     * [IOException] when the image could not be written — which the caller
     * should report. Saying that a save worked is the caller's job too: a
     * toast from here goes unseen (see ScreenshotAccessibilityService.notify).
     */
    fun deliver(context: Context, source: Bitmap, rect: Rect?, action: Delivery): Uri? {
        // A copy: the caller's rect is not ours to clamp. intersect() leaves
        // the rect untouched when there is no overlap at all, so its result
        // is the "nothing valid to deliver" answer, not something to ignore.
        val bounds = Rect(rect ?: Rect(0, 0, source.width, source.height))
        if (!bounds.intersect(0, 0, source.width, source.height)) return null
        if (bounds.width() < 4 || bounds.height() < 4) return null

        val cropped = Bitmap.createBitmap(
            source, bounds.left, bounds.top, bounds.width(), bounds.height()
        )
        if (action == Delivery.SAVE) return saveToGallery(context, cropped)
        val uri = writeAndGetUri(context, cropped)

        if (action == Delivery.SHARE) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } else {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "screenshot", uri))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        return uri
    }

    /**
     * Open the saved image [uri] in the user's app for pictures — their
     * default, or the system's pick-an-app sheet if they have none. Returns
     * false if no app can show it.
     */
    fun view(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // As openQr: whatever starting it provokes stays here.
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** [message] followed by a "View" action, as a snackbar shows one: for
     *  a notice that opens the saved image when tapped. */
    fun withViewAction(context: Context, message: CharSequence): CharSequence =
        SpannableStringBuilder(message).append("   ").apply {
            val start = length
            append(context.getString(R.string.action_view))
            // A light tint of the launcher icon's purple, for the dark pill.
            setSpan(ForegroundColorSpan(Color.rgb(207, 196, 255)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    fun writeAndGetUri(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "crop-${System.currentTimeMillis()}.png")
        try {
            file.outputStream().use { out -> writePng(bitmap, out) }
        } catch (e: IOException) {
            // Don't leave half an image behind for something to pick up.
            file.delete()
            throw e
        }
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * Add [bitmap] to the gallery, in Pictures/Screenshots beside the
     * system's own screenshots. Needs no permission: since Android 10 an app
     * may add media to the shared collections without one.
     *
     * Every failure is an [IOException], whatever the media store threw:
     * storage that's full, or shared storage that isn't there at all (the
     * media store's IllegalStateException and kin), is "couldn't save" to
     * the user, not a failed capture.
     */
    private fun saveToGallery(context: Context, bitmap: Bitmap): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            // (A name already taken gets a " (1)" from the media store.)
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            // Environment.DIRECTORY_SCREENSHOTS only exists from Android 12.
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
            // Hidden from other apps until it is written in full.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            throw e as? IOException ?: IOException("the gallery refused the image", e)
        } ?: throw IOException("the gallery refused the image")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("the gallery entry could not be opened")
            out.use { writePng(bitmap, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            // As in writeAndGetUri: no half an image left behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e as? IOException ?: IOException("the image could not be saved", e)
        }
    }

    /**
     * Write [bitmap] to [out] as a PNG, or throw. compress() itself doesn't
     * throw when the stream it writes to does — storage running full — but
     * swallows the error and returns false; ignoring that hands a truncated
     * file on as if it were the image.
     */
    @Throws(IOException::class)
    internal fun writePng(bitmap: Bitmap, out: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw IOException("the image could not be written")
        }
    }

    fun cleanupCache(context: Context) {
        val dir = File(context.cacheDir, DIR)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
