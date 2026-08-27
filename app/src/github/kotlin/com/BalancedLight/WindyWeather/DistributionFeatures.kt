package com.BalancedLight.WindyWeather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal object DistributionFeatures {
    internal const val remoteMoonServiceAvailable = true
    internal const val externalWeatherSyncAvailable = true

    private const val PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH =
        "pref_sync_with_aeroweather_refresh"
    private const val ACTION_SET_AEROWEATHER_REFRESH_SYNC =
        "com.BalancedLight.WindyWeather.action.SET_AEROWEATHER_REFRESH_SYNC"
    private const val ACTION_AEROWEATHER_SYNC_REFRESH =
        "com.BalancedLight.AeroWeather.action.WEATHER_REFRESHED"
    private const val EXTRA_SYNC_ENABLED = "extra_aeroweather_refresh_sync_enabled"
    private const val EXTRA_SYNC_SOURCE_PACKAGE = "extra_aeroweather_sync_source_package"
    private const val TRUSTED_AEROWEATHER_PACKAGE = "com.BalancedLight.AeroWeather"

    fun resolveMoonPhase(
        latitude: Double,
        longitude: Double,
        fallback: WeatherSnapshot?
    ): Int {
        return try {
            val date = todayYYYYMMDD()
            val timezoneOffset = currentUtcOffsetHours()
            val moonInfo = UsnoMoonClient().fetchMoonInfo(
                date,
                latitude,
                longitude,
                timezoneOffset
            )
            MoonPhaseMapper.toLegacyIndex(moonInfo.phase, moonInfo.fracIllum)
        } catch (error: Exception) {
            Log.w("GithubDistribution", "USNO moon fallback to local calculation", error)
            MoonPhaseCalculator.calculateCurrentPhase()
        }
    }

    fun bindSettings(
        activity: SecretWallpaperSetting,
        prefs: SharedPreferences,
        samsungEligible: Boolean,
        hooks: DistributionSettingsHooks
    ) {
        val syncSwitch = activity.findViewById<MaterialSwitch>(
            R.id.switch_sync_aeroweather_refresh
        ) ?: run {
            Log.e("GithubDistribution", "AeroWeather sync switch is missing from settings layout")
            return
        }
        syncSwitch.visibility = if (samsungEligible) View.VISIBLE else View.GONE
        if (!samsungEligible) {
            prefs.edit().putBoolean(PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH, false).apply()
            return
        }
        syncSwitch.isChecked = isAeroWeatherSyncEnabled(prefs)
        syncSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH, isChecked).apply()
            notifySyncSettingChanged(activity, isChecked)
            val toastRes = if (isChecked && !hooks.hasSamsungWeatherPermission()) {
                hooks.requestSamsungWeatherPermission()
                R.string.settings_weather_permission_requesting
            } else if (isChecked) {
                R.string.settings_sync_aeroweather_refresh_on
            } else {
                R.string.settings_sync_aeroweather_refresh_off
            }
            Toast.makeText(activity, toastRes, Toast.LENGTH_SHORT).show()
            hooks.refreshStatus()
        }
    }

    fun onSamsungPermissionResult(
        context: Context,
        prefs: SharedPreferences,
        granted: Boolean
    ) {
        notifySyncSettingChanged(context, granted && isAeroWeatherSyncEnabled(prefs))
    }

    fun appendDebug(context: Context, prefs: SharedPreferences, debug: StringBuilder) {
        debug.append("AeroWeather sync: ")
            .append(isAeroWeatherSyncEnabled(prefs))
            .append('\n')
    }

    fun createServiceIntegration(
        context: Context,
        requestRefresh: DistributionRefreshRequest
    ): DistributionServiceIntegration {
        return GithubDistributionServiceIntegration(context, requestRefresh)
    }

    internal fun isAeroWeatherSyncEnabled(context: Context): Boolean {
        return isAeroWeatherSyncEnabled(
            context.getSharedPreferences("com.BalancedLight.WindyWeather", Context.MODE_PRIVATE)
        )
    }

    private fun isAeroWeatherSyncEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(PREF_KEY_SYNC_WITH_AEROWEATHER_REFRESH, false)
    }

    private fun notifySyncSettingChanged(context: Context, enabled: Boolean) {
        val intent = Intent(ACTION_SET_AEROWEATHER_REFRESH_SYNC)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SYNC_ENABLED, enabled)
        context.sendBroadcast(intent)
    }

    private fun todayYYYYMMDD(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun currentUtcOffsetHours(): String {
        val zone = TimeZone.getDefault()
        val offsetHours = zone.getOffset(System.currentTimeMillis()) / 3600000.0
        if (Math.abs(offsetHours - Math.rint(offsetHours)) < 0.000001) {
            return String.format(Locale.US, "%.0f", offsetHours)
        }
        if (Math.abs((offsetHours * 2.0) - Math.rint(offsetHours * 2.0)) < 0.000001) {
            return String.format(Locale.US, "%.1f", offsetHours)
        }
        return String.format(Locale.US, "%.2f", offsetHours)
    }

    private class GithubDistributionServiceIntegration(
        context: Context,
        private val requestRefresh: DistributionRefreshRequest
    ) : DistributionServiceIntegration {
        private val appContext = context.applicationContext
        private var started = false
        private var samsungBridge: SamsungWeatherSyncBridge? = null

        private val internalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == ACTION_SET_AEROWEATHER_REFRESH_SYNC) {
                    samsungBridge?.onSyncSettingChanged()
                }
            }
        }

        private val externalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val safeIntent = intent ?: return
                if (safeIntent.action != ACTION_AEROWEATHER_SYNC_REFRESH) {
                    return
                }
                val declaredSource = safeIntent.getStringExtra(EXTRA_SYNC_SOURCE_PACKAGE)
                if (!declaredSource.isNullOrEmpty() && declaredSource != TRUSTED_AEROWEATHER_PACKAGE) {
                    Log.d("GithubDistribution", "Ignoring external sync from $declaredSource")
                    return
                }
                if (!isAeroWeatherSyncEnabled(appContext)) {
                    return
                }
                requestRefresh(SecretWallpaperService.ORIGIN_EXTERNAL_SYNC)
            }
        }

        override fun start() {
            if (started || !SamsungWeatherRepository.isLikelySupported(appContext)) {
                return
            }
            started = true
            ContextCompat.registerReceiver(
                appContext,
                internalReceiver,
                IntentFilter(ACTION_SET_AEROWEATHER_REFRESH_SYNC),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                appContext,
                externalReceiver,
                IntentFilter(ACTION_AEROWEATHER_SYNC_REFRESH),
                ContextCompat.RECEIVER_EXPORTED
            )
            samsungBridge = SamsungWeatherSyncBridge(appContext, {
                isAeroWeatherSyncEnabled(appContext)
            }, {
                requestRefresh(SecretWallpaperService.ORIGIN_SAMSUNG_OBSERVER)
            }).also { it.start() }
        }

        override fun stop() {
            if (!started) {
                return
            }
            started = false
            samsungBridge?.stop()
            samsungBridge = null
            try {
                appContext.unregisterReceiver(internalReceiver)
            } catch (_: Exception) {
            }
            try {
                appContext.unregisterReceiver(externalReceiver)
            } catch (_: Exception) {
            }
        }

        override fun onWeatherSourceChanged(sourceMode: String?) {
            samsungBridge?.onModeChanged(sourceMode)
        }
    }
}
