package com.BalancedLight.WindyWeather

import kotlin.math.roundToInt

internal object ForegroundLighting {
    const val BLEND_STEPS = 1024

    fun blendStep(daylight: Float): Int =
        (daylight.coerceIn(0f, 1f) * BLEND_STEPS).roundToInt()

    fun windmillBrightness(daylight: Float, oldNightEffect: Boolean): Float {
        val night = if (oldNightEffect) 0f else 0.2f
        return night + (1f - night) * daylight.coerceIn(0f, 1f)
    }

    /** Mix in premultiplied alpha, then return straight ARGB for Bitmap.setPixels. */
    fun blendPixel(day: Int, night: Int, step: Int): Int {
        if (step <= 0) return night
        if (step >= BLEND_STEPS) return day
        if (day == night) return day
        val dayAlpha = day ushr 24
        val nightAlpha = night ushr 24
        if (dayAlpha == nightAlpha) {
            if (dayAlpha == 0) return 0
            var result = dayAlpha shl 24
            for (shift in 16 downTo 0 step 8) {
                val channel = ((((day ushr shift) and 255) * step +
                    ((night ushr shift) and 255) * (BLEND_STEPS - step) + BLEND_STEPS / 2) / BLEND_STEPS)
                result = result or (channel shl shift)
            }
            return result
        }
        val dayWeight = dayAlpha.toLong() * step
        val nightWeight = nightAlpha.toLong() * (BLEND_STEPS - step)
        val alphaWeight = dayWeight + nightWeight
        if (alphaWeight == 0L) return 0
        val alpha = ((alphaWeight + BLEND_STEPS / 2) / BLEND_STEPS).toInt()
        var result = alpha shl 24
        for (shift in 16 downTo 0 step 8) {
            val value = (((day ushr shift) and 255) * dayWeight +
                ((night ushr shift) and 255) * nightWeight + alphaWeight / 2) / alphaWeight
            result = result or (value.toInt() shl shift)
        }
        return result
    }
}
