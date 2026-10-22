package com.mahasamuha.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Phone versions of the design system's components (Button, MonoLabel, ChatBubble, status dots).
// Labels are lowercase by convention; MonoLabel uppercases its own text.

/** Ink fill (paper on the dark variant). One per view. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.actionBackground, contentColor = colors.actionContent),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, style = AppType.ui)
    }
}

/** Text only, at 0.75 opacity: the secondary action next to a primary. */
@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.text.copy(alpha = 0.75f)),
        // No side padding, so the label lines up with the text around it; the touch target keeps its minimum size.
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
    ) {
        Text(label, style = AppType.ui)
    }
}

/** Geist Mono uppercase metadata. Separate parts with " · ". */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.textMuted) {
    Text(text.uppercase(), modifier = modifier, style = AppType.eyebrow, color = color)
}

/** The user's words in indigo, or the assistant's on a faint tint. The 4dp corner is the tail. */
@Composable
fun ChatBubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val shape = if (fromUser) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    Box(
        modifier = modifier
            .background(if (fromUser) colors.bubbleUser else colors.bubbleAssistant, shape)
            .padding(horizontal = 14.dp, vertical = if (fromUser) 10.dp else 12.dp),
    ) {
        Text(text, style = AppType.body, color = if (fromUser) colors.onBubbleUser else colors.text)
    }
}

/** A sage dot when done; an empty ring when not. */
@Composable
fun StatusDot(done: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .size(8.dp)
            .then(if (done) Modifier.background(colors.status, CircleShape) else Modifier.border(1.dp, colors.textMuted, CircleShape)),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = AppTheme.colors.divider)
}
