package com.mahasamuha.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet

class SpeakerException(message: String) : Exception(message)

/** Text-to-speech. Sentences queue up and play in order. */
interface Speaker {
    suspend fun speak(sentence: String)

    /** Suspends until everything queued has finished playing (or was stopped). */
    suspend fun awaitIdle()

    fun stop()

    fun shutdown()
}

/**
 * Android's TextToSpeech. The engine plays in its own process, so speech continues after the panel
 * closes (docs/phase1-notes.md). Other audio is ducked while speaking.
 */
class PlatformSpeaker(context: Context) : Speaker {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val ready = CompletableDeferred<TextToSpeech>()
    private lateinit var tts: TextToSpeech
    private var retried = false

    // Utterances queued but not finished. Updated from the main thread and the engine's binder thread.
    private val pending = MutableStateFlow(0)
    private val nextId = AtomicInteger()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = utteranceEnded()

        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit // stop() resets the count itself

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = utteranceEnded()

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "TTS error $errorCode")
            utteranceEnded()
        }
    }

    init {
        create()
    }

    // The engine can fail to bind on the first try (seen on a fresh emulator), so retry once.
    private fun create() {
        tts = TextToSpeech(appContext) { status ->
            Log.i(TAG, "TTS init status=$status")
            when {
                status == TextToSpeech.SUCCESS -> {
                    tts.setAudioAttributes(attributes)
                    tts.setOnUtteranceProgressListener(progressListener)
                    ready.complete(tts)
                }
                !retried -> {
                    retried = true
                    tts.shutdown()
                    create()
                }
                else -> ready.completeExceptionally(SpeakerException("Text-to-speech unavailable"))
            }
        }
    }

    override suspend fun speak(sentence: String) {
        val engine = ready.await()
        if (pending.updateAndGet { it + 1 } == 1) audioManager.requestAudioFocus(focusRequest)
        if (engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, "u${nextId.incrementAndGet()}") != TextToSpeech.SUCCESS) {
            utteranceEnded()
            throw SpeakerException("Text-to-speech rejected the sentence")
        }
    }

    override suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    override fun stop() {
        if (ready.isCompleted && !ready.isCancelled) tts.stop()
        pending.value = 0
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun shutdown() {
        stop()
        if (::tts.isInitialized) tts.shutdown()
    }

    private fun utteranceEnded() {
        if (pending.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
