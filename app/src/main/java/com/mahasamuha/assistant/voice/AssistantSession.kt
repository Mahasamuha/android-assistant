package com.mahasamuha.assistant.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mahasamuha.assistant.AssistantApp
import com.mahasamuha.assistant.ui.AssistantTheme

/**
 * One assistant invocation: the panel and its conversation. The session isn't an Activity, so it
 * supplies the lifecycle and saved-state owners that ComposeView needs.
 */
class AssistantSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var speaker: Speaker
    private lateinit var controller: VoiceSessionController

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Session.onCreate")
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val app = context.applicationContext as AssistantApp
        speaker = PlatformSpeaker(context)
        controller = VoiceSessionController(
            scope = lifecycleScope,
            stt = PlatformSpeechToText(context),
            chat = app.chatClient,
            tools = app.sessionTools(),
            skills = app.skills,
            speaker = speaker,
            locationSource = app.locationSource,
            auth = app.auth,
            keyguard = context.getSystemService(KeyguardManager::class.java),
            hasMicPermission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        )
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setContent {
            val state by controller.state.collectAsState()
            AssistantTheme {
                SessionPanel(
                    state = state,
                    onListen = controller::listen,
                    onAnswer = controller::answer,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Session.onShow flags=${describeShowFlags(showFlags)} args=${describeBundle(args)}")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        controller.listen()
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
        // Dismissing the panel means "stop talking". Phase 3: when the session hides because it
        // launched something itself (e.g. a call), let the announcement finish instead.
        controller.stop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        Log.i(TAG, "Session.onDestroy")
        speaker.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
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
