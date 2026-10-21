package com.mahasamuha.assistant

import android.app.Application
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.TokenStore

/** Holds the app-wide singletons; constructed by hand (no DI framework at this size). */
class AssistantApp : Application() {
    val config by lazy { AppConfig.fromBuildConfig() }
    val auth by lazy { AuthManager(this, config, TokenStore(this)) }
}
