package com.samay.weatherapp.ui

import com.samay.weatherapp.data.remote.OpenMeteoApi
import com.samay.weatherapp.data.repository.WeatherRepository
import com.samay.weatherapp.model.City
import com.samay.weatherapp.model.CurrentWeather
import com.samay.weatherapp.model.DailyWeather
import com.samay.weatherapp.model.ForecastResponse
import com.samay.weatherapp.model.GeocodingResponse
import com.samay.weatherapp.model.HourlyWeather
import com.samay.weatherapp.model.CachedWeather
import com.samay.weatherapp.data.local.WeatherDataStore
import com.samay.weatherapp.util.WeatherCodeMapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    private fun createTestDataStore(): Pair<File, WeatherDataStore> {
        val tempFile = File.createTempFile("test_ds", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        return Pair(tempFile, WeatherDataStore(dataStore))
    }

    @Test
    fun testForecastResponseSavedToDataStore_onSuccessfulSelection() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val (_, weatherDataStore) = createTestDataStore()
        val repository = WeatherRepository(fakeApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)

        val city = City(name = "Mumbai", latitude = 19.076, longitude = 72.877, country = "India")
        viewModel.onCitySelected(city)

        // Poll DataStore to allow asynchronous disk flush
        var cached: CachedWeather? = null
        for (i in 1..20) {
            delay(50)
            cached = weatherDataStore.getCachedWeather()
            if (cached != null) break
        }

        assertNotNull(cached)
        assertEquals("Mumbai", cached?.city?.name)
        assertEquals(19.076, cached?.city?.latitude ?: 0.0, 0.001)
        assertNotNull(cached?.forecast)
        assertTrue((cached?.timestampMillis ?: 0L) > 0L)
    }

    @Test
    fun testSavedCityAndForecastRestored_afterRecreatingViewModel() = runBlocking {
        val fakeApi = FakeOpenMeteoApi()
        val (_, weatherDataStore) = createTestDataStore()
        val repository1 = WeatherRepository(fakeApi, weatherDataStore)
        val viewModel1 = WeatherViewModel(repository1, coroutineScope = this)

        val city = City(name = "Tokyo", latitude = 35.676, longitude = 139.650, country = "Japan")
        viewModel1.onCitySelected(city)
        delay(100)

        // Recreate ViewModel using the same DataStore
        val repository2 = WeatherRepository(fakeApi, weatherDataStore)
        val viewModel2 = WeatherViewModel(repository2, coroutineScope = this)
        delay(100)

        assertEquals("Tokyo", viewModel2.uiState.value.selectedCity?.name)
        val state = viewModel2.uiState.value.forecastState
        assertTrue(state is ForecastState.Success)
        val successState = state as ForecastState.Success
        assertEquals(5, successState.dailyItems.size)
    }

    @Test
    fun testCachedWeatherDisplayed_whileStartupRefreshRunning() = runBlocking {
        val (_, weatherDataStore) = createTestDataStore()
        val city = City(name = "Berlin", latitude = 52.52, longitude = 13.405, country = "Germany")
        val initialTimestamp = 123456789L
        val dummyForecast = ForecastResponse(
            latitude = 52.52,
            longitude = 13.405,
            current = CurrentWeather("2026-11-03T10:00", 15.0, 60, 0, 5.0),
            daily = DailyWeather(
                time = listOf("2026-11-03"),
                weatherCode = listOf(0),
                temperature2mMax = listOf(16.0),
                temperature2mMin = listOf(8.0)
            )
        )
        weatherDataStore.saveCachedWeather(CachedWeather(city, dummyForecast, initialTimestamp))

        val delayedApi = FakeOpenMeteoApi().apply {
            forecastDelayMs = 200L // Simulate network latency
        }
        val repository = WeatherRepository(delayedApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)

        delay(50) // Cache is loaded, but network refresh is still in-flight
        val state = viewModel.uiState.value.forecastState
        assertTrue(state is ForecastState.Success)
        val successState = state as ForecastState.Success
        assertEquals(true, successState.isCached)
        assertEquals(true, successState.isRefreshing)
        assertEquals(false, successState.isOutdated)
        assertEquals(initialTimestamp, successState.lastUpdatedMillis)
        assertEquals("Berlin", viewModel.uiState.value.selectedCity?.name)

        delay(250) // Network refresh finishes
        val refreshedState = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals(false, refreshedState.isRefreshing)
        assertEquals(false, refreshedState.isCached)
        assertTrue((refreshedState.lastUpdatedMillis ?: 0L) > initialTimestamp)
    }

    @Test
    fun testSuccessfulBackgroundRefresh_replacesPreviousCache() = runBlocking {
        val (_, weatherDataStore) = createTestDataStore()
        val city = City(name = "Paris", latitude = 48.856, longitude = 2.352, country = "France")
        val initialTimestamp = 1000000L
        val oldForecast = ForecastResponse(
            latitude = 48.856,
            longitude = 2.352,
            current = CurrentWeather("2026-11-01T12:00", 12.0, 80, 3, 10.0)
        )
        weatherDataStore.saveCachedWeather(CachedWeather(city, oldForecast, initialTimestamp))

        val fakeApi = FakeOpenMeteoApi()
        val repository = WeatherRepository(fakeApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)
        delay(100)

        val updatedCache = weatherDataStore.getCachedWeather()
        assertNotNull(updatedCache)
        assertTrue((updatedCache?.timestampMillis ?: 0L) > initialTimestamp)
        assertEquals(28.5, updatedCache?.forecast?.current?.temperature2m ?: 0.0, 0.001)

        val state = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals(false, state.isOutdated)
        assertEquals(false, state.isRefreshing)
    }

    @Test
    fun testFailedBackgroundRefresh_preservesCachedWeatherAndTimestamp() = runBlocking {
        val (_, weatherDataStore) = createTestDataStore()
        val city = City(name = "Cairo", latitude = 30.044, longitude = 31.235, country = "Egypt")
        val initialTimestamp = 99999999L
        val cachedForecast = ForecastResponse(
            latitude = 30.044,
            longitude = 31.235,
            current = CurrentWeather("2026-11-03T12:00", 32.0, 30, 0, 12.0),
            daily = DailyWeather(
                time = listOf("2026-11-03"),
                weatherCode = listOf(0),
                temperature2mMax = listOf(33.0),
                temperature2mMin = listOf(22.0)
            )
        )
        weatherDataStore.saveCachedWeather(CachedWeather(city, cachedForecast, initialTimestamp))

        val failingApi = FakeOpenMeteoApi().apply {
            shouldThrowForecastError = true
        }
        val repository = WeatherRepository(failingApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)
        delay(100)

        // UI preserves cached data, marks it as outdated, and keeps the original timestamp
        val state = viewModel.uiState.value.forecastState
        assertTrue(state is ForecastState.Success)
        val successState = state as ForecastState.Success
        assertEquals(true, successState.isOutdated)
        assertEquals(false, successState.isRefreshing)
        assertEquals(initialTimestamp, successState.lastUpdatedMillis)
        assertEquals(32.0, successState.forecast.current?.temperature2m ?: 0.0, 0.001)

        // DataStore cache was NOT overwritten or cleared
        val storedCache = weatherDataStore.getCachedWeather()
        assertEquals(initialTimestamp, storedCache?.timestampMillis)
    }

    @Test
    fun testNoCachePlusFailedNetworkRequest_showsErrorState() = runBlocking {
        val (_, weatherDataStore) = createTestDataStore()
        val failingApi = FakeOpenMeteoApi().apply {
            shouldThrowForecastError = true
        }
        val repository = WeatherRepository(failingApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)

        val city = City(name = "Oslo", latitude = 59.913, longitude = 10.752, country = "Norway")
        viewModel.onCitySelected(city)
        delay(100)

        val state = viewModel.uiState.value.forecastState
        assertTrue(state is ForecastState.Error)
        assertEquals(null, weatherDataStore.getCachedWeather())
    }

    @Test
    fun testSelectingAnotherCity_doesNotDisplayPreviousCityCachedForecast() = runBlocking {
        val (_, weatherDataStore) = createTestDataStore()
        val slowApi = FakeOpenMeteoApi().apply {
            forecastDelayMs = 200L
        }
        val repository = WeatherRepository(slowApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)

        val city1 = City(name = "Rome", latitude = 41.902, longitude = 12.496, country = "Italy")
        slowApi.forecastDelayMs = 0L
        viewModel.onCitySelected(city1)
        delay(50)

        // Rome is successfully loaded and cached
        assertTrue(viewModel.uiState.value.forecastState is ForecastState.Success)
        assertEquals("Rome", viewModel.uiState.value.selectedCity?.name)

        // Now select City 2 with delay
        slowApi.forecastDelayMs = 200L
        val city2 = City(name = "Madrid", latitude = 40.416, longitude = -3.703, country = "Spain")
        viewModel.onCitySelected(city2)
        delay(30) // City 2 is in-flight

        // Should be Loading for Madrid, NOT showing Rome's forecast
        assertEquals("Madrid", viewModel.uiState.value.selectedCity?.name)
        assertEquals(ForecastState.Loading, viewModel.uiState.value.forecastState)

        delay(250) // Madrid finishes
        val madridState = viewModel.uiState.value.forecastState as ForecastState.Success
        assertEquals("Madrid", viewModel.uiState.value.selectedCity?.name)
        assertEquals(40.416, madridState.forecast.latitude, 0.001)
    }

    @Test
    fun testMalformedOrMissingCachedData_doesNotCrashApp() = runBlocking {
        val (tempFile, weatherDataStore) = createTestDataStore()
        val testDataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })

        // Inject corrupted/malformed JSON string
        testDataStore.edit { prefs ->
            prefs[WeatherDataStore.KEY_CACHED_WEATHER] = "{\"bad_json\": true, Corrupted..."
        }

        // Reading corrupted cache must return null safely
        val cached = weatherDataStore.getCachedWeather()
        assertEquals(null, cached)

        // Starting ViewModel with corrupted cache must not crash and remain Idle
        val fakeApi = FakeOpenMeteoApi()
        val repository = WeatherRepository(fakeApi, weatherDataStore)
        val viewModel = WeatherViewModel(repository, coroutineScope = this)
        delay(50)

        assertEquals(null, viewModel.uiState.value.selectedCity)
        assertEquals(ForecastState.Idle, viewModel.uiState.value.forecastState)
    }
}
