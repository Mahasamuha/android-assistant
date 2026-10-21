package com.mahasamuha.assistant.llm

import com.mahasamuha.assistant.voice.ApproximateLocation
import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class ChatOptions(
    val model: String = "anthropic/claude-haiku-5-5",
    val maxTokens: Int = 400,
    val webSearch: Boolean = true,
    /** Approximate location for web search: IANA time zone, plus city/region/country when known. */
    val timeZone: String? = null,
    val location: ApproximateLocation? = null,
)

/** The LLM endpoint. Implementations stream text deltas and fail with [ChatException]. */
interface ChatClient {
    fun stream(messages: List<ChatMessage>, options: ChatOptions = ChatOptions()): Flow<String>
}

sealed class ChatException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : ChatException("Token rejected by the gateway")
    class BudgetExceeded : ChatException("Spend budget exceeded")
    class RateLimited : ChatException("Rate limited")
    class Unavailable(message: String, cause: Throwable? = null) : ChatException(message, cause)
}
