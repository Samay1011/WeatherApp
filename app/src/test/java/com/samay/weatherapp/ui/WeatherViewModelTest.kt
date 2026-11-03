package com.samay.weatherapp.ui

import com.samay.weatherapp.data.remote.OpenMeteoApi
import com.samay.weatherapp.data.repository.WeatherRepository
import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.CurrentWeather
import com.samay.weatherapp.model.DailyWeather
import com.samay.weatherapp.model.ForecastResponse
import com.samay.weatherapp.model.GeocodingResponse
import com.samay.weatherapp.model.HourlyWeather
import com.samay.weatherapp.util.WeatherCodeMapper
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
        var shouldThrowSearchError = false
        var shouldThrowForecastError = false
        var cannedCities: List<City> = emptyList()
        var forecastDelayMs: Long = 0L

        var lastRequestedLatitude: Double? = null
        var lastRequestedLongitude: Double? = null

        override suspend fun searchCities(
            name: String,
            count: Int,
            language: String,
            format: String
        ): GeocodingResponse {
            searchCount++
            if (shouldThrowSearchError) {
                throw IOException("Simulated geocoding network failure")
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
            lastRequestedLatitude = latitude
            lastRequestedLongitude = longitude

            if (forecastDelayMs > 0) {
                delay(forecastDelayMs)
            }

            if (shouldThrowForecastError) {
                throw IOException("Simulated forecast network failure")
            }

            return ForecastResponse(
                latitude = latitude,
                longitude = longitude,
                timezone = "auto",
                current = CurrentWeather(
                    time = "2026-11-03T12:00",
                    temperature2m = 28.5,
                    relativeHumidity2m = 45,
                    weatherCode = 0,
                    windSpeed10m = 8.2
                ),
                daily = DailyWeather(
                    time = listOf("2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07"),
                    weatherCode = listOf(0, 1, 2, 61, 95),
                    temperature2mMax = listOf(29.0, 28.5, 27.0, 24.0, 26.5),
                    temperature2mMin = listOf(18.0, 17.5, 16.0, 15.0, 16.0)
                ),
                hourly = HourlyWeather(
                    time = listOf(
                        "2026-11-03T00:00", "2026-11-03T01:00", "2026-11-03T02:00",
                        "2026-11-04T00:00", "2026-11-04T01:00"
                    ),
                    temperature2m = listOf(20.0, 19.5, 19.0, 21.0, 20.5),
                    weatherCode = listOf(0, 0, 1, 2, 3)
                )
            )
        }
    }

    @Test
    fun testEmptyAndSingleCharacterInput_makesNoApiRequest() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        viewModel.onSearchQueryChanged("")
        delay(500)
        assertEquals(0, fakeApi.searchCount)
        assertEquals(SearchState.Idle, viewModel.uiState.value.searchState)

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

        viewModel.onSearchQueryChanged("De")
        delay(100)
        viewModel.onSearchQueryChanged("Del")
        delay(100)
        viewModel.onSearchQueryChanged("Delh")

        delay(200)
        assertEquals(0, fakeApi.searchCount)

        delay(350)
        assertEquals(1, fakeApi.searchCount)
        val state = viewModel.uiState.value.searchState
        assertTrue(state is SearchState.Success)
        assertEquals("Delhi", (state as SearchState.Success).cities.first().name)
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

        viewModel.onSearchQueryChanged("San")
        delay(550)
        assertEquals(1, fakeApi.searchCount)

        viewModel.onSearchQueryChanged("San Jose")
        delay(550)
        assertEquals(1, fakeApi.searchCount)
        val state = viewModel.uiState.value.searchState
        assertTrue(state is SearchState.Success)
        assertEquals("San Jose", (state as SearchState.Success).cities.first().name)
    }

    @Test
    fun testForecastLoading_successOnCitySelection_andDefaultDayHourly() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        val city = City(id = 10L, name = "Delhi", latitude = 28.65195, longitude = 77.23149, country = "India")
        viewModel.onCitySelected(city)

        delay(100)

        assertEquals(1, fakeApi.forecastCallCount)
        val forecastState = viewModel.uiState.value.forecastState
        assertTrue(forecastState is ForecastState.Success)
        val success = forecastState as ForecastState.Success

        // Verify default selected day is Day 1
        assertEquals("2026-11-03", success.selectedDate)
        assertEquals(3, success.hourlyItems.size)
        assertEquals("00:00", success.hourlyItems[0].time)
        assertEquals("2026-11-03T00:00", success.hourlyItems[0].fullTime)
        assertEquals(20.0, success.hourlyItems[0].temperature, 0.001)
    }

    @Test
    fun testSelectingDifferentDay_updatesHourlyWithoutExtraApiCall() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        val city = City(id = 10L, name = "Delhi", latitude = 28.65, longitude = 77.23, country = "India")
        viewModel.onCitySelected(city)
        delay(100)

        assertEquals(1, fakeApi.forecastCallCount)
        val state1 = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals("2026-11-03", state1.selectedDate)
        assertEquals(3, state1.hourlyItems.size)

        // Select Day 2 ("2026-11-04")
        viewModel.onDaySelected("2026-11-04")

        // Ensure ZERO additional API calls were made
        assertEquals(1, fakeApi.forecastCallCount)

        val state2 = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals("2026-11-04", state2.selectedDate)
        assertEquals(2, state2.hourlyItems.size)
        assertEquals("00:00", state2.hourlyItems[0].time)
        assertEquals(21.0, state2.hourlyItems[0].temperature, 0.001)
        assertEquals("Partly cloudy", state2.hourlyItems[0].condition.description)
    }

    @Test
    fun testForecastApiFailure_andRetry() = runBlocking {
        val fakeApi = FakeOpenMeteoApi().apply {
            shouldThrowForecastError = true
        }
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        val city = City(id = 20L, name = "London", latitude = 51.5074, longitude = -0.1278, country = "UK")
        viewModel.onCitySelected(city)
        delay(100)

        assertEquals(1, fakeApi.forecastCallCount)
        assertTrue(viewModel.uiState.value.forecastState is ForecastState.Error)

        // Clear error and retry
        fakeApi.shouldThrowForecastError = false
        viewModel.retryForecast()
        delay(100)

        assertEquals(2, fakeApi.forecastCallCount)
        assertTrue(viewModel.uiState.value.forecastState is ForecastState.Success)
    }

    @Test
    fun testSwitchingSelectedCities_clearsOldHourlyDataAndResetsDay() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val viewModel = WeatherViewModel(WeatherRepository(fakeApi), coroutineScope = this)

        val cityA = City(id = 1L, name = "CityA", latitude = 10.0, longitude = 20.0)
        val cityB = City(id = 2L, name = "CityB", latitude = 30.0, longitude = 40.0)

        // Select City A
        viewModel.onCitySelected(cityA)
        delay(100)
        // City A selects Day 2
        viewModel.onDaySelected("2026-11-04")
        val stateA = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals("2026-11-04", stateA.selectedDate)

        // Switch to City B
        viewModel.onCitySelected(cityB)
        delay(100)

        // City B resets to Day 1
        val stateB = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals("CityB", viewModel.uiState.value.selectedCity?.name)
        assertEquals("2026-11-03", stateB.selectedDate)
        assertEquals(3, stateB.hourlyItems.size)
    }

    @Test
    fun testWmoCodeMapping_allCategories() {
        assertEquals("Clear sky", WeatherCodeMapper.getCondition(0).description)
        assertEquals("☀️", WeatherCodeMapper.getCondition(0).icon)

        assertEquals("Mainly clear", WeatherCodeMapper.getCondition(1).description)
        assertEquals("Partly cloudy", WeatherCodeMapper.getCondition(2).description)
        assertEquals("Overcast", WeatherCodeMapper.getCondition(3).description)

        assertEquals("Fog", WeatherCodeMapper.getCondition(45).description)
        assertEquals("Fog", WeatherCodeMapper.getCondition(48).description)

        assertEquals("Drizzle", WeatherCodeMapper.getCondition(51).description)
        assertEquals("Drizzle", WeatherCodeMapper.getCondition(53).description)
        assertEquals("Drizzle", WeatherCodeMapper.getCondition(55).description)

        assertEquals("Rain", WeatherCodeMapper.getCondition(61).description)
        assertEquals("Rain", WeatherCodeMapper.getCondition(63).description)
        assertEquals("Rain", WeatherCodeMapper.getCondition(65).description)

        assertEquals("Snow", WeatherCodeMapper.getCondition(71).description)
        assertEquals("Snow", WeatherCodeMapper.getCondition(73).description)
        assertEquals("Snow", WeatherCodeMapper.getCondition(75).description)

        assertEquals("Rain showers", WeatherCodeMapper.getCondition(80).description)
        assertEquals("Rain showers", WeatherCodeMapper.getCondition(81).description)
        assertEquals("Rain showers", WeatherCodeMapper.getCondition(82).description)

        assertEquals("Thunderstorm", WeatherCodeMapper.getCondition(95).description)
        assertEquals("Thunderstorm", WeatherCodeMapper.getCondition(96).description)
        assertEquals("Thunderstorm", WeatherCodeMapper.getCondition(99).description)

        assertEquals("Unknown weather", WeatherCodeMapper.getCondition(1234).description)
        assertEquals("🌡️", WeatherCodeMapper.getCondition(1234).icon)
    }

    @Test
    fun testFiveDayDailyDataMapping() {
        val nullResult = WeatherCodeMapper.mapDailyForecast(null)
        assertTrue(nullResult.isEmpty())

        val sevenDayData = DailyWeather(
            time = listOf("D1", "D2", "D3", "D4", "D5", "D6", "D7"),
            weatherCode = listOf(0, 1, 2, 3, 45, 51, 61),
            temperature2mMax = listOf(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0),
            temperature2mMin = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0)
        )
        val cappedResult = WeatherCodeMapper.mapDailyForecast(sevenDayData, limit = 5)
        assertEquals(5, cappedResult.size)
        assertEquals("D1", cappedResult.first().date)
        assertEquals("D5", cappedResult.last().date)

        val mismatched = DailyWeather(
            time = listOf("D1", "D2", "D3"),
            weatherCode = listOf(0, 1),
            temperature2mMax = listOf(20.0, 21.0, 22.0),
            temperature2mMin = listOf(10.0, 11.0, 12.0)
        )
        val safeResult = WeatherCodeMapper.mapDailyForecast(mismatched)
        assertEquals(2, safeResult.size)
        assertEquals("D1", safeResult[0].date)
        assertEquals("D2", safeResult[1].date)
    }

    @Test
    fun testHourlyDataMapping_nullEmptyAndMismatchedArrays() {
        // Null hourly
        val nullResult = WeatherCodeMapper.mapHourlyForecast(null, "2026-11-03")
        assertTrue(nullResult.isEmpty())

        // Blank selected date
        val validHourly = HourlyWeather(
            time = listOf("2026-11-03T10:00", "2026-11-03T11:00"),
            temperature2m = listOf(25.0, 26.0),
            weatherCode = listOf(0, 1)
        )
        val blankDateResult = WeatherCodeMapper.mapHourlyForecast(validHourly, "")
        assertTrue(blankDateResult.isEmpty())

        // Non-matching date
        val nonMatchingResult = WeatherCodeMapper.mapHourlyForecast(validHourly, "2026-11-05")
        assertTrue(nonMatchingResult.isEmpty())

        // Mismatched parallel arrays (e.g. 3 timestamps, but only 2 temperatures and 1 weather code)
        val mismatched = HourlyWeather(
            time = listOf("2026-11-03T10:00", "2026-11-03T11:00", "2026-11-03T12:00"),
            temperature2m = listOf(25.0, 26.0),
            weatherCode = listOf(0)
        )
        val safeResult = WeatherCodeMapper.mapHourlyForecast(mismatched, "2026-11-03")
        assertEquals(1, safeResult.size)
        assertEquals("10:00", safeResult[0].time)
        assertEquals("2026-11-03T10:00", safeResult[0].fullTime)
        assertEquals(25.0, safeResult[0].temperature, 0.001)
        assertEquals("Clear sky", safeResult[0].condition.description)
    }
}
