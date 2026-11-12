package eu.kanade.tachiyomi.ui.reader.translation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Text-only Google Translate step. Only extracted text is sent, never the image. Requests are deliberately paced
 * and limited to two concurrent calls because rapid parallel requests are more likely to be throttled.
 */
internal class GoogleTranslate(
    private val network: NetworkHelper = Injekt.get(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val cache = ConcurrentHashMap<String, String>()
    private val permits = Semaphore(2)
    private val requestPacer = Mutex()
    private var lastRequestAt = 0L

    /** Translates every text. Result index matches input index; failures are null for the caller to fall back. */
    suspend fun translate(texts: List<String>, source: OcrScript?, target: String, apiKey: String): List<String?> {
        val unique = texts.distinct()
        val results = HashMap<String, String?>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val sourceLanguage = source?.google ?: "auto"
        val pending = ArrayList<String>()
        for (text in unique) {
            val hit = cache["$sourceLanguage|$target|$text"]
            if (hit != null) {
                results[text] = hit
            } else {
                pending.add(text)
            }
        }

        if (pending.isNotEmpty()) {
            coroutineScope {
                if (apiKey.isNotBlank()) {
                    pending.chunked(100).map { part ->
                        async {
                            permits.withPermit {
                                try {
                                    val out = retryGoogle { official(part, sourceLanguage, target, apiKey) }
                                    part.forEachIndexed { i, text -> store(results, sourceLanguage, target, text, out.getOrNull(i)) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errors.add(e)
                                }
                            }
                        }
                    }.awaitAll()
                } else {
                    pending.map { text ->
                        async {
                            permits.withPermit {
                                try {
                                    store(results, sourceLanguage, target, text, free(text, sourceLanguage, target))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errors.add(e)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        val firstError = errors.peek()
        if (results.values.none { !it.isNullOrBlank() } && firstError != null) throw firstError
        return texts.map { results[it] }
    }

    private fun store(results: MutableMap<String, String?>, source: String, target: String, text: String, translated: String?) {
        val clean = translated?.let { fixPunctuation(it, target) }?.takeIf { it.isNotBlank() } ?: return
        synchronized(results) { results[text] = clean }
        if (cache.size > CACHE_MAX) cache.clear()
        cache["$source|$target|$text"] = clean
    }

    private fun fixPunctuation(text: String, target: String): String =
        if (target == "ar") TextCleanup.arabize(text) else text.trim()

    private suspend fun free(text: String, source: String, target: String): String {
        val parts = TextCleanup.chunk(text, 1500)
        return parts.map { freeOne(it, source, target) }.joinToString(" ")
    }

    private suspend fun freeOne(text: String, source: String, target: String): String = retryGoogle {
        paceRequest()
        val host = if ((System.nanoTime() and 1L) == 0L) "translate.googleapis.com" else "translate.google.com"
        val url = "https://$host/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", source)
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val body = network.client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().use {
            withContext(Dispatchers.IO) { it.body.string() }
        }
        parseFree(body)
    }

    private suspend fun paceRequest() {
        requestPacer.lock()
        try {
            val wait = MIN_REQUEST_INTERVAL - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        } finally {
            requestPacer.unlock()
        }
    }

    private suspend fun <T> retryGoogle(request: suspend () -> T): T {
        var last: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_BASE_DELAY shl (attempt - 1))
            try {
                return request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Google Translate failed")
    }

    private fun parseFree(body: String): String {
        val root = json.parseToJsonElement(body)
        val segments = (root as? JsonArray)?.firstOrNull() as? JsonArray ?: error("Unexpected Google Translate response")
        val out = segments.joinToString("") { segment ->
            ((segment as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        if (out.isEmpty()) error("Google Translate returned no text")
        return out
    }

    private suspend fun official(texts: List<String>, source: String, target: String, key: String): List<String> {
        paceRequest()
        val payload = buildJsonObject {
            put("q", JsonArray(texts.map { JsonPrimitive(it) }))
            if (source != "auto") put("source", source)
            put("target", target)
            put("format", "text")
        }
        val url = "https://translation.googleapis.com/language/translate/v2".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .build()
        val request = POST(
            url.toString(),
            body = payload.toString().toRequestBody("application/json".toMediaType()),
            cache = CacheControl.FORCE_NETWORK,
        )
        val body = network.client.newCall(request).awaitSuccess().use {
            withContext(Dispatchers.IO) { it.body.string() }
        }
        val translations = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("translations")?.jsonArray
            ?: error("Unexpected Cloud Translation response")
        return translations.map { (it as JsonObject)["translatedText"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private companion object {
        const val CACHE_MAX = 3000
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY = 800L
        const val MIN_REQUEST_INTERVAL = 350L
    }
}
