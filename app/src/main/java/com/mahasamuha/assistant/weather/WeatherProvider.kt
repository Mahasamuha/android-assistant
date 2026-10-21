package com.mahasamuha.assistant.weather

import java.time.LocalDate
import java.time.LocalDateTime

enum class Units(val temperature: String, val speed: String) {
    IMPERIAL("°F", "mph"),
    METRIC("°C", "km/h"),
}

/** Provider-neutral forecast. Times are local to the forecast location. */
data class Forecast(
    val units: Units,
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
) {
    data class Current(val temperature: Double, val feelsLike: Double, val condition: String, val windSpeed: Double, val humidity: Int?)

    data class Hour(val time: LocalDateTime, val temperature: Double, val condition: String, val precipitationChance: Int?)

    data class Day(val date: LocalDate, val high: Double, val low: Double, val condition: String, val precipitationChance: Int?)
}

/** A live forecast source. Swap implementations without touching the tool or the prompt. */
interface WeatherProvider {
    /** Current conditions, the next 48 hours, and the next few days. */
    suspend fun forecast(latitude: Double, longitude: Double, units: Units): Forecast
}
