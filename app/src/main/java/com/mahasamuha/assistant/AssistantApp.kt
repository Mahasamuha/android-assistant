package com.mahasamuha.assistant

import android.app.Application
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.TokenStore
import com.mahasamuha.assistant.llm.ChatClient
import com.mahasamuha.assistant.llm.GatewayChatClient
import com.mahasamuha.assistant.tools.ToolRegistry
import com.mahasamuha.assistant.voice.LocationSource
import com.mahasamuha.assistant.voice.PlatformLocationSource
import com.mahasamuha.assistant.weather.OpenMeteoWeather
import com.mahasamuha.assistant.weather.WeatherTool

/** Holds the app-wide singletons; constructed by hand (no DI framework at this size). */
class AssistantApp : Application() {
    val config by lazy { AppConfig.fromBuildConfig() }
    val auth by lazy { AuthManager(this, config, TokenStore(this)) }
    val chatClient: ChatClient by lazy { GatewayChatClient(config.gatewayUrls, auth) }
    val locationSource: LocationSource by lazy { PlatformLocationSource(this) }

    // Tools the model can call. The weather provider is the swap point (e.g. a keyed provider later).
    val tools by lazy { ToolRegistry(listOf(WeatherTool(OpenMeteoWeather(), locationSource))) }
}
