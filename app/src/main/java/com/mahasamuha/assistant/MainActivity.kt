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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mahasamuha.assistant.ui.AssistantTheme
import com.mahasamuha.assistant.voice.TAG

/** Explains setup and links to the settings screen where the assistant is chosen. */
class MainActivity : ComponentActivity() {

    private var roleHeld by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    // Expected to fail: ROLE_ASSISTANT is believed not to be requestable. Kept to verify on API 37.
    private val requestRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.i(TAG, "ROLE_ASSISTANT request resultCode=${result.resultCode}")
        refreshRoleState()
        message = "Role request returned code ${result.resultCode}; role held: $roleHeld"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssistantTheme {
                SetupScreen(
                    roleHeld = roleHeld,
                    message = message,
                    onOpenAssistantSettings = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                    onOpenDefaultApps = { open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) },
                    onRequestRole = ::requestAssistantRole,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRoleState()
    }

    private fun refreshRoleState() {
        roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        Log.i(TAG, "ROLE_ASSISTANT available=$available")
        requestRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
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
    message: String?,
    onOpenAssistantSettings: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onRequestRole: () -> Unit,
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
            OutlinedButton(onClick = onOpenDefaultApps) { Text("Open default apps") }
            OutlinedButton(onClick = onRequestRole) { Text("Test: request assistant role") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
