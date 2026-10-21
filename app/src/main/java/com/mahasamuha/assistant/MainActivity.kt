package com.mahasamuha.assistant

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mahasamuha.assistant.auth.NotSignedInException
import com.mahasamuha.assistant.ui.AssistantTheme
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.launch

/** Explains setup, links to the assistant settings screen, and handles sign-in. */
class MainActivity : ComponentActivity() {

    private val app get() = application as AssistantApp

    private var roleHeld by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        runReporting("Sign-in") {
            app.auth.completeSignIn(result.data)
            "Signed in (token key: ${app.auth.keySecurityLevel()})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val signedIn by app.auth.signedIn.collectAsState()
            AssistantTheme {
                SetupScreen(
                    roleHeld = roleHeld,
                    signedIn = signedIn,
                    message = message,
                    onOpenAssistantSettings = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                    onSignIn = { runReporting("Sign-in") { signIn.launch(app.auth.signInIntent()); null } },
                    onSignOut = { runReporting("Sign-out") { app.auth.signOut(); "Signed out" } },
                    onTestGateway = {
                        runReporting("Gateway test") { GatewayProbe.run(app.config.gatewayUrls.first(), app.auth.freshAccessToken()) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** UI boundary: runs [block] and shows its result, or the error, as the status message. */
    private fun runReporting(label: String, block: suspend () -> String?) {
        lifecycleScope.launch {
            try {
                block()?.let { message = it }
            } catch (e: NotSignedInException) {
                message = "$label: not signed in"
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
                message = "$label failed: ${e.message}"
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for ${intent.action}", e)
            message = "This device has no screen for ${intent.action}"
        }
    }
}

@Composable
private fun SetupScreen(
    roleHeld: Boolean,
    signedIn: Boolean,
    message: String?,
    onOpenAssistantSettings: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onTestGateway: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Assistant setup", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (roleHeld) "This app is the default digital assistant."
                else "This app is not the default digital assistant yet.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "1. Open the digital assistant settings and choose this app.\n" +
                    "2. In Settings → System → Gestures → Press & hold power button, choose Digital assistant.\n" +
                    "3. Long-press the power button to open the panel.",
            )
            Button(onClick = onOpenAssistantSettings) { Text("Open digital assistant settings") }

            HorizontalDivider()
            Text(if (signedIn) "Signed in." else "Not signed in.", style = MaterialTheme.typography.titleMedium)
            if (signedIn) {
                Button(onClick = onTestGateway) { Text("Test gateway") }
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            } else {
                Button(onClick = onSignIn) { Text("Sign in") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
