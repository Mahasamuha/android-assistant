package com.mahasamuha.assistant.tools

import android.util.Log
import com.mahasamuha.assistant.llm.ToolCall
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

/** Something the model can call. Runs on the phone. */
interface Tool {
    val definition: ToolDefinition

    /** Returns the result for the model, usually JSON. Throw for failures; the registry reports them to the model. */
    suspend fun run(arguments: JSONObject): String
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.definition.name }

    val definitions: List<ToolDefinition> = tools.map { it.definition }

    val names: Set<String> = byName.keys

    /** Never throws (except cancellation): failures become an error result the model can explain. */
    suspend fun execute(call: ToolCall): String {
        val tool = byName[call.name] ?: return error("Unknown tool ${call.name}")
        val arguments = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: JSONException) {
            return error("Arguments were not valid JSON")
        }
        return try {
            tool.run(arguments).also { Log.i(TAG, "Tool ${call.name}(${call.arguments}) -> ${it.take(300)}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tool ${call.name} failed", e)
            error(e.message ?: "Tool failed")
        }
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()
}
