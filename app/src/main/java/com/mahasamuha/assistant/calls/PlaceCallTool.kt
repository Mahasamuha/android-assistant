package com.mahasamuha.assistant.calls

import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * `place_call`: announced when the phone's own contacts agree on who it is, asked first otherwise
 * (plan §2). The model picks the contact; this only checks that its pick holds up, so a number that
 * doesn't belong to the named contact can never be dialed on an announcement alone.
 *
 * [launch] starts an activity from the session; it must run while the panel is visible (Android 17
 * background activity launch rules). [contacts] is null when contacts permission is off.
 */
class PlaceCallTool(
    private val contacts: ContactDirectory?,
    private val telephony: TelephonyManager,
    private val launch: (Intent) -> Unit,
) : ActionTool {

    override val definition = ToolDefinition(
        name = "place_call",
        description = "Place a phone call from the user's phone. The phone announces the call, or asks the user " +
            "first when the contact isn't certain.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("phone_number", JSONObject()
                    .put("type", "string")
                    .put("description", "The number to call, from find_contact or as the user said it."))
                .put("contact_name", JSONObject()
                    .put("type", "string")
                    .put("description", "The contact's full name as find_contact returned it. Omit for a number the user said.")))
            .put("required", JSONArray().put("phone_number"))
            .put("additionalProperties", false),
        // Placing a call doesn't change or delete anything, so not destructive; it's announced or
        // confirmed by this tool regardless of the hint.
        annotations = ToolAnnotations(title = "Call", readOnlyHint = false, destructiveHint = false, openWorldHint = true),
    )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val number = arguments.optString("phone_number").trim()
        val name = arguments.optString("contact_name").trim()
        require(number.count { it.isDigit() } >= 2) { "A phone number is required" }

        // Apps can't place emergency calls directly; open the dialer with the number so the user presses call.
        if (telephony.isEmergencyNumber(number)) {
            return PendingAction(Confirmation.Announce("Opening the dialer for $number.")) {
                launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                JSONObject().put("status", "dialer_opened").put("note", "The user presses call in the dialer").toString()
            }
        }

        val who = contacts?.describe(number) ?: ContactDirectory.formatNumber(number)
        val confirmation = if (isCertain(name, number)) Confirmation.Announce("Calling $who.") else Confirmation.Ask("Call $who?")
        return PendingAction(confirmation) {
            launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            JSONObject().put("status", "calling").toString()
        }
    }

    /**
     * Certain = [name] matches exactly one contact, [number] is one of that contact's numbers, and no
     * other number of the contact has the same type (plan §5a: one match, one number of the type).
     */
    private suspend fun isCertain(name: String, number: String): Boolean {
        if (contacts == null || name.isEmpty()) return false
        val contact = contacts.find(name).singleOrNull() ?: return false
        val chosen = contact.numbers.firstOrNull { ContactDirectory.sameNumber(it.number, number) } ?: return false
        return contact.numbers.count { it.label == chosen.label } == 1
    }
}
