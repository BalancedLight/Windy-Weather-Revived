package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwilightTimelineTest {
    @Test
    fun `centres non-hour aligned twilight bands on sunrise and sunset`() {
        val sunrise = 630
        val sunset = 1845

        assertEquals(TwilightTimeline.SkyPhase.NIGHT, TwilightTimeline.resolve(344, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.MORNING, TwilightTimeline.resolve(345, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.MORNING, TwilightTimeline.resolve(390, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.MORNING, TwilightTimeline.resolve(435, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.DAY, TwilightTimeline.resolve(436, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.DAY, TwilightTimeline.resolve(1079, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.SUNSET, TwilightTimeline.resolve(1080, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.SUNSET, TwilightTimeline.resolve(1170, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.NIGHT, TwilightTimeline.resolve(1171, sunrise, sunset).phase)
    }

    @Test
    fun `sky position peaks exactly on sunrise and sunset`() {
        val sunrise = 600
        val sunset = 1800

        assertEquals(
            TwilightTimeline.SKY_NIGHT,
            TwilightTimeline.resolve(315, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_DAWN,
            TwilightTimeline.resolve(360, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_DAY,
            TwilightTimeline.resolve(405, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_DAY,
            TwilightTimeline.resolve(720, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_DUSK,
            TwilightTimeline.resolve(1080, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_CYCLE,
            TwilightTimeline.resolve(1125, sunrise, sunset).skyPosition,
            0.0001f
        )
        assertEquals(
            TwilightTimeline.SKY_NIGHT,
            TwilightTimeline.resolve(1126, sunrise, sunset).skyPosition,
            0.0001f
        )
    }

    @Test
    fun `sky position never jumps across a whole day`() {
        val sunrise = 630
        val sunset = 1845
        var previous = TwilightTimeline.resolve(0, sunrise, sunset).skyPosition

        for (minute in 1..1439) {
            val current = TwilightTimeline.resolve(minute, sunrise, sunset).skyPosition
            // The only legal discontinuity is the 4 -> 0 wrap that closes the cycle.
            val delta = Math.abs(current - previous)
            val wrapped = Math.abs(delta - TwilightTimeline.SKY_CYCLE)
            assertTrue(
                "jump at minute $minute: $previous -> $current",
                delta <= 0.05f || wrapped <= 0.05f
            )
            previous = current
        }
    }

    @Test
    fun `uses a continuous daylight arc throughout the day`() {
        val sunrise = 600
        val sunset = 1800

        val morning = TwilightTimeline.resolve(390, sunrise, sunset)
        val noon = TwilightTimeline.resolve(720, sunrise, sunset)
        val sunsetState = TwilightTimeline.resolve(1050, sunrise, sunset)

        assertTrue(morning.daylightProgress!! < noon.daylightProgress!!)
        assertTrue(noon.daylightProgress!! < sunsetState.daylightProgress!!)
        assertTrue(TwilightTimeline.arcHeight(noon.daylightProgress!!) > 0.99f)
    }

    @Test
    fun `keeps both bodies hidden for the thirty minute horizon gaps`() {
        val sunrise = 600
        val sunset = 1800

        val afterSunset = TwilightTimeline.resolve(18 * 60, sunrise, sunset)
        val moonRise = TwilightTimeline.resolve((18 * 60) + 30, sunrise, sunset)
        val beforeSunriseGap = TwilightTimeline.resolve((5 * 60) + 29, sunrise, sunset)
        val sunriseGap = TwilightTimeline.resolve((5 * 60) + 30, sunrise, sunset)
        val dawn = TwilightTimeline.resolve(6 * 60, sunrise, sunset)

        assertNull(afterSunset.nightProgress)
        assertEquals(0.0f, moonRise.nightProgress!!, 0.0001f)
        assertTrue(beforeSunriseGap.nightProgress!! < 1.0f)
        assertNull(sunriseGap.nightProgress)
        assertEquals(0.0f, dawn.daylightProgress!!, 0.0001f)
    }

    @Test
    fun `tracks the moon between the horizon gaps across midnight`() {
        val beforeMidnight = TwilightTimeline.resolve(23 * 60, 600, 1800)
        val afterMidnight = TwilightTimeline.resolve(60, 600, 1800)
        val beforeSunriseGap = TwilightTimeline.resolve((5 * 60) + 29, 600, 1800)

        assertEquals(TwilightTimeline.SkyPhase.NIGHT, beforeMidnight.phase)
        assertEquals(TwilightTimeline.SkyPhase.NIGHT, afterMidnight.phase)
        assertTrue(beforeMidnight.nightProgress!! < afterMidnight.nightProgress!!)
        assertTrue(afterMidnight.nightProgress!! < beforeSunriseGap.nightProgress!!)
    }

    @Test
    fun `uses the grass horizon and high eased crest for both celestial bodies`() {
        assertEquals(-5.0f, TwilightTimeline.bodyArcY(0.0f), 0.0001f)
        assertEquals(7.0f, TwilightTimeline.bodyArcY(0.5f), 0.0001f)
        assertEquals(-5.0f, TwilightTimeline.bodyArcY(1.0f), 0.0001f)

        val nonHourAlignedMidday = TwilightTimeline.resolve(12 * 60 + 39, 631, 1847)
        assertEquals(0.5f, nonHourAlignedMidday.daylightProgress!!, 0.0001f)
        assertEquals(7.0f, TwilightTimeline.bodyArcY(nonHourAlignedMidday.daylightProgress!!), 0.0001f)
    }

    @Test
    fun `easing keeps one hour after sunrise and before sunset near the horizon`() {
        val oneHourIntoDay = TwilightTimeline.resolve(7 * 60, 600, 1800).daylightProgress!!
        val oneHourBeforeSunset = TwilightTimeline.resolve(17 * 60, 600, 1800).daylightProgress!!

        assertEquals(oneHourIntoDay, 1.0f - oneHourBeforeSunset, 0.0001f)
        assertTrue(TwilightTimeline.bodyArcY(oneHourIntoDay) < -4.0f)
        assertTrue(TwilightTimeline.bodyArcY(oneHourBeforeSunset) < -4.0f)
    }

    @Test
    fun `invalid or too-short daylight data does not enable special sky phases`() {
        val invalid = TwilightTimeline.resolve(420, 660, 1800)
        val shortDay = TwilightTimeline.resolve(420, 600, 715)

        assertEquals(TwilightTimeline.SkyPhase.DAY, invalid.phase)
        assertNull(invalid.daylightProgress)
        assertFalse(invalid.hasValidDaylightData)
        assertEquals(TwilightTimeline.SKY_DAY, invalid.skyPosition, 0.0001f)
        assertEquals(TwilightTimeline.SkyPhase.DAY, shortDay.phase)
        assertNull(shortDay.daylightProgress)
        assertFalse(shortDay.hasValidDaylightData)
        assertFalse(invalid.twilightTint.green < 1.0f)
    }

    @Test
    fun `shrinks the twilight band so dawn and dusk can never overlap`() {
        // A day only just longer than the fallback threshold still has to fit two bands.
        val half = TwilightTimeline.twilightHalfWindow(600, 730)
        assertTrue(half >= 1)
        assertTrue(half * 2 < 730 - 600)

        // A short night has to fit its bands too.
        val shortNightHalf = TwilightTimeline.twilightHalfWindow(60, 1380)
        assertTrue(shortNightHalf >= 1)
        assertTrue(shortNightHalf * 2 < 1440 - (1380 - 60))

        // A normal day gets the full window.
        assertEquals(
            TwilightTimeline.TWILIGHT_HALF_WINDOW_MINUTES,
            TwilightTimeline.twilightHalfWindow(360, 1080)
        )
    }

    @Test
    fun `twilight tint peaks at the sunrise and sunset instant`() {
        val night = TwilightTimeline.resolve(300, 600, 1800).twilightTint
        val atSunrise = TwilightTimeline.resolve(360, 600, 1800).twilightTint
        val midday = TwilightTimeline.resolve(720, 600, 1800).twilightTint
        val atSunset = TwilightTimeline.resolve(1080, 600, 1800).twilightTint

        assertEquals(1.0f, night.blue, 0.0001f)
        assertEquals(1.0f, midday.blue, 0.0001f)
        assertEquals(0.70f, atSunrise.blue, 0.0001f)
        assertEquals(0.70f, atSunset.blue, 0.0001f)
        assertEquals(1.0f, atSunset.red, 0.0001f)
        assertTrue(atSunset.green < 1.0f)
    }

    @Test
    fun `stars linger through the sunrise and sunset instant`() {
        val deepNight = TwilightTimeline.resolve(300, 600, 1800).twilightStarsAlpha
        val atSunrise = TwilightTimeline.resolve(360, 600, 1800).twilightStarsAlpha
        val afterDawnBand = TwilightTimeline.resolve(405, 600, 1800).twilightStarsAlpha
        val beforeDuskBand = TwilightTimeline.resolve(1035, 600, 1800).twilightStarsAlpha
        val atSunset = TwilightTimeline.resolve(1080, 600, 1800).twilightStarsAlpha
        val afterDuskBand = TwilightTimeline.resolve(1125, 600, 1800).twilightStarsAlpha

        assertEquals(1.0f, deepNight, 0.0001f)
        assertEquals(TwilightTimeline.STARS_ALPHA_AT_EVENT, atSunrise, 0.0001f)
        assertEquals(0.0f, afterDawnBand, 0.0001f)
        assertEquals(0.0f, beforeDuskBand, 0.0001f)
        assertEquals(TwilightTimeline.STARS_ALPHA_AT_EVENT, atSunset, 0.0001f)
        assertEquals(1.0f, afterDuskBand, 0.0001f)
    }
}
