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

/**
 * Text-only Google Translate step. Only the extracted text is sent, never the image.
 * Free web endpoint by default, official Cloud Translation API when a key is set.
 */
internal class GoogleTranslate(
    private val network: NetworkHelper = Injekt.get(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val cache = ConcurrentHashMap<String, String>()
    private val permits = Semaphore(4)

    /** Translates every text. Result index matches input index; failed ones are null. Throws only if all failed. */
    suspend fun translate(texts: List<String>, source: OcrScript?, target: String, apiKey: String): List<String?> {
        val unique = texts.distinct()
        val results = HashMap<String, String?>()
        var lastError: Throwable? = null
        var ok = 0
        val sl = source?.google ?: "auto"
        val pending = ArrayList<String>()
        for (t in unique) {
            val hit = cache["$sl|$target|$t"]
            if (hit != null) {
                results[t] = hit
                ok++
            } else {
                pending.add(t)
            }
        }
        if (pending.isNotEmpty()) {
            coroutineScope {
                if (apiKey.isNotBlank()) {
                    pending.chunked(100).map { part ->
                        async {
                            permits.withPermit {
                                try {
                                    val out = official(part, sl, target, apiKey)
                                    part.forEachIndexed { i, t -> store(results, sl, target, t, out.getOrNull(i)) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastError = e
                                }
                            }
                        }
                    }.awaitAll()
                } else {
                    pending.map { t ->
                        async {
                            permits.withPermit {
                                try {
                                    store(results, sl, target, t, free(t, sl, target))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastError = e
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            ok += pending.count { results[it] != null }
        }
        if (ok == 0 && lastError != null) throw lastError!!
        return texts.map { results[it] }
    }

    private fun store(results: MutableMap<String, String?>, sl: String, tl: String, text: String, tr: String?) {
        val clean = tr?.let { fixPunctuation(it, tl) }?.takeIf { it.isNotBlank() } ?: return
        synchronized(results) { results[text] = clean }
        if (cache.size > CACHE_MAX) cache.clear()
        cache["$sl|$tl|$text"] = clean
    }

    private fun fixPunctuation(text: String, target: String): String {
        return if (target == "ar") TextCleanup.arabize(text) else text.trim()
    }

    private suspend fun free(text: String, sl: String, tl: String): String {
        val parts = TextCleanup.chunk(text, 1500)
        return parts.joinToString(" ") { freeOne(it, sl, tl) }
    }

    private suspend fun freeOne(text: String, sl: String, tl: String): String {
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(500L shl (attempt - 1))
            val host = if (attempt % 2 == 0) "translate.googleapis.com" else "translate.google.com"
            val url = "https://$host/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", sl)
                .addQueryParameter("tl", tl)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()
            try {
                val body = network.client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().use {
                    withContext(Dispatchers.IO) { it.body.string() }
                }
                return parseFree(body)
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
        val out = segments.joinToString("") { seg ->
            ((seg as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        if (out.isEmpty()) error("Google Translate returned no text")
        return out
    }

    private suspend fun official(texts: List<String>, sl: String, tl: String, key: String): List<String> {
        val payload = buildJsonObject {
            put("q", JsonArray(texts.map { JsonPrimitive(it) }))
            if (sl != "auto") put("source", sl)
            put("target", tl)
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
    }
}
