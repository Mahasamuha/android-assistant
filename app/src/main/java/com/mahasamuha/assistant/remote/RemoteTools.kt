package com.mahasamuha.assistant.remote

import android.util.Log
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.tools.Tool
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Tools served by the MCP server (plan §4b). The list is fetched at session start, while the user is still
 * speaking; if the server is slow or down, the last list fetched is used, or none.
 */
class RemoteTools(private val client: McpClient) {
    @Volatile private var cached: List<ToolDefinition>? = null

    suspend fun load(): List<Tool> {
        val definitions = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { client.listTools() }?.also { cached = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote tools unavailable", e)
            null
        } ?: cached.orEmpty()
        return definitions.map { definition ->
            if (definition.annotations.readOnlyHint) RemoteReadOnlyTool(definition, client) else RemoteActionTool(definition, client)
        }
    }

    private companion object {
        const val FETCH_TIMEOUT_MS = 4_000L
    }
}

/**
 * A remote read-only tool that replaced a local one of the same name (plan §4b): the local one answers if
 * the remote call fails, e.g. Pirate Weather unreachable → the phone's Open-Meteo get_weather.
 */
class FallbackTool(private val remote: ReadOnlyTool, private val local: ReadOnlyTool) : ReadOnlyTool {
    override val definition: ToolDefinition = remote.definition

    override suspend fun run(arguments: JSONObject): String = try {
        remote.run(arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Remote ${definition.name} failed; using the local one", e)
        local.run(arguments)
    }
}

private class RemoteReadOnlyTool(override val definition: ToolDefinition, private val client: McpClient) : ReadOnlyTool {
    override suspend fun run(arguments: JSONObject): String = client.callTool(definition.name, arguments)
}

/**
 * A remote tool with side effects always asks first (plan §4b): the phone has no tool-specific read-back
 * for it, so the question names the tool and its arguments.
 */
private class RemoteActionTool(override val definition: ToolDefinition, private val client: McpClient) : ActionTool {
    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val name = definition.annotations.title ?: definition.name.replace('_', ' ')
        val details = arguments.keys().asSequence().joinToString(", ") { key -> "$key ${arguments.opt(key)}" }
        val question = if (details.isEmpty()) "Run $name?" else "Run $name with $details?"
        return PendingAction(Confirmation.Ask(question)) { client.callTool(definition.name, arguments) }
    }
}
