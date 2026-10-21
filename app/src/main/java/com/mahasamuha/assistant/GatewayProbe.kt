package com.mahasamuha.assistant

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 check: one small, non-streamed request through the gateway with a real token.
 * Replaced by the streaming LLM client in the voice round trip.
 */
object GatewayProbe {
    suspend fun run(gateway: Uri, accessToken: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", "anthropic/claude-haiku-5-5")
            .put("max_tokens", 32)
            .put("reasoning_effort", "none")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly: gateway ok")))
        val connection = URL("$gateway/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("authorization", "Bearer $accessToken")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) return@withContext "HTTP $status: ${text.take(200)}"
            val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            "HTTP $status: $reply"
        } finally {
            connection.disconnect()
        }
    }
}
