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
 * OpenAI-compatible streaming chat completions through the auth gateway. The web search tool is
 * Anthropic's server tool, which the LLM gateway passes through (docs/phase2-notes.md).
 */
class GatewayChatClient(private val gateways: List<Uri>, private val auth: AuthManager) : ChatClient {

    override fun stream(messages: List<ChatMessage>, options: ChatOptions): Flow<String> = callbackFlow {
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
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        delta(payload)?.let { send(it) }
                    }
                }
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
            // Thinking off: cuts time to first words from ~5 s to ~2 s on web-search turns.
            .put("reasoning_effort", "none")
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
            })
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
            body.put("tools", JSONArray().put(search))
        }
        return body
    }

    private fun delta(payload: String): String? = try {
        JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            ?.takeUnless { it.isNull("content") }?.optString("content")?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        Log.w(TAG, "Unparseable stream event: ${payload.take(200)}")
        null
    }

    private fun errorFor(status: Int): ChatException = when (status) {
        401 -> ChatException.Unauthorized()
        402 -> ChatException.BudgetExceeded()
        429 -> ChatException.RateLimited()
        else -> ChatException.Unavailable("Gateway returned $status")
    }
}
