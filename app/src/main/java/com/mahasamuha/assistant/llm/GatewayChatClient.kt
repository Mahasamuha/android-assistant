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
 * OpenAI-compatible streaming chat completions through the auth gateway, with function tools. The web
 * search tool is Anthropic's server tool, which the LLM gateway passes through (docs/phase2-notes.md).
 */
class GatewayChatClient(private val gateways: List<Uri>, private val auth: AuthManager) : ChatClient {

    override fun stream(messages: List<ChatMessage>, options: ChatOptions): Flow<ChatEvent> = callbackFlow {
        val token = auth.freshAccessToken()
        // v1 uses the first gateway; the list leaves room for a fallback.
        val connection = URL("${gateways.first()}/v1/chat/completions").openConnection() as HttpURLConnection

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
                val toolCalls = ToolCallAccumulator()
                var finishReason: String? = null
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val choice = choice(payload) ?: continue
                        choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finishReason = it }
                        val delta = choice.optJSONObject("delta") ?: continue
                        delta.takeUnless { it.isNull("content") }?.optString("content")
                            ?.takeIf { it.isNotEmpty() }?.let { send(ChatEvent.Text(it)) }
                        delta.optJSONArray("tool_calls")?.let(toolCalls::add)
                    }
                }
                // Server-side web search also streams as a tool_calls entry, with no id or name, and has
                // already run. Only a "tool_calls" finish means the phone has tools to run.
                val calls = toolCalls.result().filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (finishReason == "tool_calls" && calls.isNotEmpty()) send(ChatEvent.ToolCalls(calls))
                close()
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
            .put("max_tokens", options.maxTokens)
            .put("stream", true)
            .put("reasoning_effort", options.reasoningEffort)
            .put("messages", JSONArray().apply { messages.forEach { put(messageJson(it)) } })
        val tools = JSONArray()
        options.tools.forEach { tool ->
            tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", tool.inputSchema)))
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

    private fun messageJson(message: ChatMessage): JSONObject {
        val json = JSONObject().put("role", message.role.wire).put("content", message.content ?: JSONObject.NULL)
        if (message.toolCalls.isNotEmpty()) {
            json.put("tool_calls", JSONArray().apply {
                message.toolCalls.forEach { call ->
                    put(JSONObject().put("id", call.id).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.ifBlank { "{}" })))
                }
            })
        }
        message.toolCallId?.let { json.put("tool_call_id", it) }
        return json
    }

    private fun choice(payload: String): JSONObject? = try {
        JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
    } catch (e: JSONException) {
        Log.w(TAG, "Unparseable stream event: ${payload.take(200)}")
        null
    }

    /** Streamed tool calls arrive in pieces keyed by index: id and name first, then the arguments JSON. */
    private class ToolCallAccumulator {
        private class Partial(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

        private val calls = sortedMapOf<Int, Partial>()

        fun add(deltas: JSONArray) {
            for (i in 0 until deltas.length()) {
                val delta = deltas.getJSONObject(i)
                val call = calls.getOrPut(delta.optInt("index")) { Partial() }
                delta.optString("id").takeIf { it.isNotEmpty() }?.let { call.id = it }
                delta.optJSONObject("function")?.let { function ->
                    function.optString("name").takeIf { it.isNotEmpty() }?.let { call.name += it }
                    if (!function.isNull("arguments")) call.arguments.append(function.optString("arguments"))
                }
            }
        }

        fun result(): List<ToolCall> = calls.values.map { ToolCall(it.id, it.name, it.arguments.toString()) }
    }

    private fun errorFor(status: Int): ChatException = when (status) {
        401 -> ChatException.Unauthorized()
        402 -> ChatException.BudgetExceeded()
        429 -> ChatException.RateLimited()
        in 400..499 -> ChatException.Rejected(status)
        else -> ChatException.Unavailable("Gateway returned $status")
    }
}
