package com.samay.weatherapp.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.samay.weatherapp.data.local.WeatherDataStore
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
 * forecast fetching, and offline DataStore cache restoration/refreshing.
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

        /**
         * Provides a ViewModelProvider.Factory wiring the production WeatherDataStore.
         */
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val dataStore = WeatherDataStore(context)
                    val repository = WeatherRepository(weatherDataStore = dataStore)
                    return WeatherViewModel(repository = repository) as T
                }
            }
    }

    init {
        loadCachedWeatherAndRefresh()
    }

    /**
     * Checks DataStore on startup. If cached weather exists, renders it immediately
     * and initiates a silent background refresh for the saved city.
     */
    fun loadCachedWeatherAndRefresh() {
        scope.launch {
            val cached = repository.getCachedWeather() ?: return@launch

            // If a city has already been actively selected by user interaction, do not override it
            if (_uiState.value.selectedCity != null) return@launch

            val dailyItems = WeatherCodeMapper.mapDailyForecast(cached.forecast.daily)
            val initialSelectedDate = dailyItems.firstOrNull()?.date
            val hourlyItems = if (initialSelectedDate != null) {
                WeatherCodeMapper.mapHourlyForecast(cached.forecast.hourly, initialSelectedDate)
            } else {
                emptyList()
            }

            // Immediately display cached weather
            _uiState.update { state ->
                if (state.selectedCity == null) {
                    state.copy(
                        selectedCity = cached.city,
                        searchQuery = cached.city.name,
                        forecastState = ForecastState.Success(
                            forecast = cached.forecast,
                            dailyItems = dailyItems,
                            selectedDate = initialSelectedDate,
                            hourlyItems = hourlyItems,
                            isCached = true,
                            isOutdated = false,
                            isRefreshing = true,
                            lastUpdatedMillis = cached.timestampMillis
                        )
                    )
                } else {
                    state
                }
            }

            // Trigger silent background refresh for the saved city
            refreshForecastForCity(cached.city, cachedTimestamp = cached.timestampMillis)
        }
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

        // Avoid duplicate requests if the same city is already selected and loaded fresh
        val currentCity = _uiState.value.selectedCity
        val currentForecastState = _uiState.value.forecastState
        if (currentCity == city && currentForecastState is ForecastState.Success && !currentForecastState.isOutdated) {
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
                val now = System.currentTimeMillis()
                repository.saveCachedWeather(city, forecast, now)

                val dailyItems = WeatherCodeMapper.mapDailyForecast(forecast.daily)
                val initialSelectedDate = dailyItems.firstOrNull()?.date
                val hourlyItems = if (initialSelectedDate != null) {
                    WeatherCodeMapper.mapHourlyForecast(forecast.hourly, initialSelectedDate)
                } else {
                    emptyList()
                }

                _uiState.update { state ->
                    if (state.selectedCity == city) {
                        state.copy(
                            forecastState = ForecastState.Success(
                                forecast = forecast,
                                dailyItems = dailyItems,
                                selectedDate = initialSelectedDate,
                                hourlyItems = hourlyItems,
                                isCached = false,
                                isOutdated = false,
                                isRefreshing = false,
                                lastUpdatedMillis = now
                            )
                        )
                    } else {
                        state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (state.selectedCity == city) {
                        state.copy(
                            forecastState = ForecastState.Error("Unable to load weather forecast. Please check your connection.")
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun refreshForecastForCity(city: City, cachedTimestamp: Long) {
        forecastJob?.cancel()
        forecastJob = scope.launch {
            try {
                val freshForecast = repository.getForecast(
                    latitude = city.latitude,
                    longitude = city.longitude
                )
                val newTimestamp = System.currentTimeMillis()
                repository.saveCachedWeather(city, freshForecast, newTimestamp)

                val dailyItems = WeatherCodeMapper.mapDailyForecast(freshForecast.daily)
                val currentSelectedDate = (_uiState.value.forecastState as? ForecastState.Success)?.selectedDate
                val selectedDate = if (dailyItems.any { it.date == currentSelectedDate }) {
                    currentSelectedDate
                } else {
                    dailyItems.firstOrNull()?.date
                }
                val hourlyItems = if (selectedDate != null) {
                    WeatherCodeMapper.mapHourlyForecast(freshForecast.hourly, selectedDate)
                } else {
                    emptyList()
                }

                _uiState.update { state ->
                    if (state.selectedCity == city) {
                        state.copy(
                            forecastState = ForecastState.Success(
                                forecast = freshForecast,
                                dailyItems = dailyItems,
                                selectedDate = selectedDate,
                                hourlyItems = hourlyItems,
                                isCached = false,
                                isOutdated = false,
                                isRefreshing = false,
                                lastUpdatedMillis = newTimestamp
                            )
                        )
                    } else {
                        state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If background refresh fails, preserve cached weather and flag as outdated
                _uiState.update { state ->
                    if (state.selectedCity == city && state.forecastState is ForecastState.Success) {
                        state.copy(
                            forecastState = state.forecastState.copy(
                                isRefreshing = false,
                                isOutdated = true,
                                lastUpdatedMillis = cachedTimestamp
                            )
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    /**
     * Updates the selected forecast date and extracts the hourly entries
     * from the already fetched forecast payload without making an API request.
     */
    fun onDaySelected(date: String) {
        val currentState = _uiState.value.forecastState
        if (currentState !is ForecastState.Success) return
        if (currentState.selectedDate == date) return

        val updatedHourly = WeatherCodeMapper.mapHourlyForecast(currentState.forecast.hourly, date)

        _uiState.update { state ->
            if (state.forecastState is ForecastState.Success) {
                state.copy(
                    forecastState = state.forecastState.copy(
                        selectedDate = date,
                        hourlyItems = updatedHourly
                    )
                )
            } else {
                state
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
        val currentForecastState = _uiState.value.forecastState
        if (currentForecastState is ForecastState.Success) {
            _uiState.update {
                it.copy(
                    forecastState = currentForecastState.copy(
                        isRefreshing = true,
                        isOutdated = false
                    )
                )
            }
            refreshForecastForCity(
                city = selectedCity,
                cachedTimestamp = currentForecastState.lastUpdatedMillis ?: System.currentTimeMillis()
            )
        } else {
            fetchForecastForCity(selectedCity)
        }
    }
}
