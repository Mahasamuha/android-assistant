package com.mahasamuha.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.TokenStore
import com.mahasamuha.assistant.calls.PlaceCallTool
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.contacts.FindContactTool
import com.mahasamuha.assistant.llm.ChatClient
import com.mahasamuha.assistant.llm.GatewayChatClient
import com.mahasamuha.assistant.skills.BundledSkills
import com.mahasamuha.assistant.sms.SendSmsTool
import com.mahasamuha.assistant.sms.SmsSender
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

    private val weatherTool by lazy { WeatherTool(OpenMeteoWeather(), locationSource) }
    private val contacts by lazy { ContactDirectory(contentResolver, resources) }
    private val smsSender by lazy { SmsSender(this) }

    /**
     * Tools for one session: those whose permissions are granted right now, so a skill that needs a
     * missing one is left out (plan §4b). A remote tool with the same name will override a local one.
     * [launch] starts an activity from the session, which must happen while it's visible; [closePanel]
     * dismisses the session (after a call is placed).
     */
    fun sessionTools(launch: (Intent) -> Unit, closePanel: () -> Unit): ToolRegistry {
        val contactsAllowed = granted(Manifest.permission.READ_CONTACTS)
        return ToolRegistry(buildList {
            add(weatherTool)
            if (contactsAllowed) add(FindContactTool(contacts))
            if (granted(Manifest.permission.SEND_SMS)) add(SendSmsTool(smsSender, contacts.takeIf { contactsAllowed }))
            if (granted(Manifest.permission.CALL_PHONE)) {
                add(PlaceCallTool(
                    contacts.takeIf { contactsAllowed },
                    getSystemService(TelephonyManager::class.java),
                    getSystemService(TelecomManager::class.java),
                    launch,
                    closePanel,
                ))
            }
        })
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val skills by lazy { BundledSkills(assets).load() }
}
