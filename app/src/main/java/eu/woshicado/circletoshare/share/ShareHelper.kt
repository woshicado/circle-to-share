package eu.woshicado.circletoshare.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import eu.woshicado.circletoshare.R
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Crops live only in the app's cache dir — never in the gallery. Old files
 * are swept on every session so nothing needs manual cleanup.
 */
object ShareHelper {

    private const val AUTHORITY = "eu.woshicado.circletoshare.fileprovider"
    private const val DIR = "shots"
    private const val MAX_AGE_MS = 60L * 60L * 1000L // 1 hour

    /**
     * Crops [source] to [rect] (null = whole bitmap) and either opens the share
     * sheet or copies to the clipboard. Returns false if there was nothing valid
     * to deliver. Throws only on unexpected failure — an [IOException] when
     * the image could not be written — which the caller should report.
     */
    fun deliver(context: Context, source: Bitmap, rect: Rect?, share: Boolean): Boolean {
        // A copy: the caller's rect is not ours to clamp. intersect() leaves
        // the rect untouched when there is no overlap at all, so its result
        // is the "nothing valid to deliver" answer, not something to ignore.
        val bounds = Rect(rect ?: Rect(0, 0, source.width, source.height))
        if (!bounds.intersect(0, 0, source.width, source.height)) return false
        if (bounds.width() < 4 || bounds.height() < 4) return false

        val cropped = Bitmap.createBitmap(
            source, bounds.left, bounds.top, bounds.width(), bounds.height()
        )
        val uri = writeAndGetUri(context, cropped)

        if (share) {
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
        return true
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
