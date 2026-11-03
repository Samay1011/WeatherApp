package com.samay.weatherapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samay.weatherapp.data.repository.WeatherRepository
import com.samay.weatherapp.model.City
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
 * ViewModel managing city search state, debouncing, and in-memory prefix caching.
 */
class WeatherViewModel(
    private val repository: WeatherRepository = WeatherRepository(),
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

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
     * Selects a city, retains its complete data for future milestones,
     * and clears the suggestion list.
     */
    fun onCitySelected(city: City) {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = city.name,
                selectedCity = city,
                searchState = SearchState.Idle
            )
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
}
