package com.samay.weatherapp.util

import com.samay.weatherapp.model.DailyWeather

/**
 * Encapsulates human-readable weather condition text and visual icon emoji.
 */
data class WeatherConditionInfo(
    val description: String,
    val icon: String
)

/**
 * Encapsulates a single day's forecast mapped from API parallel arrays.
 */
data class DailyForecastItem(
    val date: String,
    val weatherCode: Int,
    val condition: WeatherConditionInfo,
    val maxTemp: Double,
    val minTemp: Double
)

/**
 * Centralized mapping logic for WMO weather interpretation codes.
 */
object WeatherCodeMapper {

    /**
     * Maps WMO numeric weather codes to description and emoji representation.
     */
    fun getCondition(weatherCode: Int): WeatherConditionInfo {
        return when (weatherCode) {
            0 -> WeatherConditionInfo("Clear sky", "☀️")
            1 -> WeatherConditionInfo("Mainly clear", "🌤️")
            2 -> WeatherConditionInfo("Partly cloudy", "⛅")
            3 -> WeatherConditionInfo("Overcast", "☁️")
            45, 48 -> WeatherConditionInfo("Fog", "🌫️")
            51, 53, 55 -> WeatherConditionInfo("Drizzle", "🌦️")
            61, 63, 65 -> WeatherConditionInfo("Rain", "🌧️")
            71, 73, 75 -> WeatherConditionInfo("Snow", "🌨️")
            80, 81, 82 -> WeatherConditionInfo("Rain showers", "🌧️")
            95, 96, 99 -> WeatherConditionInfo("Thunderstorm", "⛈️")
            else -> WeatherConditionInfo("Unknown weather", "🌡️")
        }
    }

    /**
     * Safely maps the daily parallel arrays by index into structured [DailyForecastItem]s.
     * Guaranteed to extract up to [limit] entries and safely handle mismatched or missing items.
     */
    fun mapDailyForecast(daily: DailyWeather?, limit: Int = 5): List<DailyForecastItem> {
        if (daily == null) return emptyList()

        val count = minOf(
            limit,
            daily.time.size,
            daily.weatherCode.size,
            daily.temperature2mMax.size,
            daily.temperature2mMin.size
        )

        return (0 until count).map { index ->
            val code = daily.weatherCode[index]
            DailyForecastItem(
                date = daily.time[index],
                weatherCode = code,
                condition = getCondition(code),
                maxTemp = daily.temperature2mMax[index],
                minTemp = daily.temperature2mMin[index]
            )
        }
    }
}
