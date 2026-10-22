package com.mahasamuha.assistant.contacts

import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ReadOnlyTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * `find_contact`: resolves a spoken name to contacts and their numbers. Only the matches leave the
 * phone, never the address book (plan §5a).
 */
class FindContactTool(private val contacts: ContactDirectory) : ReadOnlyTool {

    override val definition = ToolDefinition(
        name = "find_contact",
        description = "Look up the user's contacts by name or nickname. Returns matching contacts with their " +
            "phone numbers and number types (mobile, home, work).",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("name", JSONObject()
                .put("type", "string")
                .put("description", "The name as the user said it, e.g. \"Sam\" or \"Sam Lee\".")))
            .put("required", JSONArray().put("name"))
            .put("additionalProperties", false),
        annotations = ToolAnnotations(title = "Find contact", readOnlyHint = true, openWorldHint = false),
    )

    override suspend fun run(arguments: JSONObject): String {
        val name = arguments.optString("name").trim()
        require(name.isNotEmpty()) { "A name is required" }
        val matches = contacts.find(name)
        return JSONObject()
            .put("contacts", JSONArray().apply {
                matches.forEach { contact ->
                    put(JSONObject()
                        .put("name", contact.name)
                        .put("numbers", JSONArray().apply {
                            contact.numbers.forEach { put(JSONObject().put("number", it.number).put("type", it.label.lowercase())) }
                        }))
                }
            })
            .toString()
    }
}
