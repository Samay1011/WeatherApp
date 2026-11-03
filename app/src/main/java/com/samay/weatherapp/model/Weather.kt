package com.samay.weatherapp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for the Open-Meteo Forecast endpoint.
 */
@Serializable
data class ForecastResponse(
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("timezone_abbreviation")
    val timezoneAbbreviation: String? = null,
    @SerialName("current")
    val current: CurrentWeather? = null,
    @SerialName("hourly")
    val hourly: HourlyWeather? = null,
    @SerialName("daily")
    val daily: DailyWeather? = null
)

/**
 * Atmospheric conditions at the current time.
 */
@Serializable
data class CurrentWeather(
    @SerialName("time")
    val time: String,
    @SerialName("temperature_2m")
    val temperature2m: Double,
    @SerialName("relative_humidity_2m")
    val relativeHumidity2m: Int,
    @SerialName("weather_code")
    val weatherCode: Int,
    @SerialName("wind_speed_10m")
    val windSpeed10m: Double
)

/**
 * Hourly forecast data arrays.
 */
@Serializable
data class HourlyWeather(
    @SerialName("time")
    val time: List<String>,
    @SerialName("temperature_2m")
    val temperature2m: List<Double>,
    @SerialName("weather_code")
    val weatherCode: List<Int>
)

/**
 * Daily forecast data arrays.
 */
@Serializable
data class DailyWeather(
    @SerialName("time")
    val time: List<String>,
    @SerialName("weather_code")
    val weatherCode: List<Int>,
    @SerialName("temperature_2m_max")
    val temperature2mMax: List<Double>,
    @SerialName("temperature_2m_min")
    val temperature2mMin: List<Double>
)
