package com.samay.weatherapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samay.weatherapp.model.CachedWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather_cache")

/**
 * Handles saving and retrieving cached weather snapshots using Preferences DataStore.
 * The payload is stored as a single serialized JSON string to ensure city, forecast,
 * and timestamp stay completely consistent.
 */
class WeatherDataStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.weatherDataStore)

    companion object {
        val KEY_CACHED_WEATHER = stringPreferencesKey("cached_weather_payload")
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    /**
     * Reads and deserializes the last cached weather payload.
     * Returns null if no cache exists, or if the stored cache is corrupted or malformed.
     */
    suspend fun getCachedWeather(): CachedWeather? {
        return try {
            val preferences = dataStore.data.first()
            val payload = preferences[KEY_CACHED_WEATHER] ?: return null
            json.decodeFromString<CachedWeather>(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Missing, malformed, or corrupted cache safely defaults to null without crashing
            null
        }
    }

    /**
     * Serializes and writes a cached weather payload into DataStore.
     */
    suspend fun saveCachedWeather(cachedWeather: CachedWeather) {
        try {
            val payload = json.encodeToString(CachedWeather.serializer(), cachedWeather)
            dataStore.edit { preferences ->
                preferences[KEY_CACHED_WEATHER] = payload
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Write exceptions do not crash the app
        }
    }

    /**
     * Clears the cached weather payload from DataStore.
     */
    suspend fun clearCache() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_CACHED_WEATHER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Safe ignore
        }
    }
}
