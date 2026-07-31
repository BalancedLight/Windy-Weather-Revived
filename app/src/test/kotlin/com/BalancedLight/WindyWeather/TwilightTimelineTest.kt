package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwilightTimelineTest {
    @Test
    fun `resolves non-hour aligned morning and sunset boundaries`() {
        val sunrise = 630
        val sunset = 1845

        assertEquals(TwilightTimeline.SkyPhase.MORNING, TwilightTimeline.resolve(390, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.DAY, TwilightTimeline.resolve(450, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.DAY, TwilightTimeline.resolve(1064, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.SUNSET, TwilightTimeline.resolve(1065, sunrise, sunset).phase)
        assertEquals(TwilightTimeline.SkyPhase.NIGHT, TwilightTimeline.resolve(1125, sunrise, sunset).phase)
    }

    @Test
    fun `uses a continuous daylight arc throughout special sky windows`() {
        val sunrise = 600
        val sunset = 1800

        val morning = TwilightTimeline.resolve(390, sunrise, sunset)
        val noon = TwilightTimeline.resolve(720, sunrise, sunset)
        val sunsetState = TwilightTimeline.resolve(1050, sunrise, sunset)

        assertEquals(0.5f, morning.twilightProgress, 0.0001f)
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
        assertEquals(TwilightTimeline.SkyPhase.DAY, shortDay.phase)
        assertNull(shortDay.daylightProgress)
        assertFalse(shortDay.hasValidDaylightData)
        assertFalse(invalid.twilightTint.green < 1.0f)
    }

    @Test
    fun `twilight tint fades in the intended direction`() {
        val dawnStart = TwilightTimeline.resolve(360, 600, 1800).twilightTint
        val dawnEnd = TwilightTimeline.resolve(420, 600, 1800).twilightTint
        val duskStart = TwilightTimeline.resolve(1020, 600, 1800).twilightTint
        val duskEnd = TwilightTimeline.resolve(1080 - 1, 600, 1800).twilightTint

        assertTrue(dawnStart.blue < dawnEnd.blue)
        assertEquals(1.0f, duskStart.blue, 0.0001f)
        assertTrue(duskEnd.blue < duskStart.blue)
    }
}
