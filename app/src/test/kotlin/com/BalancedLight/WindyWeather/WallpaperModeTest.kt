package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperModeTest {
    @Test
    fun `unknown preference values use safe defaults`() {
        assertEquals(WallpaperMode.LIVE_WEATHER, WallpaperMode.fromPreference(null))
        assertEquals(WallpaperMode.LIVE_WEATHER, WallpaperMode.fromPreference("unknown"))
        assertEquals(FixedLighting.DAY, FixedLighting.fromPreference(null))
        assertEquals(FixedScenePresets.CLEAR, FixedScenePresets.fromId("unknown"))
    }

    @Test
    fun `fixed presets are complete and deterministic`() {
        assertEquals(10, FixedScenePresets.all.size)
        assertEquals(FixedScenePresets.all.size, FixedScenePresets.all.map { it.id }.toSet().size)
        FixedScenePresets.all.forEach { preset ->
            assertTrue(preset.sceneOrdinal in 1..10)
            assertTrue(preset.weatherCode >= 0)
            assertTrue(preset.humidityPercent in 0..100)
            assertTrue(preset.windSpeedKmh >= 0.0f)
        }
        assertEquals(FixedScenePresets.FREEZING_FOG, FixedScenePresets.fromLegacy(4, 48))
    }

    @Test
    fun `migration without legacy overrides remains live`() {
        val migration = WallpaperModePolicy.migrateLegacy(
            forcedSceneOrdinal = -1,
            forcedWeatherCode = WeatherSnapshot.UNKNOWN_WEATHER_CODE,
            legacyDayNightMode = SecretWallpaperService.DAY_NIGHT_MODE_AUTO,
            lastSceneOrdinal = FixedScenePresets.RAIN.sceneOrdinal,
            effectiveNight = true
        )

        assertEquals(WallpaperMode.LIVE_WEATHER, migration.mode)
        assertEquals(FixedLighting.DAY, migration.lighting)
        assertEquals(FixedScenePresets.CLEAR, migration.preset)
    }

    @Test
    fun `legacy override migrates once into fixed scene policy`() {
        val migration = WallpaperModePolicy.migrateLegacy(
            forcedSceneOrdinal = FixedScenePresets.SNOW.sceneOrdinal,
            forcedWeatherCode = WeatherSnapshot.UNKNOWN_WEATHER_CODE,
            legacyDayNightMode = SecretWallpaperService.DAY_NIGHT_MODE_FORCE_NIGHT,
            lastSceneOrdinal = FixedScenePresets.CLEAR.sceneOrdinal,
            effectiveNight = false
        )

        assertEquals(WallpaperMode.FIXED_SCENE, migration.mode)
        assertEquals(FixedScenePresets.SNOW, migration.preset)
        assertEquals(FixedLighting.NIGHT, migration.lighting)
        assertFalse(migration.mode == WallpaperMode.LIVE_WEATHER)
    }
}
