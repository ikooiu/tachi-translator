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
