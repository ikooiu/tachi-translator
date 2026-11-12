package eu.kanade.tachiyomi.ui.reader.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device fallback for Google translation failures. ML Kit models download once and then work offline. */
internal class MLKitTranslate {
    private class ClientState(val translator: Translator) {
        val modelLock = Mutex()
        val lastWarmUp = AtomicLong(0L)

        @Volatile var modelReady = false
    }

    private val clients = ConcurrentHashMap<String, ClientState>()

    /** Starts model preparation without blocking the page's primary Google translation. */
    fun warmUp(source: String, target: String) {
        val state = stateOrNull(source, target) ?: return
        val now = System.currentTimeMillis()
        val previous = state.lastWarmUp.get()
        if (now - previous < WARMUP_RETRY_INTERVAL || !state.lastWarmUp.compareAndSet(previous, now)) return
        warmUpScope.launch {
            try {
                ensureModel(state)
            } catch (_: Exception) {
                // The model may be downloaded later when the network is available or on a later page.
            }
        }
    }

    /** Translates one text after ensuring the required offline model is installed. */
    suspend fun translate(text: String, source: String, target: String): String {
        val state = stateOrNull(source, target) ?: error("ML Kit does not support $source to $target translation")
        ensureModel(state)
        return state.translator.translate(text).awaitTask().trim().takeIf { it.isNotEmpty() }
            ?: error("ML Kit returned an empty translation")
    }

    private suspend fun ensureModel(state: ClientState) {
        state.modelLock.withLock {
            if (!state.modelReady) {
                state.translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
                state.modelReady = true
            }
        }
    }

    private fun stateOrNull(source: String, target: String): ClientState? {
        val sourceCode = source.trim().lowercase().substringBefore('-')
        val targetCode = target.trim().lowercase().substringBefore('-')
        val available = TranslateLanguage.getAllLanguages()
        if (sourceCode == targetCode || sourceCode !in available || targetCode !in available) return null
        val key = "$sourceCode|$targetCode"
        return clients.computeIfAbsent(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()
            ClientState(Translation.getClient(options))
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel(CancellationException("ML Kit task was cancelled"))
        }
    }

    private companion object {
        const val WARMUP_RETRY_INTERVAL = 10 * 60 * 1000L
        val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
