package home.phonelock.parent

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner's decoding, on frames shaped like a camera's: a real pairing link drawn as a QR code on a grey
 * background, with rows padded beyond the image width as camera buffers often are.
 */
class QrFrameDecoderTest {
    private val link = "phonelock://pair?s=https%3A%2F%2Fntfy.sh&t=pl-test&k=sndwiKxES8Ik8bBOiB9nlstt7reuBg2EURYMu7faX0I&n=Test"

    /**
     * A [width] by [height] brightness frame with [text] as a QR code [size] pixels square in the middle, each
     * row [padding] bytes longer than the image. [rotate] turns the code a quarter turn, as a phone held
     * sideways would see it.
     */
    private fun frame(text: String?, width: Int = 640, height: Int = 480, size: Int = 360, padding: Int = 64, rotate: Boolean = false): Pair<ByteArray, Int> {
        val stride = width + padding
        val luma = ByteArray(stride * height) { 0x90.toByte() }
        if (text != null) {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val left = (width - size) / 2
            val top = (height - size) / 2
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val dark = if (rotate) m.get(y, size - 1 - x) else m.get(x, y)
                    luma[(top + y) * stride + left + x] = if (dark) 0x10 else 0xF0.toByte()
                }
            }
        }
        return luma to stride
    }

    @Test
    fun readsAPairingLinkFromAPaddedFrame() {
        val (luma, stride) = frame(link)
        assertEquals(link, QrFrameDecoder().decode(luma, stride, 640, 480))
    }

    @Test
    fun readsACodeTurnedAQuarterTurn() {
        val (luma, stride) = frame(link, rotate = true)
        assertEquals(link, QrFrameDecoder().decode(luma, stride, 640, 480))
    }

    @Test
    fun findsNothingInAFrameWithoutACode() {
        val (luma, stride) = frame(null)
        assertNull(QrFrameDecoder().decode(luma, stride, 640, 480))
    }

    @Test
    fun oneDecoderReadsFrameAfterFrame() {
        val decoder = QrFrameDecoder()
        val (blank, blankStride) = frame(null)
        val (code, codeStride) = frame(link)
        assertNull(decoder.decode(blank, blankStride, 640, 480))
        assertEquals(link, decoder.decode(code, codeStride, 640, 480))
    }

    @Test
    fun keepsOnlyPairingLinks() {
        assertEquals(link, QrFrameDecoder.pairingLink(link))
        assertNull(QrFrameDecoder.pairingLink("https://example.com/menu"))
        assertNull(QrFrameDecoder.pairingLink(null))
    }
}
