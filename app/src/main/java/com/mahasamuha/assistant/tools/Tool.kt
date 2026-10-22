package com.mahasamuha.assistant.tools

import android.util.Log
import com.mahasamuha.assistant.llm.ToolCall
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

/** Something the model can call. Runs on the phone; a [ReadOnlyTool] or an [ActionTool] (plan §4b). */
sealed interface Tool {
    val definition: ToolDefinition
}

/** No side effects, so it runs without asking. Its definition must set `readOnlyHint`. */
interface ReadOnlyTool : Tool {
    /** Returns the result for the model, usually JSON. Throw for failures; the registry reports them to the model. */
    suspend fun run(arguments: JSONObject): String
}

/**
 * Changes something outside the conversation (sends a text, places a call). The tool only prepares the
 * action; the registry gets it confirmed or announced before performing it, so no tool can skip that step.
 */
interface ActionTool : Tool {
    /** Validates and resolves the arguments. Throw for failures, as in [ReadOnlyTool.run]. */
    suspend fun prepare(arguments: JSONObject): PendingAction
}

/** A prepared action. [confirmation]'s text is built from the resolved arguments, never taken from the model. */
class PendingAction(val confirmation: Confirmation, private val action: suspend () -> String) {
    suspend fun perform(): String = action()
}

sealed interface Confirmation {
    val text: String

    /** Read back and wait for a yes ("Text Sam Lee: I'm running late. Send it?"). */
    data class Ask(override val text: String) : Confirmation

    /** Say what's happening and go ahead unless cancelled ("Calling Mom, mobile"). */
    data class Announce(override val text: String) : Confirmation
}

sealed interface ConfirmationResult {
    data object Confirmed : ConfirmationResult

    /** Cancelled, or the user said something other than yes; [userSaid] goes back to the model. */
    data class Declined(val userSaid: String?) : ConfirmationResult
}

/** Asks the user; implemented by the session, which owns speech and the panel. */
fun interface Confirmer {
    suspend fun confirm(confirmation: Confirmation): ConfirmationResult
}

class ToolRegistry(tools: List<Tool>) {
    init {
        require(tools.map { it.definition.name }.toSet().size == tools.size) { "Tool names must be unique" }
        for (tool in tools) {
            require((tool is ReadOnlyTool) == tool.definition.annotations.readOnlyHint) {
                "${tool.definition.name}: readOnlyHint must be set exactly for read-only tools"
            }
        }
    }

    private val byName = tools.associateBy { it.definition.name }

    val definitions: List<ToolDefinition> = tools.map { it.definition }

    val names: Set<String> = byName.keys

    /** Never throws (except cancellation): failures become an error result the model can explain. */
    suspend fun execute(call: ToolCall, confirmer: Confirmer): String {
        val tool = byName[call.name] ?: return error("Unknown tool ${call.name}")
        val arguments = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: JSONException) {
            return error("Arguments were not valid JSON")
        }
        return try {
            when (tool) {
                is ReadOnlyTool -> tool.run(arguments)
                is ActionTool -> {
                    val action = tool.prepare(arguments)
                    when (val result = confirmer.confirm(action.confirmation)) {
                        ConfirmationResult.Confirmed -> action.perform()
                        is ConfirmationResult.Declined -> notConfirmed(result.userSaid)
                    }
                }
            }.also { Log.i(TAG, "Tool ${call.name}(${call.arguments}) -> ${it.take(300)}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tool ${call.name} failed", e)
            error(e.message ?: "Tool failed")
        }
    }

    private fun notConfirmed(userSaid: String?) = JSONObject()
        .put("status", "not_confirmed")
        .apply { userSaid?.let { put("user_said", it) } }
        .toString()

    private fun error(message: String) = JSONObject().put("error", message).toString()
}
