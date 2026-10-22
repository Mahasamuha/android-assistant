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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
                Transcript(state)
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

/** The conversation so far as bubbles, kept scrolled to the newest; words still being heard show faded. */
@Composable
private fun Transcript(state: PanelState) {
    val listState = rememberLazyListState()
    val itemCount = state.transcript.size + if (state.hearing.isNotEmpty()) 1 else 0
    // Follow new bubbles and the one that is still streaming.
    LaunchedEffect(itemCount, state.transcript.lastOrNull()?.text, state.hearing) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.transcript) { message -> Bubble(message.text, message.fromUser) }
        if (state.hearing.isNotEmpty()) {
            item { Bubble(state.hearing, fromUser = true, modifier = Modifier.alpha(0.6f)) }
        }
    }
}

/** User bubbles sit on the right at up to 78% of the width; the assistant's on the left at up to 92%. */
@Composable
private fun Bubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(if (fromUser) 0.78f else 0.92f), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
            ChatBubble(text, fromUser = fromUser, modifier = modifier)
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
