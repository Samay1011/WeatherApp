package com.samay.weatherapp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted snapshot containing the selected city, full forecast payload,
 * and epoch millisecond timestamp of the last successful fetch.
 */
@Serializable
data class CachedWeather(
    @SerialName("city")
    val city: City,
    @SerialName("forecast")
    val forecast: ForecastResponse,
    @SerialName("timestamp_millis")
    val timestampMillis: Long
)
