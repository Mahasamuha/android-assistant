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

