package com.BalancedLight.WindyWeather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungEligibilityTest {
    @Test fun manufacturerMatchingIsCaseInsensitiveAndFailClosed() {
        assertTrue(SamsungWeatherRepository.isSamsungManufacturer("SAMSUNG"))
        assertFalse(SamsungWeatherRepository.isSamsungManufacturer("Google"))
        assertFalse(SamsungWeatherRepository.isSamsungManufacturer(null))
    }
}
