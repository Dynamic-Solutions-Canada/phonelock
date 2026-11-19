package home.phonelock.parent

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Finds a QR code in camera frames with ZXing, entirely on the phone: nothing about the scan is sent
 * anywhere, so the app stays free of telemetry (LOG-9). One decoder is used per scanner screen, from its single
 * analysis thread; it is not thread-safe.
 *
 * Only the frame's brightness (the Y plane) is read. A QR code decodes the same at any rotation, so the
 * camera's rotation is not applied.
 */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The text of the QR code in a CameraX frame, or null when there is none. Doesn't close [frame]; the
     * caller does, once it is done with it.
     */
    fun decode(frame: ImageProxy): String? {
        val plane = frame.planes[0]
        return decode(plane.buffer, plane.rowStride, frame.width, frame.height)
    }

    /**
     * The text of the QR code in an image's brightness plane, as Android's camera and image readers hand it
     * over: [buffer] holds [height] rows of [rowStride] bytes, except that the last row may stop at [width].
     * Reads [buffer] without moving its position.
     */
    fun decode(buffer: ByteBuffer, rowStride: Int, width: Int, height: Int): String? {
        // Copy into a full stride-by-height array, padding a short last row.
        val luma = ByteArray(rowStride * height)
        val source = buffer.duplicate().apply { rewind() }
        source.get(luma, 0, minOf(source.remaining(), luma.size))
        return decode(luma, rowStride, width, height)
    }

    /**
     * The text of the QR code in a brightness frame, or null when there is none.
     *
     * [luma] holds one byte per pixel, row by row, [rowStride] bytes per row (at least [width]; camera rows are
     * often padded) and [height] rows.
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            // No code in this frame, or one too blurred to read: the next frame tries again.
            null
        } finally {
            reader.reset()
        }
    }

    companion object {
        /** [text] if it is a PhoneLock pairing link, else null, so other QR codes in view are ignored. */
        fun pairingLink(text: String?): String? = text?.takeIf { it.startsWith("phonelock://") }
    }
}
