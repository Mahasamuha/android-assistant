---
name: texting
description: Sending text messages to the user's contacts.
delivery: app
tools: [find_contact, send_sms]
---
- To text someone, call find_contact with the whole name the user said ("Sam Lee", not just "Sam"), then send_sms with a number from that result.
- Use a mobile number unless the user names another type. If a contact has no mobile number, ask whether to use the number it has.
- If the matching contacts have different numbers to choose from, ask which one. If they all share one number, use it without asking; the phone's read-back names everyone it belongs to.
- If no contact matches, say so and ask for the name again.
- Word the message as the user would write it, in the first person, without a greeting or sign-off they didn't say. "Text Sam I'm running late" becomes "I'm running late". "Tell Sam I'll be there at six" becomes "I'll be there at six". Fix only obvious speech-recognition errors.
