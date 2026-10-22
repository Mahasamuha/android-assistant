package com.mahasamuha.assistant.calls

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
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
 * Calls go through [TelecomManager.placeCall], not an ACTION_CALL activity: with more than one app
 * handling ACTION_CALL, the activity resolved to a chooser that opened behind the panel (on-device
 * test). [launch] starts an activity from the session while it's visible (only the emergency dialer
 * now); [closePanel] dismisses the session once the call screen takes over. [contacts] is null when
 * contacts permission is off.
 */
class PlaceCallTool(
    private val contacts: ContactDirectory?,
    private val telephony: TelephonyManager,
    private val telecom: TelecomManager,
    private val launch: (Intent) -> Unit,
    private val closePanel: () -> Unit,
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

        // Apps can't place emergency calls directly; open the default dialer with the number so the user presses call.
        if (telephony.isEmergencyNumber(number)) {
            return PendingAction(Confirmation.Announce("Opening the dialer for $number.")) {
                launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(telecom.defaultDialerPackage))
                closePanel()
                JSONObject().put("status", "dialer_opened").put("note", "The user presses call in the dialer").toString()
            }
        }

        // Certain: announce the contact the model named. Otherwise ask, naming everyone the number belongs to.
        val confirmation = certainMatch(name, number)
            ?.let { (contactName, label) -> Confirmation.Announce("Calling $contactName, ${label.lowercase()}.") }
            ?: Confirmation.Ask("Call ${contacts?.describe(number) ?: ContactDirectory.formatNumber(number)}?")
        return PendingAction(confirmation) {
            placeCall(number)
            closePanel()
            JSONObject().put("status", "calling").toString()
        }
    }

    // Only registered while CALL_PHONE is granted (AssistantApp.sessionTools).
    @SuppressLint("MissingPermission")
    private fun placeCall(number: String) {
        telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    }

    /**
     * The contact name and number type when certain: [name] matches exactly one contact, [number] is one
     * of its numbers, and no other number of that contact has the same type (plan §5a). Null otherwise.
     */
    private suspend fun certainMatch(name: String, number: String): Pair<String, String>? {
        if (contacts == null || name.isEmpty()) return null
        val contact = contacts.find(name).singleOrNull() ?: return null
        val chosen = contact.numbers.firstOrNull { ContactDirectory.sameNumber(it.number, number) } ?: return null
        return if (contact.numbers.count { it.label == chosen.label } == 1) contact.name to chosen.label else null
    }
}
