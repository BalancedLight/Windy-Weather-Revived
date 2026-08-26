package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPrivacyTest {
    @Test fun coordinatesAreRoundedToTwoDecimalPlaces() {
        assertEquals(37.77, LocationPrivacy.roundCoordinate(37.7749), 0.0)
        assertEquals(-122.42, LocationPrivacy.roundCoordinate(-122.4194), 0.0)
    }
}
