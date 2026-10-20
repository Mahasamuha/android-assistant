package com.mahasamuha.assistant.voice

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mahasamuha.assistant.ui.AssistantTheme

/**
 * One assistant invocation. The session isn't an Activity, so it supplies the
 * lifecycle and saved-state owners that ComposeView needs.
 *
 * Phase 1: logs what the system hands us and speaks a test line from onShow,
 * to check whether the panel counts as visible for audio on Android 17.
 */
class AssistantSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakWhenReady = false

    // Main-thread only. If the session is destroyed mid-utterance, TTS shutdown waits for
    // the utterance to end (phase 1 showed the engine keeps playing after the panel closes).
    private var speaking = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Session.onCreate")
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        tts = TextToSpeech(context, ::onTtsInit)
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setContent {
            AssistantTheme {
                SessionPanel(onClose = { finish() })
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Session.onShow flags=${describeShowFlags(showFlags)} args=${describeBundle(args)}")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (ttsReady) speakTestLine() else speakWhenReady = true
    }

    override fun onHandleAssist(state: AssistState) {
        Log.i(
            TAG,
            "Session.onHandleAssist index=${state.index}/${state.count} " +
                "structure=${state.assistStructure?.activityComponent} " +
                "content=${state.assistContent != null} data=${describeBundle(state.assistData)}",
        )
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Log.i(TAG, "Session.onHandleScreenshot ${screenshot?.let { "${it.width}x${it.height}" }}")
    }

    override fun onHide() {
        Log.i(TAG, "Session.onHide")
        // Dismissing the panel means "stop talking". Phase 3: when the session hides because
        // it launched something itself (e.g. a call), skip this so the announcement finishes.
        if (speaking) {
            tts?.stop()
            speaking = false
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        Log.i(TAG, "Session.onDestroy speaking=$speaking")
        destroyed = true
        if (!speaking) shutdownTts()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun onTtsInit(status: Int) {
        Log.i(TAG, "TTS init status=$status")
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.i(TAG, "TTS onStart")
                mainHandler.post { speaking = true }
            }

            override fun onDone(utteranceId: String) {
                Log.i(TAG, "TTS onDone")
                mainHandler.post(::onUtteranceEnded)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                Log.i(TAG, "TTS onStop interrupted=$interrupted")
                mainHandler.post(::onUtteranceEnded)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.w(TAG, "TTS onError")
                mainHandler.post(::onUtteranceEnded)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                Log.w(TAG, "TTS onError code=$errorCode")
                mainHandler.post(::onUtteranceEnded)
            }
        })
        if (speakWhenReady) {
            speakWhenReady = false
            speakTestLine()
        }
    }

    private fun onUtteranceEnded() {
        speaking = false
        if (destroyed) shutdownTts()
    }

    private fun shutdownTts() {
        Log.i(TAG, "TTS shutdown")
        tts?.shutdown()
        tts = null
    }

    // Long enough to close the panel mid-sentence and hear whether audio stops.
    private fun speakTestLine() {
        val result = tts?.speak(
            "Assistant panel open. This is a test line to check audio while the panel is visible.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "test-line",
        )
        Log.i(TAG, "TTS speak result=$result")
    }

    private companion object {
        val SHOW_FLAG_NAMES = mapOf(
            SHOW_WITH_ASSIST to "WITH_ASSIST",
            SHOW_WITH_SCREENSHOT to "WITH_SCREENSHOT",
            SHOW_SOURCE_ASSIST_GESTURE to "SOURCE_ASSIST_GESTURE",
            SHOW_SOURCE_APPLICATION to "SOURCE_APPLICATION",
            SHOW_SOURCE_ACTIVITY to "SOURCE_ACTIVITY",
            SHOW_SOURCE_PUSH_TO_TALK to "SOURCE_PUSH_TO_TALK",
            SHOW_SOURCE_NOTIFICATION to "SOURCE_NOTIFICATION",
            SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI to "SOURCE_AUTOMOTIVE_SYSTEM_UI",
        )

        fun describeShowFlags(flags: Int): String {
            val known = SHOW_FLAG_NAMES.filterKeys { flags and it != 0 }.values
            val unknown = flags and SHOW_FLAG_NAMES.keys.fold(0) { acc, bit -> acc or bit }.inv()
            return (known + if (unknown != 0) listOf("0x%x".format(unknown)) else emptyList())
                .joinToString("|").ifEmpty { "0" }
        }

        @Suppress("DEPRECATION")
        fun describeBundle(bundle: Bundle?): String =
            bundle?.keySet()?.joinToString(prefix = "{", postfix = "}") { "$it=${bundle.get(it)}" } ?: "null"
    }
}
