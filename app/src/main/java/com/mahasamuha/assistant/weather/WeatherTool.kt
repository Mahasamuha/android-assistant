package com.mahasamuha.assistant.weather

import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.voice.LocationSource
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * `get_weather`: live conditions and forecast for the user's location or a named place. To change
 * provider, replace this tool (e.g. with a remote `get_weather`, plan §4b) rather than the forecast source.
 */
class WeatherTool(private val provider: OpenMeteoWeather, private val locations: LocationSource) : ReadOnlyTool {

    override val definition = ToolDefinition(
        name = "get_weather",
        description = "Live current conditions, the next 48 hours, and a 7-day forecast. " +
            "Use this for every weather question instead of web search.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("place", JSONObject()
                .put("type", "string")
                .put("description", "A city or place name. Omit it for the user's current location.")))
            .put("additionalProperties", false),
        annotations = ToolAnnotations(title = "Weather", readOnlyHint = true, openWorldHint = true),
    )

    override suspend fun run(arguments: JSONObject): String {
        val placeName = arguments.optString("place").trim()
        val place = if (placeName.isNotEmpty()) {
            locations.find(placeName) ?: throw IllegalArgumentException("Couldn't find a place called $placeName")
        } else {
            locations.current() ?: throw IllegalStateException("The user's location isn't available; ask which city")
        }
        val forecast = provider.forecast(place.latitude, place.longitude, unitsForLocale())
        return toJson(place.description ?: placeName, forecast).toString()
    }

    /** Compact, already-rounded values with spoken-friendly times, so the model doesn't need to compute anything. */
    private fun toJson(place: String, forecast: Forecast): JSONObject {
        val units = forecast.units
        fun temp(value: Double) = "${value.roundToInt()}${units.temperature}"
        val hourFormat = DateTimeFormatter.ofPattern("EEEE h a", Locale.US)

        return JSONObject()
            .put("place", place)
            .put("current", JSONObject()
                .put("temperature", temp(forecast.current.temperature))
                .put("feels_like", temp(forecast.current.feelsLike))
                .put("condition", forecast.current.condition)
                .put("wind", "${forecast.current.windSpeed.roundToInt()} ${units.speed}")
                .apply { forecast.current.humidity?.let { put("humidity", "$it%") } })
            .put("next_hours", JSONArray().apply {
                // Every third hour keeps the result short while still covering "tonight" through "tomorrow night".
                forecast.hours.filterIndexed { i, _ -> i % 3 == 0 }.forEach { hour ->
                    put(JSONObject()
                        .put("time", hour.time.format(hourFormat))
                        .put("temperature", temp(hour.temperature))
                        .put("condition", hour.condition)
                        .apply { hour.precipitationChance?.let { put("precipitation_chance", "$it%") } })
                }
            })
            .put("days", JSONArray().apply {
                forecast.days.forEach { day ->
                    put(JSONObject()
                        .put("day", day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US))
                        .put("high", temp(day.high))
                        .put("low", temp(day.low))
                        .put("condition", day.condition)
                        .apply { day.precipitationChance?.let { put("precipitation_chance", "$it%") } })
                }
            })
    }

    private fun unitsForLocale(): Units =
        if (Locale.getDefault().country in setOf("US", "LR", "MM")) Units.IMPERIAL else Units.METRIC
}
