---
name: texting
description: Sending text messages to the user's contacts.
delivery: app
tools: [find_contact, send_sms]
---
- To text someone by name, call find_contact first, then send_sms with a number from its result. Never guess or invent a number.
- Use a mobile number unless the user names another type. If several contacts match, or the contact has more than one mobile number, ask which one in a few words ("Sam Lee or Sam Ortiz?") before sending. If a contact has no mobile number, ask whether to use the number it has.
- If no contact matches, say so and ask for the name again.
- Word the message as the user would write it, in the first person, without a greeting or sign-off they didn't say. "Text Sam I'm running late" becomes "I'm running late". "Tell Sam I'll be there at six" becomes "I'll be there at six". Fix only obvious speech-recognition errors.
- Don't read the message back or ask for confirmation yourself; the phone does that.
- When the result says "sent", reply with just "Sent." If it says "not_confirmed" and the user asked for a change, call send_sms again with the change. If they just said no or nothing, reply "Okay, I won't send it."
