package com.mahasamuha.assistant

import android.net.Uri
import androidx.core.net.toUri

/** Public client settings from config.local.properties, via BuildConfig. No secrets. */
data class AppConfig(
    val issuer: Uri,
    val clientId: String,
    /** In priority order. v1 uses the first; the rest are for fallback later. */
    val gatewayUrls: List<Uri>,
    val redirectUri: Uri,
) {
    companion object {
        fun fromBuildConfig() = AppConfig(
            issuer = BuildConfig.ISSUER_URL.toUri(),
            clientId = BuildConfig.CLIENT_ID,
            gatewayUrls = BuildConfig.GATEWAY_URLS.split(",").map { it.trim().trimEnd('/').toUri() },
            redirectUri = "${BuildConfig.APPLICATION_ID}:/oauth2redirect".toUri(),
        )
    }
}
