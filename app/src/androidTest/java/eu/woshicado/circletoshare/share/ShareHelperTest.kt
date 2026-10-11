package eu.woshicado.circletoshare.share

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareHelperTest {

    private val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    @Test
    fun anImageIsWrittenAsAPng() {
        val out = ByteArrayOutputStream()
        ShareHelper.writePng(bitmap, out)

        val bytes = out.toByteArray()
        assertTrue(bytes.size > 8)
        assertTrue("PNG signature", bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte())
    }

    @Test
    fun aWriteThatFails_isReportedNotSwallowed() {
        // What storage running full looks like to the encoder.
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device")
            override fun write(b: ByteArray, off: Int, len: Int) =
                throw IOException("No space left on device")
        }
        try {
            ShareHelper.writePng(bitmap, full)
            fail("a failed write must not pass for a written image")
        } catch (expected: IOException) {
            // as it should be
        }
    }
}
