package com.BalancedLight.WindyWeather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object LocationWeatherConsent {
    private const val PREF_NAME = "weather_privacy"
    private const val KEY_CONSENT_VERSION = "location_weather_consent_version"
    internal const val CURRENT_VERSION = 2

    fun isGranted(context: Context?): Boolean {
        if (context == null) {
            return false
        }
        val storedVersion = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSENT_VERSION, 0)
        return isVersionAccepted(storedVersion)
    }

    /**
     * True when the user accepted an earlier disclosure that has since been superseded,
     * so the settings screen can re-ask instead of silently dropping weather updates.
     */
    fun needsReconsent(context: Context?): Boolean {
        if (context == null) {
            return false
        }
        val storedVersion = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSENT_VERSION, 0)
        return needsReconsent(storedVersion)
    }

    fun hasCoarseLocationPermission(context: Context?): Boolean {
        if (context == null) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isTransferAllowed(context: Context?): Boolean {
        if (context == null) return false
        val storedVersion = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSENT_VERSION, 0)
        return isTransferAllowed(storedVersion, hasCoarseLocationPermission(context))
    }

    fun grant(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONSENT_VERSION, CURRENT_VERSION)
            .apply()
    }

    fun revoke(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CONSENT_VERSION)
            .apply()
    }

    internal fun isVersionAccepted(storedVersion: Int): Boolean {
        return storedVersion == CURRENT_VERSION
    }

    internal fun needsReconsent(storedVersion: Int): Boolean {
        return storedVersion in 1 until CURRENT_VERSION
    }

    internal fun isTransferAllowed(storedVersion: Int, coarsePermissionGranted: Boolean): Boolean {
        return isVersionAccepted(storedVersion) && coarsePermissionGranted
    }

    internal fun versionAfterPermissionResult(permissionGranted: Boolean): Int {
        return if (permissionGranted) CURRENT_VERSION else 0
    }
}
