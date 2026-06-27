package com.BalancedLight.WindyWeather

import java.util.Locale

object MoonPhaseMapper {
    fun toLegacyIndex(phase: String?, fracIllum: Double): Int {
        val normalizedPhase = if (phase == null) "" else phase.trim().lowercase(Locale.US)
        val illum: Int = MoonFormat.illuminationPercent(fracIllum)

        if (normalizedPhase.contains("new")) {
            return 0
        }
        if (normalizedPhase.contains("first quarter")) {
            return 7
        }
        if (normalizedPhase.contains("full")) {
            return 13
        }
        if (normalizedPhase.contains("last quarter") || normalizedPhase.contains("third quarter")) {
            return 20
        }
        if (normalizedPhase.contains("waxing")) {
            return com.BalancedLight.WindyWeather.MoonPhaseMapper.clamp(
                java.lang.Math.round(illum * 13.0f / 100.0f),
                1,
                12
            )
        }
        if (normalizedPhase.contains("waning")) {
            val index: Int = 13 + java.lang.Math.round((100 - illum) * 13.0f / 100.0f)
            return com.BalancedLight.WindyWeather.MoonPhaseMapper.clamp(index, 14, 26)
        }

        return 0
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }
}
