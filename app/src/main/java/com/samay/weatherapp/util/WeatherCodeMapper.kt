package com.samay.weatherapp.util

import com.samay.weatherapp.model.DailyWeather
import com.samay.weatherapp.model.HourlyWeather

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
 * Encapsulates a single hour's forecast mapped from API parallel arrays.
 */
data class HourlyForecastItem(
    val time: String,
    val fullTime: String,
    val temperature: Double,
    val weatherCode: Int,
    val condition: WeatherConditionInfo
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

    /**
     * Safely maps the hourly parallel arrays by index into structured [HourlyForecastItem]s,
     * filtered by [selectedDate] (matching the date portion before 'T').
     */
    fun mapHourlyForecast(hourly: HourlyWeather?, selectedDate: String): List<HourlyForecastItem> {
        if (hourly == null || selectedDate.isBlank()) return emptyList()

        val count = minOf(
            hourly.time.size,
            hourly.temperature2m.size,
            hourly.weatherCode.size
        )

        val items = ArrayList<HourlyForecastItem>()
        for (i in 0 until count) {
            val rawTime = hourly.time[i]
            val datePart = if (rawTime.contains('T')) rawTime.substringBefore('T') else rawTime
            if (datePart == selectedDate) {
                val code = hourly.weatherCode[i]
                val displayTime = if (rawTime.contains('T')) rawTime.substringAfter('T') else rawTime
                items.add(
                    HourlyForecastItem(
                        time = displayTime,
                        fullTime = rawTime,
                        temperature = hourly.temperature2m[i],
                        weatherCode = code,
                        condition = getCondition(code)
                    )
                )
            }
        }
        return items
    }

    /**
     * Formats an epoch millisecond timestamp into a human-readable string (e.g. "Nov 3, 3:45 PM").
     */
    fun formatLastUpdated(timestampMillis: Long): String {
        val date = java.util.Date(timestampMillis)
        val formatter = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        return formatter.format(date)
    }
}
