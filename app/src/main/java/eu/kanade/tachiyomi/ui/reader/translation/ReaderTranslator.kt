package eu.kanade.tachiyomi.ui.reader.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Translates one manga page: finds the speech bubbles, reads them with on-device OCR, translates the text with
 * Google Translate and draws the result back into the page image.
 */
class ReaderTranslator(
    private val context: Application = Injekt.get(),
    private val prefs: TranslationPreferences = Injekt.get(),
) {

    private val ocr = OcrEngine()
    private val google = GoogleTranslate()
    private val lock = Mutex()
    private val cacheDir = File(context.cacheDir, "translated")
    private val detected = ConcurrentHashMap<String, OcrScript>()
    private var writes = 0
    private var lastToast = 0L

    /** One piece of text found on the page, in bitmap pixels. */
    private class Found(
        val box: Box,
        val dark: Boolean,
        val text: String,
        val script: OcrScript,
    )

    /** A text block waiting to be read. */
    private class Candidate(
        val bubble: Bubble,
        val block: TextBlock,
        val gray: Gray,
        val det: Detection,
        val dark: Boolean,
        val offsetY: Int,
        val rect: Boolean,
        val blockCount: Int,
    )

    /** Already translated copy of this page from the disk cache, or null (not translated yet / nothing to translate). */
    fun cached(bytes: ByteArray): ByteArray? {
        if (!prefs.enabled.get() || !prefs.cacheResults.get()) return null
        return readCache(cacheKey(bytes))?.takeIf { it.isNotEmpty() }
    }

    /** Same as the other [translate] but looks the language up from the manga source. */
    suspend fun translate(bytes: ByteArray, sourceId: Long?): ByteArray? {
        val lang = sourceId
            ?.let { Injekt.get<SourceManager>().get(it)?.lang }
            ?.takeIf { it.isNotBlank() }
        return translate(bytes, sourceId?.toString() ?: "unknown", lang)
    }

    /**
     * Returns the translated page as JPEG bytes, or null when nothing was translated (translation off, no text, or
     * failure) and the original should be shown.
     */
    suspend fun translate(bytes: ByteArray, sourceKey: String, sourceLang: String?): ByteArray? {
        if (!prefs.enabled.get() || bytes.size > MAX_BYTES) return null
        val useCache = prefs.cacheResults.get()
        val key = withContext(Dispatchers.Default) { cacheKey(bytes) }
        if (useCache) readCache(key)?.let { return it.takeIf { b -> b.isNotEmpty() } }
        return lock.withLock {
            if (useCache) readCache(key)?.let { return@withLock it.takeIf { b -> b.isNotEmpty() } }
            try {
                withContext(Dispatchers.Default) { process(bytes, key, useCache, sourceKey, sourceLang) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Page translation failed" }
                reportError(e)
                null
            }
        }
    }

    // region pipeline

    private suspend fun process(
        bytes: ByteArray,
        key: String,
        useCache: Boolean,
        sourceKey: String,
        sourceLang: String?,
    ): ByteArray? {
        val bitmap = decode(bytes) ?: return null
        try {
            val page = grayOf(bitmap)
            val candidates = findCandidates(page)
            if (candidates.isEmpty()) {
                if (useCache) writeCache(key, ByteArray(0))
                return null
            }
            val script = resolveScript(candidates, sourceKey, sourceLang) ?: return null
            val target = prefs.targetLanguage.get()
            if (script.google.substringBefore('-') == target) return null

            val found = ArrayList<Found>()
            for (c in candidates) {
                val text = readBlock(c.gray, c.det, c.block, script) ?: continue
                found.add(Found(overlayBox(c, bitmap.width.toFloat() / page.w), c.dark, text, script))
            }
            if (found.isEmpty()) {
                if (useCache) writeCache(key, ByteArray(0))
                return null
            }

            val translated = google.translate(found.map { it.text }, script, target, prefs.googleApiKey.get().trim())
            val items = ArrayList<RenderItem>()
            found.forEachIndexed { i, f ->
                val tr = translated[i] ?: return@forEachIndexed
                items.add(RenderItem(f.box.x0.toFloat(), f.box.y0.toFloat(), f.box.x1.toFloat(), f.box.y1.toFloat(), tr, f.dark))
            }
            if (items.isEmpty()) return null

            PageRenderer.render(bitmap, items, target in TranslationPreferences.RTL_LANGUAGES)
            val out = ByteArrayOutputStream(bytes.size)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val result = out.toByteArray()
            if (useCache) writeCache(key, result)
            return result
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return it }
        }
        // formats the platform cannot decode (JXL and friends)
        val decoded = ImageDecoder.newInstance(ByteArrayInputStream(bytes))?.decode() ?: return null
        if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) return decoded
        return decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
    }

    /** Grayscale copy of the page, at most [DETECT_WIDTH] pixels wide. */
    private fun grayOf(bitmap: Bitmap): Gray {
        val w = bitmap.width
        val h = bitmap.height
        val full = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                full[base + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        }
        val gray = Gray(w, h, full)
        return if (w > DETECT_WIDTH) gray.scaled(DETECT_WIDTH.toFloat() / w) else gray
    }

    private fun findCandidates(page: Gray): List<Candidate> {
        val target = max(1200, (page.w * 1.6f).toInt())
        val cuts = if (page.h > target * 1.25f) {
            BubbleDetector.cutRows(page, target)
        } else {
            listOf(Cut(0, true), Cut(page.h, true))
        }
        val overlap = (page.w * 0.4f).toInt()
        val out = ArrayList<Candidate>()
        for (i in 0 until cuts.size - 1) {
            val own0 = cuts[i].y
            val own1 = cuts[i + 1].y
            val y0 = if (i > 0 && !cuts[i].clean) max(0, own0 - overlap) else own0
            val y1 = if (i < cuts.size - 2 && !cuts[i + 1].clean) min(page.h, own1 + overlap) else own1
            if (y1 - y0 < 30) continue
            val slice = Gray(page.w, y1 - y0, page.px.copyOfRange(y0 * page.w, y1 * page.w))
            collect(slice, y0, own0, own1, out)
        }
        return out
    }

    private fun collect(slice: Gray, y0: Int, own0: Int, own1: Int, out: MutableList<Candidate>) {
        fun owned(b: TextBlock) = (y0 + (b.box.y0 + b.box.y1) / 2) in own0 until own1

        val det = BubbleDetector.detect(slice)
        for (bubble in det.bubbles) {
            val blocks = bubble.blocks.filter { owned(it) && it.size >= 10 }
            for (block in blocks) {
                out.add(Candidate(bubble, block, slice, det, false, y0, bubble.isRect, blocks.size))
            }
        }
        // dark caption boxes with light text
        val inverted = slice.inverted()
        val invDet = BubbleDetector.detect(inverted)
        for (bubble in invDet.bubbles) {
            if (bubble.fillFull < 0.7f) continue
            if (det.bubbles.any { overlapRatio(it.white, bubble.white) > 0.3f }) continue
            val blocks = bubble.blocks.filter { owned(it) && it.size >= 10 }
            for (block in blocks) {
                out.add(Candidate(bubble, block, inverted, invDet, true, y0, true, blocks.size))
            }
        }
    }

    private fun overlapRatio(a: Box, b: Box): Float {
        val ix = min(a.x1, b.x1) - max(a.x0, b.x0) + 1
        val iy = min(a.y1, b.y1) - max(a.y0, b.y0) + 1
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix.toLong() * iy
        return inter.toFloat() / min(a.width.toLong() * a.height, b.width.toLong() * b.height)
    }

    /** The rectangle (in bitmap pixels) that will be painted over the original text. */
    private fun overlayBox(c: Candidate, toBitmap: Float): Box {
        val wb = c.bubble.white
        val ix0 = wb.x0 + 3
        val iy0 = wb.y0 + 3
        val ix1 = wb.x1 - 3
        val iy1 = wb.y1 - 3
        val pad = 6
        var x0 = c.block.box.x0 - pad
        var y0 = c.block.box.y0 - pad
        var x1 = c.block.box.x1 + pad
        var y1 = c.block.box.y1 + pad
        if (c.blockCount == 1) {
            val k = if (c.rect) 0.96f else 0.74f
            val cx = (wb.x0 + wb.x1) / 2f
            val cy = (wb.y0 + wb.y1) / 2f
            val hw = (wb.x1 - wb.x0) / 2f * k
            val hh = (wb.y1 - wb.y0) / 2f * k
            x0 = min(x0, (cx - hw).toInt())
            y0 = min(y0, (cy - hh).toInt())
            x1 = max(x1, (cx + hw).toInt())
            y1 = max(y1, (cy + hh).toInt())
        }
        x0 = max(ix0, x0)
        y0 = max(iy0, y0)
        x1 = min(ix1, x1)
        y1 = min(iy1, y1)
        return Box(
            (x0 * toBitmap).toInt(),
            ((y0 + c.offsetY) * toBitmap).toInt(),
            (x1 * toBitmap).toInt(),
            ((y1 + c.offsetY) * toBitmap).toInt(),
        )
    }

    // endregion

    // region OCR

    private suspend fun readBlock(gray: Gray, det: Detection, block: TextBlock, script: OcrScript): String? {
        val variants = ArrayList<Gray>(2)
        if (script.isCjk && (block.vertical || block.ambiguous)) {
            variants.add(BubbleDetector.horizontalize(gray, block, det.dark, det.owner))
        }
        if (!script.isCjk || !block.vertical || block.ambiguous) {
            variants.add(BubbleDetector.cropForOcr(gray, block, det.dark, det.owner))
        }
        val targetSize = if (script.isCjk) TARGET_CJK else TARGET_LATIN
        val factor = (targetSize.toFloat() / max(8, block.size)).coerceIn(0.5f, 3f)
        var best: String? = null
        var bestScore = 0f
        for (v in variants) {
            val img = if (factor in 0.95f..1.05f) v else v.scaled(factor)
            val text = TextCleanup.cleanText(ocr.readLines(img, script), script)
            if (!TextCleanup.plausible(text, script)) continue
            val score = TextCleanup.fit(script, text) * (0.5f + 0.5f * min(1f, text.length / 6f))
            if (best == null || score > bestScore) {
                best = text
                bestScore = score
            }
        }
        return best
    }

    /** Which script to read: the setting, then the source's language, then a one-off trial reading per source. */
    private suspend fun resolveScript(candidates: List<Candidate>, sourceKey: String, sourceLang: String?): OcrScript? {
        val pref = prefs.sourceLanguage.get()
        if (pref != "auto") OcrScript.fromCode(pref)?.let { return it }
        OcrScript.fromCode(sourceLang)?.let { return it }
        detected[sourceKey]?.let { return it }

        val samples = candidates.sortedByDescending { it.block.box.width.toLong() * it.block.box.height }.take(3)
        var bestScript: OcrScript? = null
        var bestScore = 0f
        for (script in listOf(OcrScript.LATIN, OcrScript.CHINESE, OcrScript.JAPANESE, OcrScript.KOREAN)) {
            var total = 0f
            for (c in samples) {
                val text = readBlock(c.gray, c.det, c.block, script) ?: continue
                var s = TextCleanup.fit(script, text)
                if (script == OcrScript.JAPANESE) {
                    val kana = text.count { it in '぀'..'ヿ' }
                    s = if (kana > 0) min(1f, s + 0.15f) else s * 0.7f
                }
                total += s * min(1f, text.length / 3f)
            }
            val score = total / samples.size
            if (score > bestScore) {
                bestScore = score
                bestScript = script
            }
            if (script == OcrScript.LATIN && score >= 0.6f) break
        }
        val result = bestScript?.takeIf { bestScore >= 0.22f } ?: return null
        detected[sourceKey] = result
        return result
    }

    // endregion

    // region cache

    private fun cacheKey(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes)
        md.update(prefs.signature().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): ByteArray? {
        val f = File(cacheDir, key)
        if (!f.isFile) return null
        return try {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(key: String, data: ByteArray) {
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$key.tmp")
            tmp.writeBytes(data)
            tmp.renameTo(File(cacheDir, key))
            if (++writes % 10 == 0) trimCache()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not write translation cache" }
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    /** Size of the saved translated pages in bytes. */
    fun cacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        detected.clear()
    }

    // endregion

    private suspend fun reportError(e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastToast < TOAST_INTERVAL) return
        lastToast = now
        val message = e.message?.take(120) ?: e::class.java.simpleName
        withContext(Dispatchers.Main) { context.toast("Translation failed: $message") }
    }

    companion object {
        val instance: ReaderTranslator by lazy { ReaderTranslator() }

        private const val MAX_BYTES = 40 * 1024 * 1024
        private const val MAX_PIXELS = 24_000_000L
        private const val DETECT_WIDTH = 1000
        private const val JPEG_QUALITY = 92
        private const val MAX_CACHE = 300L * 1024 * 1024
        private const val TARGET_CJK = 36
        private const val TARGET_LATIN = 30
        private const val TOAST_INTERVAL = 15_000L
    }
}
