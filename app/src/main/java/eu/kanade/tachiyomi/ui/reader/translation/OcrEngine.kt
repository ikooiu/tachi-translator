package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition (Google ML Kit, models are bundled in the APK, nothing is uploaded).
 * Only ever gets small crops of one text block, always laid out as ordinary horizontal text.
 */
internal class OcrEngine {

    private val clients = HashMap<OcrScript, TextRecognizer>()

    private fun client(script: OcrScript): TextRecognizer = synchronized(clients) {
        clients.getOrPut(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    /** Returns the recognised lines, top to bottom. */
    suspend fun readLines(gray: Gray, script: OcrScript): List<String> {
        val bitmap = gray.toBitmap()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = suspendCancellableCoroutine<Text> { cont ->
                client(script).process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return text.textBlocks.flatMap { block -> block.lines.map { it.text } }
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        synchronized(clients) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
    }

    /** ML Kit rejects images smaller than 32 px on either side, so small crops are padded with white. */
    private fun Gray.toBitmap(): Bitmap {
        val outW = maxOf(w, MIN_SIDE)
        val outH = maxOf(h, MIN_SIDE)
        val offX = (outW - w) / 2
        val offY = (outH - h) / 2
        val out = IntArray(outW * outH) { WHITE }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = at(x, y)
                out[(y + offY) * outW + (x + offX)] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private companion object {
        const val MIN_SIDE = 32
        const val WHITE = -1 // 0xFFFFFFFF
    }
}
