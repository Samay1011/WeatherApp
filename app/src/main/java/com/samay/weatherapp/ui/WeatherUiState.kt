package com.samay.weatherapp.ui

import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.ForecastResponse
import com.samay.weatherapp.util.DailyForecastItem

/**
 * Represents the current status of city search operations.
 */
sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Success(val cities: List<City>) : SearchState
    data object Empty : SearchState
    data class Error(val message: String) : SearchState
}

/**
 * Represents the current status of weather forecast fetching.
 */
sealed interface ForecastState {
    data object Idle : ForecastState
    data object Loading : ForecastState
    data class Success(
        val forecast: ForecastResponse,
        val dailyItems: List<DailyForecastItem>
    ) : ForecastState
    data class Error(val message: String) : ForecastState
}

/**
 * Top-level UI state for the weather home screen.
 */
data class WeatherUiState(
    val searchQuery: String = "",
    val searchState: SearchState = SearchState.Idle,
    val selectedCity: City? = null,
    val forecastState: ForecastState = ForecastState.Idle
)
