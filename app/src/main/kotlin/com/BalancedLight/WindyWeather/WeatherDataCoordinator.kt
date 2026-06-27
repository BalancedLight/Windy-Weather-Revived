package com.BalancedLight.WindyWeather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

internal object WeatherDataCoordinator {
    private val TAG = "WeatherCoordinator"

    fun refreshAsync(
        context: Context,
        sourceMode: String?,
        callback: OpenMeteoWeatherRepository.Callback?
    ) {
        val appContext: Context = context.applicationContext
        if (!SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(sourceMode)) {
            OpenMeteoWeatherRepository.refreshAsync(appContext, callback)
            return
        }

        val samsungSnapshot: SamsungWeatherRepository.SamsungSnapshot? =
            SamsungWeatherRepository.fetchLatest(appContext)
        OpenMeteoWeatherRepository.refreshAsync(appContext) { openMeteoSnapshot ->
            val baseline: WeatherSnapshot? = if (openMeteoSnapshot != null)
                openMeteoSnapshot
            else
                OpenMeteoWeatherRepository.readFromCache(appContext)
            val result: WeatherSnapshot
            if (samsungSnapshot == null || !samsungSnapshot.hasAnyData()) {
                result = if (baseline != null)
                    com.BalancedLight.WindyWeather.WeatherDataCoordinator.copyWithSource(
                        baseline,
                        WeatherSnapshot.SOURCE_OPEN_METEO_FALLBACK
                    )
                else
                    WeatherSnapshot.empty()
            } else {
                result =
                    com.BalancedLight.WindyWeather.WeatherDataCoordinator.mergeSamsungWithFallback(
                        samsungSnapshot,
                        baseline
                    )
            }
            OpenMeteoWeatherRepository.writeToCache(appContext, result)
            Log.d(
                com.BalancedLight.WindyWeather.WeatherDataCoordinator.TAG,
                "Resolved weather source=" + result.codeSource + " mode=" + sourceMode
            )
            if (callback != null) {
                callback.onWeatherUpdated(result)
            }
        }
    }

    fun refreshSamsungOnlyAsync(context: Context, callback: OpenMeteoWeatherRepository.Callback?) {
        val appContext: Context = context.applicationContext
        Thread({
            val samsungSnapshot: SamsungWeatherRepository.SamsungSnapshot? =
                SamsungWeatherRepository.fetchLatest(appContext)
            val baseline: WeatherSnapshot? = OpenMeteoWeatherRepository.readFromCache(appContext)
            val result: WeatherSnapshot?
            if (samsungSnapshot == null || !samsungSnapshot.hasAnyData()) {
                result = if (baseline != null)
                    com.BalancedLight.WindyWeather.WeatherDataCoordinator.copyWithSource(
                        baseline,
                        WeatherSnapshot.SOURCE_OPEN_METEO_FALLBACK
                    )
                else
                    WeatherSnapshot.empty()
            } else {
                result =
                    com.BalancedLight.WindyWeather.WeatherDataCoordinator.mergeSamsungWithFallback(
                        samsungSnapshot,
                        baseline
                    )
            }
            OpenMeteoWeatherRepository.writeToCache(appContext, result)
            if (callback != null) {
                Handler(Looper.getMainLooper()).post({ callback.onWeatherUpdated(result) })
            }
        }, "ww-samsung-only-refresh").start()
    }

    fun readFromCache(context: Context): WeatherSnapshot {
        return OpenMeteoWeatherRepository.readFromCache(context) ?: WeatherSnapshot.empty()
    }

    fun isCacheStale(context: Context, maxAgeMs: Long): Boolean {
        return OpenMeteoWeatherRepository.isCacheStale(context, maxAgeMs)
    }

    fun isSamsungLikelyAvailable(context: Context?): Boolean {
        return SamsungWeatherRepository.isLikelySupported(context)
    }

    fun hasSamsungWeatherPermission(context: Context?): Boolean {
        return SamsungWeatherRepository.hasReadDangerousProviderPermission(context)
    }

    private fun mergeSamsungWithFallback(
        samsung: SamsungWeatherRepository.SamsungSnapshot,
        baseline: WeatherSnapshot?
    ): WeatherSnapshot {
        val safeBaseline: WeatherSnapshot =
            if (baseline != null) baseline else WeatherSnapshot.empty()

        var weatherCode: Int =
            samsung.weatherCode ?: safeBaseline.weatherCode
        if (weatherCode == WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
            weatherCode = 2
        }
        val currentTemp: Int =
            samsung.currentTempC ?: safeBaseline.currentTempC
        val highTemp: Int =
            samsung.highTempC ?: safeBaseline.highTempC
        val lowTemp: Int = samsung.lowTempC ?: safeBaseline.lowTempC
        val humidity: Int =
            samsung.humidityPercent ?: safeBaseline.humidityPercent
        val wind: Float =
            samsung.windSpeedKmh ?: safeBaseline.windSpeedKmh
        val sunrise: Int =
            samsung.sunriseTime ?: safeBaseline.sunriseTime
        val sunset: Int =
            samsung.sunsetTime ?: safeBaseline.sunsetTime
        val city: String = if (samsung.cityName != null && samsung.cityName.isNotEmpty())
            samsung.cityName
        else
            safeBaseline.cityName
        var lastUpdated: Long =
            if (samsung.lastUpdatedMs > 0L) samsung.lastUpdatedMs else safeBaseline.lastUpdatedMs
        if (lastUpdated <= 0L) {
            lastUpdated = System.currentTimeMillis()
        }

        val codeSource: String = if (samsung.isCompleteForOverride)
            WeatherSnapshot.SOURCE_SAMSUNG
        else
            WeatherSnapshot.SOURCE_SAMSUNG_WITH_OPEN_METEO_FALLBACK

        return WeatherSnapshot(
            weatherCode,
            currentTemp,
            highTemp,
            lowTemp,
            humidity,
            wind,
            sunrise,
            sunset,
            safeBaseline.moonPhase,
            city,
            lastUpdated,
            codeSource
        )
    }

    private fun copyWithSource(snapshot: WeatherSnapshot, source: String): WeatherSnapshot {
        return WeatherSnapshot(
            snapshot.weatherCode,
            snapshot.currentTempC,
            snapshot.highTempC,
            snapshot.lowTempC,
            snapshot.humidityPercent,
            snapshot.windSpeedKmh,
            snapshot.sunriseTime,
            snapshot.sunsetTime,
            snapshot.moonPhase,
            snapshot.cityName,
            if (snapshot.lastUpdatedMs > 0L) snapshot.lastUpdatedMs else System.currentTimeMillis(),
            source
        )
    }
}
