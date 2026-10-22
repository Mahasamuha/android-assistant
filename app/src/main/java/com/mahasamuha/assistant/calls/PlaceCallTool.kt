package com.mahasamuha.assistant.calls

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.contacts.PhoneNumber
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * `place_call`: announced when the phone's own contacts agree on who it is, asked first otherwise
 * (plan §2). The model picks the contact; this only checks that its pick holds up, and an announced
 * call dials the contact's saved number, never the model's text. The registry turns an announcement
 * into a question once the session has read outside content (search results).
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
        description = "Place a phone call from the user's phone. The phone announces the call or asks the user first.",
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
        require(ContactDirectory.isPhoneNumber(number, minDigits = 2)) { "A plain phone number is required: digits only, no codes or letters" }

        // Apps can't place emergency calls directly; open the default dialer with the number so the user presses call.
        if (telephony.isEmergencyNumber(number)) {
            return PendingAction(Confirmation.Announce("Opening the dialer for $number.", "Open the dialer for $number?")) {
                launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(telecom.defaultDialerPackage))
                closePanel()
                JSONObject().put("status", "dialer_opened").put("note", "The user presses call in the dialer").toString()
            }
        }

        // Certain: announce the contact the model named and dial its saved number. Otherwise ask, naming
        // everyone the number belongs to.
        val match = certainMatch(name, number)
        val confirmation = match
            ?.let { (contactName, saved) ->
                val who = "$contactName, ${saved.label.lowercase()}"
                Confirmation.Announce("Calling $who.", "Call $who?")
            }
            ?: Confirmation.Ask("Call ${contacts?.describe(number) ?: ContactDirectory.formatNumber(number)}?")
        val dialed = match?.second?.number ?: number
        return PendingAction(confirmation) {
            placeCall(dialed)
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
     * The contact name and its saved number when certain: [name] matches exactly one contact, [number] is one
     * of its numbers, and no other number of that contact has the same type (plan §5a). Null otherwise.
     */
    private suspend fun certainMatch(name: String, number: String): Pair<String, PhoneNumber>? {
        if (contacts == null || name.isEmpty()) return null
        val contact = contacts.find(name).singleOrNull() ?: return null
        val chosen = contact.numbers.firstOrNull { ContactDirectory.sameNumber(it.number, number) } ?: return null
        return if (contact.numbers.count { it.label == chosen.label } == 1) contact.name to chosen else null
    }
}
