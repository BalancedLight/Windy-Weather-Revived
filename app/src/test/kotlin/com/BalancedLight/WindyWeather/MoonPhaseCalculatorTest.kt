package com.BalancedLight.WindyWeather

import org.junit.Assert.assertTrue
import org.junit.Test

class MoonPhaseCalculatorTest {
    @Test fun localMoonCalculationAlwaysReturnsLegacyRange() {
        for (month in 1..12) {
            val phase = MoonPhaseCalculator.calculateForDate(2026, month, 15)
            assertTrue("phase=$phase month=$month", phase in 0..26)
        }
    }
}
