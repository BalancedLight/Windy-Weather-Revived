package com.BalancedLight.WindyWeather

internal class WeatherSnapshot(
    val weatherCode: Int,
    val currentTempC: Int,
    val highTempC: Int,
    val lowTempC: Int,
    val humidityPercent: Int,
    val windSpeedKmh: Float,
    val sunriseTime: Int,
    val sunsetTime: Int,
    val moonPhase: Int,
    val cityName: String,
    val lastUpdatedMs: Long,
    val codeSource: String
) {
    companion object {
        val UNKNOWN_WEATHER_CODE: Int = -1
        val SOURCE_UNKNOWN: String = "unknown"
        val SOURCE_CURRENT: String = "current"
        val SOURCE_DAILY: String = "daily"
        val SOURCE_SAMSUNG: String = "samsung"
        val SOURCE_OPEN_METEO_FALLBACK: String = "open_meteo_fallback"
        val SOURCE_SAMSUNG_WITH_OPEN_METEO_FALLBACK: String = "samsung+open_meteo_fallback"

        fun empty(): WeatherSnapshot {
            return com.BalancedLight.WindyWeather.WeatherSnapshot(
                com.BalancedLight.WindyWeather.WeatherSnapshot.Companion.UNKNOWN_WEATHER_CODE,
                0,
                0,
                0,
                0,
                0.0f,
                600,
                1800,
                13,
                "",
                0L,
                com.BalancedLight.WindyWeather.WeatherSnapshot.Companion.SOURCE_UNKNOWN
            )
        }
    }
}

