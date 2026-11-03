package com.samay.weatherapp.ui

import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.ForecastResponse
import com.samay.weatherapp.util.DailyForecastItem
import com.samay.weatherapp.util.HourlyForecastItem

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
        val dailyItems: List<DailyForecastItem>,
        val selectedDate: String? = null,
        val hourlyItems: List<HourlyForecastItem> = emptyList(),
        val isCached: Boolean = false,
        val isOutdated: Boolean = false,
        val isRefreshing: Boolean = false,
        val lastUpdatedMillis: Long? = null
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
