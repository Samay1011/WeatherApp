package com.samay.weatherapp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for the Open-Meteo Geocoding search endpoint.
 */
@Serializable
data class GeocodingResponse(
    @SerialName("results")
    val results: List<City>? = null
)

/**
 * Represents an individual city result returned by the Geocoding API.
 */
@Serializable
data class City(
    @SerialName("id")
    val id: Long? = null,
    @SerialName("name")
    val name: String,
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("country")
    val country: String? = null,
    @SerialName("admin1")
    val admin1: String? = null,
    @SerialName("country_code")
    val countryCode: String? = null
)
