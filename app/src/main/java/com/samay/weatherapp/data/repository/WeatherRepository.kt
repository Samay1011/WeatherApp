package com.samay.weatherapp.data.repository

import com.samay.weatherapp.data.local.WeatherDataStore
import com.samay.weatherapp.data.remote.ApiClient
import com.samay.weatherapp.data.remote.OpenMeteoApi
import com.samay.weatherapp.model.CachedWeather
import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.ForecastResponse

/**
 * Repository handling weather and geocoding data operations,
 * coordinating remote API calls and local DataStore caching.
 */
class WeatherRepository(
    private val api: OpenMeteoApi = ApiClient.openMeteoApi,
    private val weatherDataStore: WeatherDataStore? = null
) {

    /**
     * Searches for cities matching the provided [query] string.
     * Returns an empty list if no results are found or if the results field is null.
     */
    suspend fun searchCities(query: String): List<City> {
        val response = api.searchCities(name = query)
        return response.results ?: emptyList()
    }

    /**
     * Fetches current weather and 5-day daily forecast for the given [latitude] and [longitude].
     */
    suspend fun getForecast(latitude: Double, longitude: Double): ForecastResponse {
        return api.getForecast(latitude = latitude, longitude = longitude)
    }

    /**
     * Reads the last cached weather snapshot from DataStore, if available.
     */
    suspend fun getCachedWeather(): CachedWeather? {
        return weatherDataStore?.getCachedWeather()
    }

    /**
     * Persists the last successful city and weather forecast snapshot to DataStore.
     */
    suspend fun saveCachedWeather(
        city: City,
        forecast: ForecastResponse,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        weatherDataStore?.saveCachedWeather(
            CachedWeather(
                city = city,
                forecast = forecast,
                timestampMillis = timestampMillis
            )
        )
    }
}
