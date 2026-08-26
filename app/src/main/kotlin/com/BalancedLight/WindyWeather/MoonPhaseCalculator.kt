package com.BalancedLight.WindyWeather

import java.util.Calendar

internal object MoonPhaseCalculator {
    fun calculateCurrentPhase(): Int {
        val calendar = Calendar.getInstance()
        return calculateForDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    internal fun calculateForDate(inputYear: Int, inputMonth: Int, day: Int): Int {
        var year = inputYear
        var month = inputMonth
        if (month < 3) {
            year--
            month += 12
        }
        month++
        var days = (365.25 * year) + (30.6 * month) + day - 694039.09
        days /= 29.5305882
        val cycle = days - Math.floor(days)
        val phase = Math.round(cycle * 26.0).toInt()
        return phase.coerceIn(0, 26)
    }
}
