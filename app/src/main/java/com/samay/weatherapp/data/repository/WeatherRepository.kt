package com.samay.weatherapp.data.repository

import com.samay.weatherapp.data.remote.ApiClient
import com.samay.weatherapp.data.remote.OpenMeteoApi
import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.ForecastResponse

/**
 * Repository handling weather and geocoding data operations.
 */
class WeatherRepository(
    private val api: OpenMeteoApi = ApiClient.openMeteoApi
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
}
