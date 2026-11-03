package com.samay.weatherapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samay.weatherapp.data.repository.WeatherRepository
import com.samay.weatherapp.model.City
import com.samay.weatherapp.util.WeatherCodeMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing city search state, debouncing, in-memory prefix caching,
 * and forecast fetching.
 */
class WeatherViewModel(
    private val repository: WeatherRepository = WeatherRepository(),
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var forecastJob: Job? = null

    // In-memory cache holding the raw source results of the most recent API fetch
    private var cachedSourceCities: List<City> = emptyList()
    private var lastFetchedQuery: String = ""

    companion object {
        private const val DEBOUNCE_MILLIS = 450L
        private const val MIN_QUERY_LENGTH = 2
    }

    /**
     * Handles search input changes, applying input validation, debouncing,
     * and in-memory prefix filtering before falling back to network queries.
     */
    fun onSearchQueryChanged(newQuery: String) {
        _uiState.update { it.copy(searchQuery = newQuery) }

        val trimmed = newQuery.trim()

        if (trimmed.length < MIN_QUERY_LENGTH) {
            searchJob?.cancel()
            _uiState.update { it.copy(searchState = SearchState.Idle) }
            return
        }

        // If the query extends the last fetched query, attempt in-memory prefix filtering
        if (lastFetchedQuery.isNotEmpty() && trimmed.startsWith(lastFetchedQuery, ignoreCase = true)) {
            val localMatches = cachedSourceCities.filter {
                it.name.startsWith(trimmed, ignoreCase = true)
            }
            if (localMatches.isNotEmpty()) {
                searchJob?.cancel()
                _uiState.update { it.copy(searchState = SearchState.Success(localMatches)) }
                return
            }
        }

        // Cache could not satisfy the query; trigger debounced fresh API search
        triggerDebouncedSearch(trimmed)
    }

    private fun triggerDebouncedSearch(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(DEBOUNCE_MILLIS)

            _uiState.update { it.copy(searchState = SearchState.Loading) }

            try {
                val results = repository.searchCities(query)

                cachedSourceCities = results
                lastFetchedQuery = query

                _uiState.update { state ->
                    state.copy(
                        searchState = if (results.isEmpty()) {
                            SearchState.Empty
                        } else {
                            SearchState.Success(results)
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(searchState = SearchState.Error("Unable to search locations. Please check your connection."))
                }
            }
        }
    }

    /**
     * Selects a city, clears the suggestions list, and triggers a weather forecast request.
     */
    fun onCitySelected(city: City) {
        searchJob?.cancel()

        // Avoid duplicate requests if the same city is already selected and loaded
        val currentCity = _uiState.value.selectedCity
        val currentForecastState = _uiState.value.forecastState
        if (currentCity == city && currentForecastState is ForecastState.Success) {
            _uiState.update {
                it.copy(
                    searchQuery = city.name,
                    searchState = SearchState.Idle
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                searchQuery = city.name,
                selectedCity = city,
                searchState = SearchState.Idle
            )
        }

        fetchForecastForCity(city)
    }

    private fun fetchForecastForCity(city: City) {
        forecastJob?.cancel()
        forecastJob = scope.launch {
            _uiState.update { it.copy(forecastState = ForecastState.Loading) }

            try {
                val forecast = repository.getForecast(
                    latitude = city.latitude,
                    longitude = city.longitude
                )
                val dailyItems = WeatherCodeMapper.mapDailyForecast(forecast.daily)

                _uiState.update {
                    it.copy(
                        forecastState = ForecastState.Success(
                            forecast = forecast,
                            dailyItems = dailyItems
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(forecastState = ForecastState.Error("Unable to load weather forecast. Please check your connection."))
                }
            }
        }
    }

    /**
     * Retries search for the current query string.
     */
    fun retrySearch() {
        val trimmed = _uiState.value.searchQuery.trim()
        if (trimmed.length >= MIN_QUERY_LENGTH) {
            triggerDebouncedSearch(trimmed)
        }
    }

    /**
     * Retries forecast loading for the currently selected city.
     */
    fun retryForecast() {
        val selectedCity = _uiState.value.selectedCity ?: return
        fetchForecastForCity(selectedCity)
    }
}
