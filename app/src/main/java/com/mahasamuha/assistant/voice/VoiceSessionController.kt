package com.mahasamuha.assistant.voice

import android.app.KeyguardManager
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.NotSignedInException
import com.mahasamuha.assistant.llm.ChatClient
import com.mahasamuha.assistant.llm.ChatException
import com.mahasamuha.assistant.llm.ChatMessage
import com.mahasamuha.assistant.llm.ChatMessage.Role
import com.mahasamuha.assistant.llm.ChatOptions
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PanelState(
    val phase: Phase = Phase.Starting,
    val heard: String = "",
    val reply: String = "",
    val error: String? = null,
) {
    enum class Phase { Starting, Listening, Thinking, Speaking, Idle }
}

/** A problem the user can act on; the message is spoken as is. */
private class UserFacingException(message: String) : Exception(message)

/**
 * One panel's conversation: listen, ask the LLM, speak the reply sentence by sentence.
 * History lives as long as the session (plan §2: memory per invocation).
 */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val stt: SpeechToText,
    private val chat: ChatClient,
    private val speaker: Speaker,
    private val locationSource: LocationSource,
    private val auth: AuthManager,
    private val keyguard: KeyguardManager,
    private val hasMicPermission: () -> Boolean,
) {
    private val _state = MutableStateFlow(PanelState())
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private val history = mutableListOf<ChatMessage>()
    private var turn: Job? = null

    /** Starts a new turn, interrupting any speech or turn in progress. */
    fun listen() {
        turn?.cancel()
        speaker.stop()
        turn = scope.launch { runTurn() }
    }

    fun stop() {
        turn?.cancel()
        speaker.stop()
    }

    private suspend fun runTurn() {
        _state.value = PanelState(phase = PanelState.Phase.Starting)
        var askedFollowUp = false
        try {
            checkPreconditions()
            // Look up the location while the user is talking, so it adds no delay.
            val location = scope.async { runCatching { locationSource.current() }.getOrNull() }
            val heard = try {
                hear()
            } catch (e: Exception) {
                location.cancel()
                throw e
            }
            if (heard.isBlank()) throw UserFacingException("I didn't catch that.")

            val place = location.await()
            if (history.isEmpty()) history += ChatMessage(Role.SYSTEM, VoicePrompt.system(place))
            history += ChatMessage(Role.USER, heard)
            _state.update { it.copy(phase = PanelState.Phase.Thinking) }

            val reply = try {
                streamAndSpeak(place)
            } catch (e: Exception) {
                history.removeAt(history.lastIndex) // keep history to completed exchanges
                throw e
            }
            history += ChatMessage(Role.ASSISTANT, reply)
            speaker.awaitIdle()
            _state.update { it.copy(phase = PanelState.Phase.Idle) }
            askedFollowUp = reply.trimEnd().endsWith("?")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            Log.w(TAG, "Turn failed: $message", e)
            _state.update { it.copy(phase = PanelState.Phase.Idle, error = message) }
            try {
                speaker.speak(message)
            } catch (speakError: Exception) {
                Log.w(TAG, "Couldn't speak the error", speakError)
            }
        }
        // The assistant asked something ("Which Sam?"), so listen for the answer without a tap.
        if (askedFollowUp) listen()
    }

    private fun checkPreconditions() {
        if (keyguard.isDeviceLocked) throw UserFacingException("Unlock your phone first.")
        if (!auth.signedIn.value) throw UserFacingException("You're signed out. Open the assistant app to sign in.")
        if (!hasMicPermission()) throw UserFacingException("I need microphone access. Open the assistant app to allow it.")
    }

    private suspend fun hear(): String {
        _state.update { it.copy(phase = PanelState.Phase.Listening) }
        var final = ""
        stt.listen().collect { event ->
            when (event) {
                is SttEvent.Partial -> _state.update { it.copy(heard = event.text) }
                is SttEvent.Final -> {
                    final = event.text
                    _state.update { it.copy(heard = event.text) }
                }
            }
        }
        return final
    }

    /** Streams the reply, speaking each sentence as soon as it's complete. Returns the full text. */
    private suspend fun streamAndSpeak(place: ApproximateLocation?): String {
        val splitter = SentenceSplitter()
        val reply = StringBuilder()
        chat.stream(history.toList(), ChatOptions(timeZone = ZoneId.systemDefault().id, location = place)).collect { delta ->
            reply.append(delta)
            _state.update { it.copy(reply = reply.toString()) }
            for (sentence in splitter.add(delta)) speakSentence(sentence)
        }
        splitter.flush()?.let { speakSentence(it) }
        return reply.toString()
    }

    private suspend fun speakSentence(sentence: String) {
        _state.update { it.copy(phase = PanelState.Phase.Speaking) }
        speaker.speak(sentence)
    }

    private fun userMessage(e: Exception): String = when (e) {
        is UserFacingException -> e.message.orEmpty()
        is NotSignedInException -> "You're signed out. Open the assistant app to sign in."
        is UserNotAuthenticatedException -> "Unlock your phone first."
        is ChatException.BudgetExceeded -> "The assistant's monthly budget is used up."
        is ChatException.RateLimited -> "That's too many requests. Try again in a moment."
        is ChatException.Unauthorized -> "The server didn't accept the sign-in. Open the assistant app and sign in again."
        is ChatException.Unavailable -> "I can't reach the assistant server right now."
        is SttException -> if (e.noSpeech) "I didn't catch that." else "Speech recognition failed."
        is SpeakerException -> "Text to speech isn't working."
        else -> "Something went wrong."
    }
}
