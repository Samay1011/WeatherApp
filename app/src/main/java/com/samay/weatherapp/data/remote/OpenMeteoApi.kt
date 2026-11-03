package com.samay.weatherapp.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit service interface for Open-Meteo Geocoding and Forecast APIs.
 *
 * Generic [ResponseBody] is used temporarily for Milestone M1-A to provide
 * a compilable service contract prior to the introduction of dedicated data models in M1-B.
 */
interface OpenMeteoApi {

    @GET("https://geocoding-api.open-meteo.com/v1/search")
    suspend fun searchCities(
        @Query("name") name: String,
        @Query("count") count: Int = 5,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json"
    ): ResponseBody

    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
        @Query("hourly") hourly: String = "temperature_2m,weather_code",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min",
        @Query("forecast_days") forecastDays: Int = 5,
        @Query("timezone") timezone: String = "auto"
    ): ResponseBody
}
