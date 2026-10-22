package com.mahasamuha.assistant.llm

import android.net.Uri
import android.util.Log
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.voice.TAG
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The OpenAI Responses API (`/v1/responses`), streamed through the auth gateway, with function tools and
 * Anthropic's web search server tool (passed through by the LLM gateway). Chosen over chat completions
 * because its output is typed: searches arrive as items that can be sent back, so follow-ups see what was
 * searched, and citations are annotations rather than text (docs/phase3-notes.md).
 */
class GatewayChatClient(private val gateways: List<Uri>, private val auth: AuthManager) : ChatClient {

    override fun stream(messages: List<ChatMessage>, options: ChatOptions): Flow<ChatEvent> = callbackFlow {
        val token = auth.freshAccessToken()
        // v1 uses the first gateway; the list leaves room for a fallback.
        val connection = URL("${gateways.first()}/v1/responses").openConnection() as HttpURLConnection

        launch(Dispatchers.IO) {
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                connection.setRequestProperty("accept", "text/event-stream")
                connection.setRequestProperty("authorization", "Bearer $token")
                connection.outputStream.use { it.write(requestBody(messages, options).toString().toByteArray()) }

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "Gateway returned $status: ${detail.take(300)}")
                    close(errorFor(status))
                    return@launch
                }
                // Items are collected as each one finishes: in a streamed search, the LLM gateway's final
                // response.completed omits the web_search_call items (docs/phase3-notes.md).
                val items = mutableListOf<JSONObject>()
                // Citations arrive as their own events (message items carry none), also when the answer
                // draws on an earlier turn's search.
                val cited = mutableListOf<SourceLink>()
                var completed = false
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val event = event(line.removePrefix("data:").trim()) ?: continue
                        when (event.optString("type")) {
                            "response.output_text.delta" -> event.optString("delta").takeIf { it.isNotEmpty() }?.let { send(ChatEvent.Text(it)) }
                            "response.output_item.done" -> event.optJSONObject("item")?.let(items::add)
                            "response.output_text.annotation.added" -> event.optJSONObject("annotation")?.let(::sourceLink)?.let(cited::add)
                            // Incomplete means a limit was hit (e.g. max tokens): keep what arrived.
                            "response.completed", "response.incomplete" -> completed = true
                            "response.failed", "error" -> throw ChatException.Unavailable("Response failed: ${event.toString().take(300)}")
                        }
                    }
                }
                if (!completed) throw ChatException.Unavailable("Stream ended without a completed response")
                val kept = items
                    // Thinking can't be sent back through the LLM gateway (its signatures are lost), and
                    // earlier turns don't need it.
                    .filter { it.optString("type") != "reasoning" }
                val calls = kept.filter { it.optString("type") == "function_call" }
                    .map { ToolCall(it.getString("call_id"), it.getString("name"), it.optString("arguments")) }
                if (calls.isNotEmpty()) send(ChatEvent.ToolCalls(calls))
                val sources = cited.distinctBy { it.url }
                send(ChatEvent.Finished(kept, sources.ifEmpty { searchResults(kept) }, sourcesCited = sources.isNotEmpty()))
                close()
            } catch (e: ChatException) {
                close(e)
            } catch (e: IOException) {
                // Also how a cancelled collection ends: awaitClose disconnects, and the blocked read throws.
                close(ChatException.Unavailable("Gateway connection failed", e))
            }
        }

        awaitClose { connection.disconnect() }
    }

    private fun requestBody(messages: List<ChatMessage>, options: ChatOptions): JSONObject {
        val body = JSONObject()
            .put("model", options.model)
            .put("max_output_tokens", options.maxTokens)
            .put("stream", true)
            .put("reasoning", JSONObject().put("effort", options.reasoningEffort))
            .put("instructions", messages.filter { it.role == ChatMessage.Role.SYSTEM }.joinToString("\n\n") { it.content.orEmpty() })
            .put("input", JSONArray().apply { messages.forEach { message -> inputItems(message).forEach(::put) } })
        val tools = JSONArray()
        options.tools.forEach { tool ->
            tools.put(JSONObject()
                .put("type", "function")
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", tool.inputSchema))
        }
        if (options.webSearch) {
            val search = JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2)
            val userLocation = JSONObject().put("type", "approximate")
            options.timeZone?.let { userLocation.put("timezone", it) }
            options.location?.let { place ->
                place.city?.let { userLocation.put("city", it) }
                place.region?.let { userLocation.put("region", it) }
                place.countryCode?.let { userLocation.put("country", it) }
            }
            if (userLocation.length() > 1) search.put("user_location", userLocation)
            tools.put(search)
        }
        if (tools.length() > 0) body.put("tools", tools)
        return body
    }

    /** One conversation entry as Responses input items. Assistant turns go back exactly as received. */
    private fun inputItems(message: ChatMessage): List<JSONObject> = when (message.role) {
        ChatMessage.Role.SYSTEM -> emptyList() // sent as instructions
        ChatMessage.Role.USER -> listOf(JSONObject().put("role", "user").put("content", message.content.orEmpty()))
        ChatMessage.Role.TOOL -> listOf(JSONObject()
            .put("type", "function_call_output")
            .put("call_id", message.toolCallId)
            .put("output", message.content.orEmpty()))
        ChatMessage.Role.ASSISTANT -> message.replay.ifEmpty {
            buildList {
                message.content?.takeIf { it.isNotEmpty() }?.let { add(JSONObject().put("role", "assistant").put("content", it)) }
                message.toolCalls.forEach { call ->
                    add(JSONObject().put("type", "function_call").put("call_id", call.id).put("name", call.name).put("arguments", call.arguments))
                }
            }
        }
    }

    /** Every page this response's searches returned, in order: the fallback when the answer cited nothing. */
    private fun searchResults(items: List<JSONObject>): List<SourceLink> = items
        .filter { it.optString("type") == "web_search_call" }
        .flatMap { item ->
            val sources = item.optJSONObject("action")?.optJSONArray("sources") ?: JSONArray()
            (0 until sources.length()).mapNotNull { i -> sources.optJSONObject(i)?.let(::sourceLink) }
        }
        .distinctBy { it.url }

    /** A `url_citation` annotation or a search source, as a link; null without a web URL. */
    private fun sourceLink(json: JSONObject): SourceLink? {
        val url = json.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return SourceLink(json.optString("title").ifBlank { Uri.parse(url).host.orEmpty() }, url)
    }

    private fun event(payload: String): JSONObject? = try {
        JSONObject(payload)
    } catch (e: JSONException) {
        Log.w(TAG, "Unparseable stream event: ${payload.take(200)}")
        null
    }

    private fun errorFor(status: Int): ChatException = when (status) {
        401 -> ChatException.Unauthorized()
        402 -> ChatException.BudgetExceeded()
        429 -> ChatException.RateLimited()
        in 400..499 -> ChatException.Rejected(status)
        else -> ChatException.Unavailable("Gateway returned $status")
    }
}
