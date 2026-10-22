package com.mahasamuha.assistant.sms

import android.telephony.PhoneNumberUtils
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * `send_sms`: always confirmed (plan §2). The read-back names the recipient from the phone's own
 * lookup of the number, so it says who will actually get the text, whatever the model believes.
 * [contacts] is null when contacts permission is off; the read-back then gives the number.
 */
class SendSmsTool(private val sender: SmsSender, private val contacts: ContactDirectory?) : ActionTool {

    override val definition = ToolDefinition(
        name = "send_sms",
        description = "Send a text message from the user's phone. The phone reads it back to the user and sends " +
            "it only after they confirm.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("phone_number", JSONObject()
                    .put("type", "string")
                    .put("description", "The recipient's number, from find_contact or as the user said it."))
                .put("message", JSONObject()
                    .put("type", "string")
                    .put("description", "The text to send, worded as the user would write it.")))
            .put("required", JSONArray().put("phone_number").put("message"))
            .put("additionalProperties", false),
        // Sending adds a message rather than changing or deleting anything, so not destructive; the
        // confirmation comes from this tool's Ask, not from the hint.
        annotations = ToolAnnotations(title = "Send text", readOnlyHint = false, destructiveHint = false, openWorldHint = true),
    )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val number = arguments.optString("phone_number").trim()
        val message = arguments.optString("message").trim()
        require(number.count { it.isDigit() } >= 3) { "A phone number is required" }
        require(message.isNotEmpty()) { "The message is empty" }

        val recipient = describeRecipient(number)
        val body = if (message.last() in ".!?") message else "$message."
        return PendingAction(Confirmation.Ask("Text $recipient: $body Send it?")) {
            when (sender.send(number, message)) {
                SmsSender.Outcome.Sent -> JSONObject().put("status", "sent")
                SmsSender.Outcome.Pending -> JSONObject().put("status", "sending").put("note", "The phone hasn't confirmed it was sent yet")
            }.toString()
        }
    }

    /** Names everyone the number is saved under, so a shared number isn't read back as just one of them. */
    private suspend fun describeRecipient(number: String): String {
        val names = contacts?.namesFor(number).orEmpty()
        return when (names.size) {
            0 -> PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
            1 -> names.single().let { (name, label) -> "$name, ${label.lowercase()}" }
            else -> names.joinToString(" or ") { it.first } + " (same number)"
        }
    }
}
