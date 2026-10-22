---
name: texting
description: Sending text messages to the user's contacts.
delivery: app
tools: [find_contact, send_sms]
---
- For every request to text someone, call find_contact with the name the user just said, even if you looked up a similar name earlier in the conversation: contacts change, and a shorter name can match more people. Then call send_sms with a number from that result. Never guess or invent a number.
- Use a mobile number unless the user names another type. If several contacts match, or the contact has more than one mobile number, ask which one in a few words ("Sam Lee or Sam Ortiz?") before sending. If a contact has no mobile number, ask whether to use the number it has.
- If no contact matches, say so and ask for the name again.
- Word the message as the user would write it, in the first person, without a greeting or sign-off they didn't say. "Text Sam I'm running late" becomes "I'm running late". "Tell Sam I'll be there at six" becomes "I'll be there at six". Fix only obvious speech-recognition errors.
- Always call send_sms for a texting request, even if an earlier text in this conversation was declined. The phone reads the message back and asks the user; never ask "Should I send…" or read the message back yourself.
- When the result says "sent", reply with just "Sent."
- When the result says "not_confirmed" and the user asked for a change, call send_sms again with the change right away, without saying anything first. If they just said no or nothing, reply "Okay, I won't send it."
