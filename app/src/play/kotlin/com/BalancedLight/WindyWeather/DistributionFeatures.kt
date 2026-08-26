package com.BalancedLight.WindyWeather

import android.content.Context
import android.content.SharedPreferences

internal object DistributionFeatures {
    internal const val remoteMoonServiceAvailable = false
    internal const val externalWeatherSyncAvailable = false

    fun resolveMoonPhase(
        latitude: Double,
        longitude: Double,
        fallback: WeatherSnapshot?
    ): Int = MoonPhaseCalculator.calculateCurrentPhase()

    fun bindSettings(
        activity: SecretWallpaperSetting,
        prefs: SharedPreferences,
        samsungEligible: Boolean,
        hooks: DistributionSettingsHooks
    ) = Unit

    fun onSamsungPermissionResult(
        context: Context,
        prefs: SharedPreferences,
        granted: Boolean
    ) = Unit

    fun appendDebug(context: Context, prefs: SharedPreferences, debug: StringBuilder) = Unit

    fun createServiceIntegration(
        context: Context,
        requestRefresh: DistributionRefreshRequest
    ): DistributionServiceIntegration = NoOpDistributionServiceIntegration
}
