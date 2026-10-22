package com.mahasamuha.assistant.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.ui.AppTheme
import com.mahasamuha.assistant.ui.AppType
import com.mahasamuha.assistant.ui.ChatBubble
import com.mahasamuha.assistant.ui.GhostButton
import com.mahasamuha.assistant.ui.Hairline
import com.mahasamuha.assistant.ui.Mark
import com.mahasamuha.assistant.ui.MonoLabel
import com.mahasamuha.assistant.ui.PrimaryButton
import com.mahasamuha.assistant.voice.PanelState.Phase

/** Bottom sheet-style panel in the design system's ChatPanel style; tapping outside it closes the session. */
@Composable
fun SessionPanel(
    state: PanelState,
    onListen: () -> Unit,
    onAnswer: (confirmed: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppTheme.colors
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
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            color = colors.panel,
            border = colors.panelEdge?.let { BorderStroke(1.dp, it) },
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel("assistant · ${statusText(state)}", modifier = Modifier.weight(1f))
                    Mark(size = 32.dp)
                }
                if (state.heard.isNotEmpty()) {
                    // Right-aligned, at most 78% of the width.
                    Box(Modifier.fillMaxWidth(0.78f).align(Alignment.End), contentAlignment = Alignment.CenterEnd) {
                        ChatBubble(state.heard, fromUser = true)
                    }
                }
                if (state.reply.isNotEmpty()) {
                    ChatBubble(
                        state.reply,
                        fromUser = false,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                state.error?.let { error ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoLabel("couldn't finish")
                        ChatBubble(error, fromUser = false)
                    }
                }
                state.confirming?.let { ConfirmationCard(it, onAnswer) }

                Hairline()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.confirming == null && (state.phase == Phase.Idle || state.phase == Phase.Speaking)) {
                        PrimaryButton("ask", onClick = onListen)
                    }
                    Spacer(Modifier.weight(1f))
                    GhostButton("close", onClick = onClose)
                }
            }
        }
    }
}

/** The action waiting on the user, in an inset well: confirm or cancel for an ask, cancel for an announcement. */
@Composable
private fun ConfirmationCard(confirmation: Confirmation, onAnswer: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.inset, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MonoLabel(if (confirmation is Confirmation.Ask) "confirm" else "about to")
        Text(confirmation.text, style = AppType.bodyLarge, color = AppTheme.colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (confirmation is Confirmation.Ask) PrimaryButton("confirm →", onClick = { onAnswer(true) })
            GhostButton("cancel", onClick = { onAnswer(false) })
        }
    }
}

private fun statusText(state: PanelState): String = when {
    state.confirming is Confirmation.Ask && state.phase == Phase.Listening -> "say yes or no"
    else -> when (state.phase) {
        Phase.Starting -> "…"
        Phase.Listening -> "listening…"
        Phase.Thinking -> "thinking…"
        Phase.Speaking -> "speaking"
        Phase.Idle -> "ready"
    }
}
