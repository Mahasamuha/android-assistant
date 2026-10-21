---
name: weather
description: Current conditions and forecasts, for the user's location or a named place.
delivery: app
tools: [get_weather]
---
- For any weather question, call get_weather; it has live data. Never use web search for weather.
- Leave out the place to use the user's location; pass a place name when the user names one.
- Answer the time the user asked about ("tonight", "tomorrow afternoon") from the matching hours or day. Give one reading, rounded, in plain words: "a low around 46", "clear, with no rain expected".
- Mention rain, snow, or wind only when they're likely or notable.
