package com.mahasamuha.assistant.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.voice.PanelState.Phase

/** Bottom sheet-style panel; tapping outside it closes the session. */
@Composable
fun SessionPanel(
    state: PanelState,
    onListen: () -> Unit,
    onAnswer: (confirmed: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Swallow taps on the panel itself so they don't close it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = when (state.phase) {
                        Phase.Starting -> "…"
                        Phase.Listening -> "Listening…"
                        Phase.Thinking -> "Thinking…"
                        Phase.Speaking, Phase.Idle -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.heard.isNotEmpty()) {
                    Text(state.heard, style = MaterialTheme.typography.titleMedium)
                }
                if (state.reply.isNotEmpty()) {
                    Text(
                        state.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                state.confirming?.let { confirmation ->
                    Text(confirmation.text, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (confirmation is Confirmation.Ask) {
                            Button(onClick = { onAnswer(true) }) { Text("Confirm") }
                        }
                        OutlinedButton(onClick = { onAnswer(false) }) { Text("Cancel") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.confirming == null && (state.phase == Phase.Idle || state.phase == Phase.Speaking)) {
                        Button(onClick = onListen) { Text("Ask") }
                    } else {
                        Text("")
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
