import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Real endpoints live in the gitignored config.local.properties (see config.example.properties).
val localConfig = Properties().apply {
    val file = rootProject.file("config.local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localConfigValue(key: String): String =
    localConfig.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("Missing $key in config.local.properties (copy config.example.properties and fill it in)")

android {
    namespace = "com.mahasamuha.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mahasamuha.assistant"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ISSUER_URL", "\"${localConfigValue("assistant.issuerUrl")}\"")
        buildConfigField("String", "CLIENT_ID", "\"${localConfigValue("assistant.clientId")}\"")
        buildConfigField("String", "GATEWAY_URLS", "\"${localConfigValue("assistant.gatewayUrls")}\"")
        // AppAuth's redirect receiver listens on this scheme: <applicationId>:/oauth2redirect
        manifestPlaceholders["appAuthRedirectScheme"] = "com.mahasamuha.assistant"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
