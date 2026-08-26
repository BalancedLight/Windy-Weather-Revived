package com.BalancedLight.WindyWeather

import java.util.Locale

internal object MoonPhaseMapper {
    fun toLegacyIndex(phase: String?, fracIllum: Double): Int {
        val normalizedPhase = phase?.trim()?.lowercase(Locale.US) ?: ""
        val illumination = MoonFormat.illuminationPercent(fracIllum)
        if (normalizedPhase.contains("new")) return 0
        if (normalizedPhase.contains("first quarter")) return 7
        if (normalizedPhase.contains("full")) return 13
        if (normalizedPhase.contains("last quarter") || normalizedPhase.contains("third quarter")) {
            return 20
        }
        if (normalizedPhase.contains("waxing")) {
            return Math.round(illumination * 13.0f / 100.0f).coerceIn(1, 12)
        }
        if (normalizedPhase.contains("waning")) {
            return (13 + Math.round((100 - illumination) * 13.0f / 100.0f)).coerceIn(14, 26)
        }
        return 0
    }
}
