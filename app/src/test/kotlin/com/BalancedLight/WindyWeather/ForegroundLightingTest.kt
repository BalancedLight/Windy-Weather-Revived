package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundLightingTest {
    @Test
    fun `windmills preserve both night effects and interpolate toward day`() {
        for ((oldEffect, night) in listOf(false to 0.2f, true to 0f)) {
            assertEquals(night, ForegroundLighting.windmillBrightness(0f, oldEffect), 0.0001f)
            assertEquals((night + 1f) / 2f, ForegroundLighting.windmillBrightness(0.5f, oldEffect), 0.0001f)
            assertEquals(1f, ForegroundLighting.windmillBrightness(1f, oldEffect), 0f)
        }
    }

    @Test
    fun `ground blend preserves endpoints and opaque midpoint`() {
        val day = 0xffe0c080.toInt()
        val night = 0xff204060.toInt()
        assertEquals(night, ForegroundLighting.blendPixel(day, night, 0))
        assertEquals(day, ForegroundLighting.blendPixel(day, night, 1024))
        assertEquals(0xff808070.toInt(), ForegroundLighting.blendPixel(day, night, 512))
    }

    @Test
    fun `ground blend weights transparent edges without dark fringes`() {
        assertEquals(0x80ff0000.toInt(), ForegroundLighting.blendPixel(0xffff0000.toInt(), 0, 512))
        assertEquals(0, ForegroundLighting.blendPixel(0x00ffffff, 0, 512))
        assertEquals(0x804080c0.toInt(), ForegroundLighting.blendPixel(0x804080c0.toInt(), 0x804080c0.toInt(), 512))
    }
}
