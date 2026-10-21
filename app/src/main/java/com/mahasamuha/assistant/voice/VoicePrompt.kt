package com.mahasamuha.assistant.voice

import com.mahasamuha.assistant.skills.Skill
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The system prompt: generic voice rules, then each skill's instructions (always loaded for now,
 * plan §4b), then the session's context. Skills come first and sorted so the prompt prefix is stable.
 */
object VoicePrompt {
    fun system(skills: List<Skill>, location: ApproximateLocation?, now: ZonedDateTime = ZonedDateTime.now()): String {
        val where = location?.description?.let { "The user is near $it." }
            ?: "The user's location is unknown. If a question depends on location, ask which city."
        val skillText = skills.sortedBy { it.name }.joinToString("\n\n") { skill ->
            "## Skill: ${skill.name}\n${skill.description}\n\n${skill.instructions}"
        }
        return buildString {
            append(BASE)
            if (skillText.isNotEmpty()) append("\n\n# Skills\n\n").append(skillText)
            append("\n\n# Context\n\n").append(where)
            append("\nThe current local date and time is ")
            append(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a z", Locale.US))).append('.')
        }
    }

    private val BASE = """
        You are a voice assistant on the user's phone. Everything you write is read aloud by text-to-speech, so write only what the user should hear.

        - Answer in one to three short sentences unless the user asks for more.
        - Plain text only: no markdown, lists, headings, emoji, or URLs.
        - Write numbers, units, and symbols the way they should be spoken, for example "57 degrees" or "three and a half cups".
        - For anything current or changing (news, scores, prices, opening hours), use web search, unless a skill below says to use a specific tool.
        - Before using a tool you may say one short sentence, such as "Let me check."
        - Give one direct answer from the most recent result. Never talk about the results themselves: not their dates, their sources, or whether they disagree. Don't add caveats or suggest checking elsewhere. If you can't find a current answer, say so in one sentence.
        - Never describe your reasoning or restate what the user asked.
        - If the request is ambiguous, ask one short question instead of guessing.
    """.trimIndent()
}
