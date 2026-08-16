package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPaletteTest {
    private fun blend(
        family: SkyPalette.Family,
        skyPosition: Float,
        variation: SkyPalette.DailyVariation = SkyPalette.DEFAULT_DAILY_VARIATION,
        variationStrength: Float = SkyPalette.FULL_VARIATION_STRENGTH
    ): FloatArray {
        val out = FloatArray(SkyPalette.COMPONENT_COUNT)
        SkyPalette.blendInto(family, skyPosition, variation, variationStrength, out)
        return out
    }

    private fun tangentsFor(stops: FloatArray): FloatArray {
        val out = FloatArray(SkyPalette.COMPONENT_COUNT)
        SkyPalette.computeTangents(stops, out)
        return out
    }

    private fun assertHex(expected: Int, linearStops: FloatArray, stop: Int) {
        val base = stop * 3
        assertEquals(
            "red",
            (expected shr 16) and 0xFF,
            Math.round(SkyPalette.linearToSrgb(linearStops[base]) * 255.0f)
        )
        assertEquals(
            "green",
            (expected shr 8) and 0xFF,
            Math.round(SkyPalette.linearToSrgb(linearStops[base + 1]) * 255.0f)
        )
        assertEquals(
            "blue",
            expected and 0xFF,
            Math.round(SkyPalette.linearToSrgb(linearStops[base + 2]) * 255.0f)
        )
    }

    @Test
    fun `keyframe positions reproduce the sampled sky colours`() {
        val horizon = SkyPalette.STOP_COUNT - 1
        val clearDay = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DAY)
        assertHex(0x2A70B6, clearDay, 0)
        assertHex(0xF5FDFF, clearDay, horizon)

        val overcastDay = blend(SkyPalette.Family.OVERCAST, TwilightTimeline.SKY_DAY)
        assertHex(0x213849, overcastDay, 0)
        assertHex(0x97A7A8, overcastDay, horizon)

        val clearNight = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_NIGHT)
        assertHex(0x010729, clearNight, 0)
        assertHex(0x68C9F4, clearNight, horizon)

        val overcastNight = blend(SkyPalette.Family.OVERCAST, TwilightTimeline.SKY_NIGHT)
        assertHex(0x0C242D, overcastNight, 0)
    }

    @Test
    fun `the cycle closes on itself`() {
        val start = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_NIGHT)
        val end = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_CYCLE)

        for (i in 0 until SkyPalette.COMPONENT_COUNT) {
            assertEquals(start[i], end[i], 0.0001f)
        }
    }

    @Test
    fun `daily variation is deterministic with independent dawn and dusk choices`() {
        val first = SkyPalette.dailyVariation(2026, 123)
        val again = SkyPalette.dailyVariation(2026, 123)

        assertEquals(first, again)

        val year = (1..366).map { SkyPalette.dailyVariation(2026, it) }
        assertTrue(year.map { it.dawn }.toSet().size > 1)
        assertTrue(year.map { it.dusk }.toSet().size > 1)
        assertTrue(year.map { it.cacheKey }.toSet().size > 4)
    }

    @Test
    fun `daily variations leave night and day endpoints unchanged`() {
        val variation = SkyPalette.DailyVariation(
            SkyPalette.DawnProfile.ROSE_PINK,
            SkyPalette.DuskProfile.ORANGE
        )

        for (family in SkyPalette.Family.values()) {
            for (position in floatArrayOf(
                TwilightTimeline.SKY_NIGHT,
                TwilightTimeline.SKY_DAY,
                TwilightTimeline.SKY_CYCLE
            )) {
                val baseline = blend(family, position)
                val varied = blend(family, position, variation)
                for (i in 0 until SkyPalette.COMPONENT_COUNT) {
                    assertEquals("$family position $position component $i", baseline[i], varied[i], 0.0001f)
                }
            }
        }
    }

    @Test
    fun `overcast variation keeps thirty percent of the clear profile delta`() {
        val variation = SkyPalette.DailyVariation(
            SkyPalette.DawnProfile.ROSE_PINK,
            SkyPalette.DuskProfile.ORANGE
        )
        val clearBase = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DAWN)
        val clearFull = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DAWN, variation)
        val overcastBase = blend(SkyPalette.Family.OVERCAST, TwilightTimeline.SKY_DAWN)
        val overcastMuted = blend(
            SkyPalette.Family.OVERCAST,
            TwilightTimeline.SKY_DAWN,
            variation,
            SkyPalette.MUTED_VARIATION_STRENGTH
        )
        var foundVisibleClearChange = false

        for (i in 0 until SkyPalette.COMPONENT_COUNT) {
            val clearDelta = clearFull[i] - clearBase[i]
            val mutedDelta = overcastMuted[i] - overcastBase[i]
            assertEquals(
                "component $i",
                clearDelta * SkyPalette.MUTED_VARIATION_STRENGTH,
                mutedDelta,
                0.0001f
            )
            if (kotlin.math.abs(clearDelta) > 0.005f) {
                foundVisibleClearChange = true
            }
        }
        assertTrue("expected a distinct clear-sky profile", foundVisibleClearChange)
    }

    @Test
    fun `foreground tints match clear skies and keep rain scenes cool`() {
        val variation = SkyPalette.DailyVariation(
            SkyPalette.DawnProfile.ROSE_PINK,
            SkyPalette.DuskProfile.ORANGE
        )
        val full = SkyPalette.foregroundTint(
            variation,
            TwilightTimeline.SKY_DUSK,
            1.0f,
            SkyPalette.ForegroundTreatment.FULL
        )
        val muted = SkyPalette.foregroundTint(
            variation,
            TwilightTimeline.SKY_DUSK,
            1.0f,
            SkyPalette.ForegroundTreatment.MUTED_MATCHED
        )
        val cool = SkyPalette.foregroundTint(
            variation,
            TwilightTimeline.SKY_DUSK,
            1.0f,
            SkyPalette.ForegroundTreatment.COOL_NEUTRAL
        )

        assertTrue(full.red > full.blue)
        assertTrue(muted.green > full.green)
        assertTrue(muted.blue > full.blue)
        assertTrue(cool.red <= cool.green)
        assertTrue(cool.green <= cool.blue)

        for (dawn in SkyPalette.DawnProfile.values()) {
            val dawnCool = SkyPalette.foregroundTint(
                SkyPalette.DailyVariation(dawn, SkyPalette.DuskProfile.AMBER),
                TwilightTimeline.SKY_DAWN,
                1.0f,
                SkyPalette.ForegroundTreatment.COOL_NEUTRAL
            )
            assertTrue("$dawn red", dawnCool.red <= dawnCool.green)
            assertTrue("$dawn green", dawnCool.green <= dawnCool.blue)
        }
        for (dusk in SkyPalette.DuskProfile.values()) {
            val duskCool = SkyPalette.foregroundTint(
                SkyPalette.DailyVariation(SkyPalette.DawnProfile.PEACH, dusk),
                TwilightTimeline.SKY_DUSK,
                1.0f,
                SkyPalette.ForegroundTreatment.COOL_NEUTRAL
            )
            assertTrue("$dusk red", duskCool.red <= duskCool.green)
            assertTrue("$dusk green", duskCool.green <= duskCool.blue)
        }
    }

    @Test
    fun `weather scene treatments keep rain family cool and cloudy vivid`() {
        val cloudy = SecretWallpaperService.twilightAppearanceForScene(
            SecretWallpaperService.WeatherConditions.D2_CLOUDY.ordinal
        )
        assertEquals(SkyPalette.FULL_VARIATION_STRENGTH, cloudy.variationStrength, 0.0f)
        assertEquals(SkyPalette.ForegroundTreatment.FULL, cloudy.foregroundTreatment)

        for (scene in intArrayOf(
            SecretWallpaperService.WeatherConditions.D3_DREARY.ordinal,
            SecretWallpaperService.WeatherConditions.D5_RAIN_SHOWERS.ordinal,
            SecretWallpaperService.WeatherConditions.D6_THUNDERSTORMS.ordinal,
            SecretWallpaperService.WeatherConditions.D9_SLEET.ordinal
        )) {
            val treatment = SecretWallpaperService.twilightAppearanceForScene(scene)
            assertEquals(SkyPalette.MUTED_VARIATION_STRENGTH, treatment.variationStrength, 0.0f)
            assertEquals(SkyPalette.ForegroundTreatment.COOL_NEUTRAL, treatment.foregroundTreatment)
        }
    }

    @Test
    fun `crossfades without stepping outside the two keyframes`() {
        val night = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_NIGHT)
        val dawn = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DAWN)
        val middle = blend(SkyPalette.Family.CLEAR, 0.5f)

        for (i in 0 until SkyPalette.COMPONENT_COUNT) {
            val low = minOf(night[i], dawn[i])
            val high = maxOf(night[i], dawn[i])
            assertTrue("component $i = ${middle[i]} outside [$low, $high]", middle[i] in low..high)
        }
    }

    @Test
    fun `dusk stays warmer at the horizon than the day it follows`() {
        val day = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DAY)
        val dusk = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DUSK)
        val horizon = (SkyPalette.STOP_COUNT - 1) * 3

        // Red dominates blue at dusk; a daytime sky is the other way round.
        assertTrue(dusk[horizon] > dusk[horizon + 2])
        assertTrue(day[horizon + 2] >= day[horizon])
    }

    @Test
    fun `overcast twilight is less colourful than clear twilight`() {
        // Compared at the v = 0.75 stop, which sits inside the visible band and carries most of
        // the twilight colour.  Deliberately not compared at the day keyframe: clear day fades to
        // a near-white haze at the horizon, which is legitimately less saturated than overcast grey.
        val stop = 12 * 3
        for (position in floatArrayOf(TwilightTimeline.SKY_DAWN, TwilightTimeline.SKY_DUSK)) {
            val clear = blend(SkyPalette.Family.CLEAR, position)
            val overcast = blend(SkyPalette.Family.OVERCAST, position)
            val clearSpread = maxOf(clear[stop], clear[stop + 1], clear[stop + 2]) -
                minOf(clear[stop], clear[stop + 1], clear[stop + 2])
            val overcastSpread = maxOf(overcast[stop], overcast[stop + 1], overcast[stop + 2]) -
                minOf(overcast[stop], overcast[stop + 1], overcast[stop + 2])
            assertTrue("position $position", overcastSpread < clearSpread)
        }
    }

    @Test
    fun `sampling lands exactly on the stops at stop positions`() {
        val stops = blend(SkyPalette.Family.CLEAR, TwilightTimeline.SKY_DUSK)
        val tangents = tangentsFor(stops)
        val rgb = FloatArray(3)

        for (stop in 0 until SkyPalette.STOP_COUNT) {
            SkyPalette.sampleInto(stops, tangents, stop / (SkyPalette.STOP_COUNT - 1.0f), rgb)
            for (channel in 0 until 3) {
                assertEquals("stop $stop channel $channel", stops[(stop * 3) + channel], rgb[channel], 0.0001f)
            }
        }
    }

    @Test
    fun `monotone cubic never overshoots the enclosing segment`() {
        val families = SkyPalette.Family.values()
        val variations = SkyPalette.DawnProfile.values().flatMap { dawn ->
            SkyPalette.DuskProfile.values().map { dusk -> SkyPalette.DailyVariation(dawn, dusk) }
        }
        val rgb = FloatArray(3)
        for (family in families) {
            for (variation in variations) {
                for (keyframe in 0..3) {
                    val stops = blend(family, keyframe.toFloat(), variation)
                    val tangents = tangentsFor(stops)
                    for (step in 0..2048) {
                        val v = step / 2048.0f
                        SkyPalette.sampleInto(stops, tangents, v, rgb)
                        val scaled = (v * (SkyPalette.STOP_COUNT - 1)).toInt()
                            .coerceIn(0, SkyPalette.STOP_COUNT - 2)
                        for (channel in 0 until 3) {
                            val low = stops[(scaled * 3) + channel]
                            val high = stops[((scaled + 1) * 3) + channel]
                            val lower = minOf(low, high) - 0.0001f
                            val upper = maxOf(low, high) + 0.0001f
                            assertTrue(
                                "$family $variation key $keyframe v=$v channel $channel -> ${rgb[channel]} outside [$lower, $upper]",
                                rgb[channel] in lower..upper
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `srgb conversion round trips`() {
        for (step in 0..255) {
            val srgb = step / 255.0f
            val roundTripped = SkyPalette.linearToSrgb(SkyPalette.srgbToLinear(srgb))
            assertEquals(srgb, roundTripped, 0.0005f)
        }
    }

    @Test
    fun `positions outside the cycle are clamped rather than throwing`() {
        val below = blend(SkyPalette.Family.CLEAR, -3.0f)
        val above = blend(SkyPalette.Family.OVERCAST, 99.0f)

        assertEquals(SkyPalette.COMPONENT_COUNT, below.size)
        assertEquals(SkyPalette.COMPONENT_COUNT, above.size)
    }
}
