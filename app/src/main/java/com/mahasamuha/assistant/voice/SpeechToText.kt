package com.mahasamuha.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
}

class SttException(val code: Int) : Exception("Speech recognition error $code") {
    val noSpeech get() = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
}

/** One utterance per [listen]: partial results, then a single [SttEvent.Final], then completion. */
interface SpeechToText {
    fun listen(): Flow<SttEvent>
}

/** The platform's on-device recognizer. Must be driven from the main thread. */
class PlatformSpeechToText(private val context: Context) : SpeechToText {

    override fun listen(): Flow<SttEvent> = callbackFlow {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        Log.i(TAG, "STT: on-device available=$onDevice")
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                firstResult(partialResults)?.let { trySend(SttEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle) {
                trySend(SttEvent.Final(firstResult(results).orEmpty()))
                close()
            }

            override fun onError(error: Int) {
                Log.i(TAG, "STT error $error")
                close(SttException(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "STT ready")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )

        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main)

    private fun firstResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
