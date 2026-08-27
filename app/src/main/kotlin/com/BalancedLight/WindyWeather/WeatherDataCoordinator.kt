package com.BalancedLight.WindyWeather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

internal object WeatherDataCoordinator {
    private const val TAG = "WeatherCoordinator"
    private const val PREF_NAME = "com.BalancedLight.WindyWeather"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun refreshAsync(
        context: Context,
        sourceMode: String?,
        callback: OpenMeteoWeatherRepository.Callback?
    ) {
        val appContext = context.applicationContext
        if (!isLiveWeatherMode(appContext)) {
            Log.d(TAG, "Refresh skipped in Fixed Scene mode")
            notify(callback, OpenMeteoWeatherRepository.readFromCache(appContext))
            return
        }
        if (sourceMode != SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE) {
            if (!LocationWeatherConsent.isTransferAllowed(appContext)) {
                notify(callback, OpenMeteoWeatherRepository.readFromCache(appContext))
                return
            }
            OpenMeteoWeatherRepository.refreshAsync(appContext, callback)
            return
        }

        Thread({
            val samsung = SamsungWeatherRepository.fetchLatest(appContext)
            if (LocationWeatherConsent.isTransferAllowed(appContext)) {
                OpenMeteoWeatherRepository.refreshAsync(appContext) { remote ->
                    finishSamsungRefresh(appContext, samsung, remote, callback)
                }
            } else {
                finishSamsungRefresh(
                    appContext,
                    samsung,
                    OpenMeteoWeatherRepository.readFromCache(appContext),
                    callback
                )
            }
        }, "ww-samsung-refresh").start()
    }

    fun refreshSamsungOnlyAsync(context: Context, callback: OpenMeteoWeatherRepository.Callback?) {
        val appContext = context.applicationContext
        if (!isLiveWeatherMode(appContext)) {
            Log.d(TAG, "Samsung-only refresh skipped in Fixed Scene mode")
            notify(callback, OpenMeteoWeatherRepository.readFromCache(appContext))
            return
        }
        Thread({
            finishSamsungRefresh(
                appContext,
                SamsungWeatherRepository.fetchLatest(appContext),
                OpenMeteoWeatherRepository.readFromCache(appContext),
                callback
            )
        }, "ww-samsung-only-refresh").start()
    }

    fun readFromCache(context: Context): WeatherSnapshot =
        OpenMeteoWeatherRepository.readFromCache(context) ?: WeatherSnapshot.empty()

    fun isCacheStale(context: Context, maxAgeMs: Long): Boolean =
        OpenMeteoWeatherRepository.isCacheStale(context, maxAgeMs)

    fun isSamsungLikelyAvailable(context: Context?): Boolean =
        SamsungWeatherRepository.isLikelySupported(context)

    fun hasSamsungWeatherPermission(context: Context?): Boolean =
        SamsungWeatherRepository.hasReadDangerousProviderPermission(context)

    private fun isLiveWeatherMode(context: Context): Boolean =
        WallpaperModePreferences.isLiveWeather(
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        )

    private fun finishSamsungRefresh(
        context: Context,
        samsung: SamsungWeatherRepository.SamsungSnapshot?,
        baseline: WeatherSnapshot?,
        callback: OpenMeteoWeatherRepository.Callback?
    ) {
        val result = if (samsung == null || !samsung.hasAnyData()) {
            baseline?.let { copyWithSource(it, WeatherSnapshot.SOURCE_OPEN_METEO_FALLBACK) }
                ?: WeatherSnapshot.empty()
        } else {
            mergeSamsungWithFallback(samsung, baseline)
        }
        OpenMeteoWeatherRepository.writeToCache(context, result)
        Log.d(TAG, "Resolved local Samsung weather source=${result.codeSource}")
        notify(callback, result)
    }

    private fun notify(
        callback: OpenMeteoWeatherRepository.Callback?,
        snapshot: WeatherSnapshot?
    ) {
        if (callback != null) mainHandler.post { callback.onWeatherUpdated(snapshot) }
    }

    private fun mergeSamsungWithFallback(
        samsung: SamsungWeatherRepository.SamsungSnapshot,
        baseline: WeatherSnapshot?
    ): WeatherSnapshot {
        val safe = baseline ?: WeatherSnapshot.empty()
        val code = samsung.weatherCode ?: safe.weatherCode
        val source = if (samsung.isCompleteForOverride) {
            WeatherSnapshot.SOURCE_SAMSUNG
        } else {
            WeatherSnapshot.SOURCE_SAMSUNG_WITH_OPEN_METEO_FALLBACK
        }
        return WeatherSnapshot(
            if (code == WeatherSnapshot.UNKNOWN_WEATHER_CODE) 2 else code,
            samsung.currentTempC ?: safe.currentTempC,
            samsung.highTempC ?: safe.highTempC,
            samsung.lowTempC ?: safe.lowTempC,
            samsung.humidityPercent ?: safe.humidityPercent,
            samsung.windSpeedKmh ?: safe.windSpeedKmh,
            samsung.sunriseTime ?: safe.sunriseTime,
            samsung.sunsetTime ?: safe.sunsetTime,
            safe.moonPhase,
            samsung.cityName?.takeIf { it.isNotEmpty() } ?: safe.cityName,
            samsung.lastUpdatedMs.takeIf { it > 0 } ?: safe.lastUpdatedMs.takeIf { it > 0 }
                ?: System.currentTimeMillis(),
            source
        )
    }

    private fun copyWithSource(snapshot: WeatherSnapshot, source: String): WeatherSnapshot =
        WeatherSnapshot(
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
            snapshot.lastUpdatedMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
            source
        )
}
