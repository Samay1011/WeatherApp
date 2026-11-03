package com.samay.weatherapp.ui

import com.samay.weatherapp.data.remote.OpenMeteoApi
import com.samay.weatherapp.data.repository.WeatherRepository
import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.ForecastResponse
import com.samay.weatherapp.model.GeocodingResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WeatherViewModelTest {

    private class FakeOpenMeteoApi : OpenMeteoApi {
        var searchCount = 0
        var forecastCallCount = 0
        var shouldThrowError = false
        var cannedCities: List<City> = emptyList()

        override suspend fun searchCities(
            name: String,
            count: Int,
            language: String,
            format: String
        ): GeocodingResponse {
            searchCount++
            if (shouldThrowError) {
                throw IOException("Simulated network failure")
            }
            return GeocodingResponse(results = cannedCities)
        }

        override suspend fun getForecast(
            latitude: Double,
            longitude: Double,
            current: String,
            hourly: String,
            daily: String,
            forecastDays: Int,
            timezone: String
        ): ForecastResponse {
            forecastCallCount++
            throw UnsupportedOperationException("Forecast API should not be called in M2")
        }
    }

    @Test
    fun testEmptyAndSingleCharacterInput_makesNoApiRequest() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        // Empty query
        viewModel.onSearchQueryChanged("")
        delay(500)
        assertEquals(0, fakeApi.searchCount)
        assertEquals(SearchState.Idle, viewModel.uiState.value.searchState)

        // 1-character query
        viewModel.onSearchQueryChanged("D")
        delay(500)
        assertEquals(0, fakeApi.searchCount)
        assertEquals(SearchState.Idle, viewModel.uiState.value.searchState)
    }

    @Test
    fun testSearchSuccess_andDebounce() = runBlocking {
        val fakeApi = FakeOpenMeteoApi().apply {
            cannedCities = listOf(
                City(id = 1L, name = "Delhi", latitude = 28.65, longitude = 77.23, country = "India")
            )
        }
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        // Rapid typing: "De" -> "Del" -> "Delh" within debounce period
        viewModel.onSearchQueryChanged("De")
        delay(100)
        viewModel.onSearchQueryChanged("Del")
        delay(100)
        viewModel.onSearchQueryChanged("Delh")

        // Before debounce elapses (450ms)
        delay(200)
        assertEquals(0, fakeApi.searchCount)

        // After debounce elapses
        delay(350)
        assertEquals(1, fakeApi.searchCount)
        val state = viewModel.uiState.value.searchState
        assertTrue(state is SearchState.Success)
        assertEquals(1, (state as SearchState.Success).cities.size)
        assertEquals("Delhi", state.cities.first().name)
    }

    @Test
    fun testInMemoryPrefixCaching_avoidsExtraNetworkCall() = runBlocking {
        val fakeApi = FakeOpenMeteoApi().apply {
            cannedCities = listOf(
                City(id = 1L, name = "San Francisco", latitude = 37.77, longitude = -122.41, country = "USA"),
                City(id = 2L, name = "San Jose", latitude = 37.33, longitude = -121.88, country = "USA")
            )
        }
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        // Initial search for "San"
        viewModel.onSearchQueryChanged("San")
        delay(550)
        assertEquals(1, fakeApi.searchCount)

        // Extending query to "San Jose" should use in-memory prefix matching
        viewModel.onSearchQueryChanged("San Jose")
        delay(550)
        // searchCount remains 1 because local prefix matching satisfied the query
        assertEquals(1, fakeApi.searchCount)
        val state = viewModel.uiState.value.searchState
        assertTrue(state is SearchState.Success)
        val results = (state as SearchState.Success).cities
        assertEquals(1, results.size)
        assertEquals("San Jose", results.first().name)
    }

    @Test
    fun testPrefixMismatch_triggersFreshApiSearch() = runBlocking {
        val fakeApi = FakeOpenMeteoApi().apply {
            cannedCities = listOf(
                City(id = 1L, name = "Berlin", latitude = 52.52, longitude = 13.40, country = "Germany")
            )
        }
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        // Search for "Ber"
        viewModel.onSearchQueryChanged("Ber")
        delay(550)
        assertEquals(1, fakeApi.searchCount)

        // Query changing prefix to "Lon" must trigger fresh search
        viewModel.onSearchQueryChanged("Lon")
        delay(550)
        assertEquals(2, fakeApi.searchCount)
    }

    @Test
    fun testNetworkError_setsErrorStateWithoutCrashing() = runBlocking {
        val fakeApi = FakeOpenMeteoApi().apply {
            shouldThrowError = true
        }
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        viewModel.onSearchQueryChanged("Delhi")
        delay(550)

        val state = viewModel.uiState.value.searchState
        assertTrue(state is SearchState.Error)
        assertEquals("Unable to search locations. Please check your connection.", (state as SearchState.Error).message)
    }

    @Test
    fun testCitySelection_retainsCoordinatesAndCallsNoForecast() = runBlocking {
        val selected = City(id = 123L, name = "Delhi", latitude = 28.65195, longitude = 77.23149, country = "India")
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        viewModel.onCitySelected(selected)

        assertEquals("Delhi", viewModel.uiState.value.searchQuery)
        assertNotNull(viewModel.uiState.value.selectedCity)
        assertEquals(28.65195, viewModel.uiState.value.selectedCity?.latitude ?: 0.0, 0.0001)
        assertEquals(77.23149, viewModel.uiState.value.selectedCity?.longitude ?: 0.0, 0.0001)
        assertEquals(SearchState.Idle, viewModel.uiState.value.searchState)
        assertEquals(0, fakeApi.forecastCallCount)
    }
}
