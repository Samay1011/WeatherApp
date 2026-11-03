package com.samay.weatherapp.ui

import com.samay.weatherapp.model.City

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
 * Top-level UI state for the weather home screen.
 */
data class WeatherUiState(
    val searchQuery: String = "",
    val searchState: SearchState = SearchState.Idle,
    val selectedCity: City? = null
)
