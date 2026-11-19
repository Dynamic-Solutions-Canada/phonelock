package home.phonelock.parent

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's decoder on Android itself: a pairing link drawn as a QR code into a real YUV_420_888 image,
 * the format CameraX gives the scanner, passed through Android's image queue and read back from its brightness
 * plane with the plane's own row stride. The JVM tests (QrFrameDecoderTest) cover the decoding rules; this
 * proves the same code reads Android's buffers.
 */
@RunWith(AndroidJUnit4::class)
class QrFrameDecoderDeviceTest {
    private val link = "phonelock://pair?s=https%3A%2F%2Fntfy.sh&t=pl-device-test&k=sndwiKxES8Ik8bBOiB9nlstt7reuBg2EURYMu7faX0I&n=Device"
    private val width = 640
    private val height = 480

    /**
     * Writes a frame through an [ImageWriter] into an [ImageReader] and hands the received image to [check].
     * [text] is drawn as a QR code 360 pixels square in the middle of a grey frame, or left out when null.
     */
    private fun withFrame(text: String?, check: (Image) -> Unit) {
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).use { reader ->
            ImageWriter.newInstance(reader.surface, 2, ImageFormat.YUV_420_888).use { writer ->
                val out = writer.dequeueInputImage()
                paint(out, text)
                writer.queueInputImage(out)

                // The frame arrives on the reader asynchronously; wait up to two seconds for it.
                var image: Image? = null
                repeat(100) {
                    image = image ?: reader.acquireNextImage()
                    if (image == null) Thread.sleep(20)
                }
                assertNotNull("no frame reached the image reader", image)
                image!!.use(check)
            }
        }
    }

    /** Fills [image]'s planes: grey brightness with [text] as a QR code, and neutral colour. */
    private fun paint(image: Image, text: String?) {
        val y = image.planes[0]
        val stride = y.rowStride
        val buffer = y.buffer
        for (row in 0 until height) {
            for (col in 0 until width) buffer.put(row * stride + col, 0x90.toByte())
        }
        if (text != null) {
            val size = 360
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val left = (width - size) / 2
            val top = (height - size) / 2
            for (row in 0 until size) {
                for (col in 0 until size) {
                    val dark = matrix.get(col, row)
                    buffer.put((top + row) * stride + left + col, if (dark) 0x10 else 0xF0.toByte())
                }
            }
        }
        // Neutral chroma, so the frame is a plain greyscale picture.
        for (plane in image.planes.drop(1)) {
            val b = plane.buffer
            for (i in 0 until b.capacity()) b.put(i, 0x80.toByte())
        }
    }

    @Test
    fun readsAPairingLinkFromAnAndroidYuvImage() {
        withFrame(link) { image ->
            val plane = image.planes[0]
            val text = QrFrameDecoder().decode(plane.buffer, plane.rowStride, image.width, image.height)
            assertEquals(link, QrFrameDecoder.pairingLink(text))
        }
    }

    @Test
    fun findsNothingInAnAndroidImageWithoutACode() {
        withFrame(null) { image ->
            val plane = image.planes[0]
            assertNull(QrFrameDecoder().decode(plane.buffer, plane.rowStride, image.width, image.height))
        }
    }
}
