package com.BalancedLight.WindyWeather

object MoonFormat {
    fun illuminationPercent(fracIllum: Double): Int {
        var percent = if (fracIllum <= 1.0) fracIllum * 100.0 else fracIllum
        if (percent.isNaN() || percent.isInfinite()) {
            return 0
        }
        percent = Math.max(0.0, Math.min(100.0, percent))
        return java.lang.Math.round(percent).toInt()
    }
}
