package com.BalancedLight.WindyWeather

import android.content.SharedPreferences

internal enum class WallpaperMode(val preferenceValue: String) {
    LIVE_WEATHER("live_weather"),
    FIXED_SCENE("fixed_scene");

    companion object {
        fun fromPreference(value: String?): WallpaperMode =
            entries.firstOrNull { it.preferenceValue == value } ?: LIVE_WEATHER
    }
}

internal enum class FixedLighting(val preferenceValue: String) {
    DAY("day"),
    NIGHT("night");

    companion object {
        fun fromPreference(value: String?): FixedLighting =
            entries.firstOrNull { it.preferenceValue == value } ?: DAY
    }
}

internal data class FixedScenePreset(
    val id: String,
    val sceneOrdinal: Int,
    val weatherCode: Int,
    val currentTempC: Int,
    val humidityPercent: Int,
    val windSpeedKmh: Float
)

internal object FixedScenePresets {
    val CLEAR = FixedScenePreset("clear", 1, 0, 20, 50, 10.0f)
    val MOSTLY_CLEAR = FixedScenePreset("mostly_clear", 10, 1, 19, 55, 12.0f)
    val CLOUDY = FixedScenePreset("cloudy", 2, 2, 16, 70, 14.0f)
    val DREARY = FixedScenePreset("dreary", 3, 3, 12, 85, 16.0f)
    val FOG = FixedScenePreset("fog", 4, 45, 8, 95, 5.0f)
    val FREEZING_FOG = FixedScenePreset("freezing_fog", 4, 48, -3, 95, 5.0f)
    val RAIN = FixedScenePreset("rain", 5, 61, 12, 95, 20.0f)
    val THUNDER = FixedScenePreset("thunder", 6, 95, 20, 95, 35.0f)
    val SNOW = FixedScenePreset("snow", 7, 71, -5, 90, 15.0f)
    val SLEET = FixedScenePreset("sleet", 9, 66, -2, 95, 20.0f)

    val all: List<FixedScenePreset> = listOf(
        CLEAR,
        MOSTLY_CLEAR,
        CLOUDY,
        DREARY,
        FOG,
        FREEZING_FOG,
        RAIN,
        THUNDER,
        SNOW,
        SLEET
    )

    fun fromId(id: String?): FixedScenePreset = all.firstOrNull { it.id == id } ?: CLEAR

    fun fromLegacy(sceneOrdinal: Int, weatherCode: Int): FixedScenePreset {
        if (weatherCode == 48) return FREEZING_FOG
        return all.firstOrNull { it.sceneOrdinal == sceneOrdinal } ?: CLEAR
    }
}

internal data class WallpaperModeMigration(
    val mode: WallpaperMode,
    val lighting: FixedLighting,
    val preset: FixedScenePreset
)

internal object WallpaperModePolicy {
    const val MIGRATION_VERSION = 1

    fun migrateLegacy(
        forcedSceneOrdinal: Int,
        forcedWeatherCode: Int,
        legacyDayNightMode: Int,
        lastSceneOrdinal: Int,
        effectiveNight: Boolean
    ): WallpaperModeMigration {
        val hasForcedScene = forcedSceneOrdinal in 1..10 || forcedWeatherCode >= 0
        val hasForcedLighting = legacyDayNightMode == 1 || legacyDayNightMode == 2
        if (!hasForcedScene && !hasForcedLighting) {
            return WallpaperModeMigration(WallpaperMode.LIVE_WEATHER, FixedLighting.DAY, FixedScenePresets.CLEAR)
        }
        val scene = if (forcedSceneOrdinal in 1..10) forcedSceneOrdinal else lastSceneOrdinal
        val preset = FixedScenePresets.fromLegacy(scene, forcedWeatherCode)
        val lighting = when (legacyDayNightMode) {
            1 -> FixedLighting.DAY
            2 -> FixedLighting.NIGHT
            else -> if (effectiveNight) FixedLighting.NIGHT else FixedLighting.DAY
        }
        return WallpaperModeMigration(WallpaperMode.FIXED_SCENE, lighting, preset)
    }
}

internal object WallpaperModePreferences {
    const val KEY_MODE = "pref_wallpaper_mode"
    const val KEY_FIXED_LIGHTING = "pref_fixed_lighting"
    const val KEY_FIXED_SCENE = "pref_fixed_scene"
    const val KEY_MIGRATION_VERSION = "pref_wallpaper_mode_migration_version"

    fun mode(prefs: SharedPreferences?): WallpaperMode = WallpaperMode.fromPreference(
        prefs?.getString(KEY_MODE, WallpaperMode.LIVE_WEATHER.preferenceValue)
    )

    fun lighting(prefs: SharedPreferences?): FixedLighting = FixedLighting.fromPreference(
        prefs?.getString(KEY_FIXED_LIGHTING, FixedLighting.DAY.preferenceValue)
    )

    fun preset(prefs: SharedPreferences?): FixedScenePreset = FixedScenePresets.fromId(
        prefs?.getString(KEY_FIXED_SCENE, FixedScenePresets.CLEAR.id)
    )

    fun isLiveWeather(prefs: SharedPreferences?): Boolean = mode(prefs) == WallpaperMode.LIVE_WEATHER

    fun setMode(prefs: SharedPreferences, mode: WallpaperMode) {
        prefs.edit().putString(KEY_MODE, mode.preferenceValue).apply()
    }

    fun setFixedLighting(prefs: SharedPreferences, lighting: FixedLighting) {
        prefs.edit().putString(KEY_FIXED_LIGHTING, lighting.preferenceValue).apply()
    }

    fun setFixedScene(prefs: SharedPreferences, preset: FixedScenePreset) {
        prefs.edit().putString(KEY_FIXED_SCENE, preset.id).apply()
    }
}
