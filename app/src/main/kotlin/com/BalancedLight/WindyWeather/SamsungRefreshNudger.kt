package com.BalancedLight.WindyWeather

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

internal object SamsungRefreshNudger {
    private val TAG = "SamsungRefreshNudger"
    private val NUDGE_COOLDOWN_MS = 30L * 60L * 1000L
    private val TARGET_PACKAGES = arrayOf<String?>(
        "com.samsung.android.weather",
        "com.sec.android.daemonapp",
        "com.sec.android.daemonapp.ap",
        "com.sec.android.widgetapp.ap.accuweatherdaemon"
    )
    private val ACTION_CANDIDATES = arrayOf<String?>(
        "com.sec.android.widgetapp.ap.accuweatherdaemon.action.MANUAL_REFRESH",
        "com.sec.android.widgetapp.ap.accuweatherdaemon.action.B_MANUALREFRESH",
        "com.sec.android.widgetapp.ap.accuweatherdaemon.action.ACTION_REQUEST_WEATHER_DATA_TO_DAEMON",
        "com.samsung.android.weather.action.REFRESH",
        "com.samsung.android.weather.action.MANUAL_REFRESH"
    )

    fun tryNudge(context: Context?, origin: String?) {
        if (context == null || !SamsungWeatherRepository.isSamsungDevice()) {
            return
        }
        val appContext: Context = context.getApplicationContext()
        if (!SecretWallpaperService.WEATHER_SOURCE_SAMSUNG_DEVICE.equals(
                com.BalancedLight.WindyWeather.SamsungRefreshNudger.getWeatherSourceMode(
                    appContext
                )
            )
        ) {
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "mode_open_meteo",
                origin,
                0,
                0L
            )
            return
        }

        val now: Long = System.currentTimeMillis()
        val suppressUntil: Long = SecretWallpaperService.getSamsungNudgeSuppressUntilMs(appContext)
        if (suppressUntil > now) {
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "observer_origin_suppressed",
                origin,
                0,
                suppressUntil - now
            )
            return
        }

        val lastNudge: Long = SecretWallpaperService.getLastSamsungNudgeMs(appContext)
        if (lastNudge > 0L && now - lastNudge < com.BalancedLight.WindyWeather.SamsungRefreshNudger.NUDGE_COOLDOWN_MS) {
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "cooldown",
                origin,
                0,
                com.BalancedLight.WindyWeather.SamsungRefreshNudger.NUDGE_COOLDOWN_MS - (now - lastNudge)
            )
            return
        }

        val packageManager: PackageManager? = appContext.getPackageManager()
        if (packageManager == null) {
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "no_package_manager",
                origin,
                0,
                0L
            )
            return
        }

        var sentCount = 0
        var securityBlocked = false
        for (action in com.BalancedLight.WindyWeather.SamsungRefreshNudger.ACTION_CANDIDATES) {
            for (targetPackage in com.BalancedLight.WindyWeather.SamsungRefreshNudger.TARGET_PACKAGES) {
                val probe: Intent = Intent(action)
                probe.setPackage(targetPackage)
                val receivers: List<ResolveInfo?>?
                try {
                    receivers = packageManager.queryBroadcastReceivers(probe, 0)
                } catch (e: Exception) {
                    continue
                }
                if (receivers == null || receivers.isEmpty()) {
                    continue
                }
                for (receiver in receivers) {
                    val info: ActivityInfo? = receiver.activityInfo
                    if (!com.BalancedLight.WindyWeather.SamsungRefreshNudger.isSafeTarget(info)) {
                        continue
                    }
                    val safeInfo = info ?: continue
                    val nudgeIntent: Intent = Intent(action)
                    nudgeIntent.setClassName(safeInfo.packageName, safeInfo.name)
                    nudgeIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    try {
                        appContext.sendBroadcast(nudgeIntent)
                        sentCount++
                    } catch (e2: SecurityException) {
                        securityBlocked = true
                    } catch (e3: Exception) {
                    }
                }
            }
        }

        if (sentCount > 0) {
            SecretWallpaperService.setLastSamsungNudgeMs(appContext, now)
            SecretWallpaperService.setLastSyncOrigin(
                appContext,
                SecretWallpaperService.ORIGIN_APP_REFRESH
            )
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "sent",
                origin,
                sentCount,
                0L
            )
            return
        }
        if (securityBlocked) {
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
                "security_blocked",
                origin,
                0,
                0L
            )
            return
        }
        com.BalancedLight.WindyWeather.SamsungRefreshNudger.logResult(
            "no_exported_target",
            origin,
            0,
            0L
        )
    }

    private fun isSafeTarget(info: ActivityInfo?): Boolean {
        if (info == null || !info.exported) {
            return false
        }
        return info.permission == null || info.permission.length == 0
    }

    private fun getWeatherSourceMode(context: Context): String {
        return context.getSharedPreferences("com.BalancedLight.WindyWeather", Context.MODE_PRIVATE)
            .getString(
                SecretWallpaperService.PREF_KEY_WEATHER_SOURCE_MODE,
                SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
            ) ?: SecretWallpaperService.WEATHER_SOURCE_OPEN_METEO
    }

    private fun logResult(result: String?, origin: String?, sentCount: Int, remainingMs: Long) {
        Log.d(
            com.BalancedLight.WindyWeather.SamsungRefreshNudger.TAG,
            "result=" + result + " origin=" + origin + " sent=" + sentCount + " remainingMs=" + remainingMs
        )
    }
}

