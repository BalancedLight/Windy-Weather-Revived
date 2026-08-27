package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPrivacyTest {
    @Test fun coordinatesAreRoundedToTwoDecimalPlaces() {
        assertEquals(37.77, LocationPrivacy.roundCoordinate(37.7749), 0.0)
        assertEquals(-122.42, LocationPrivacy.roundCoordinate(-122.4194), 0.0)
    }

    @Test fun roundedCoordinateValueAndCacheKeyAreStable() {
        val coordinates = LocationPrivacy.roundCoordinates(37.7749, -122.4194)!!

        assertEquals(37.77, coordinates.latitude, 0.0)
        assertEquals(-122.42, coordinates.longitude, 0.0)
        assertEquals("37.77,-122.42", coordinates.cacheKey)
    }

    @Test fun missingStoredCoordinatesDoNotInventADefaultLocation() {
        assertNull(LocationPrivacy.storedCoordinates(null, null))
        assertNull(LocationPrivacy.storedCoordinates(37.77, null))
        assertNull(LocationPrivacy.storedCoordinates(null, -122.42))
    }

    @Test fun invalidCoordinatesAreRejected() {
        assertNull(LocationPrivacy.roundCoordinates(Double.NaN, 0.0))
        assertNull(LocationPrivacy.roundCoordinates(91.0, 0.0))
        assertNull(LocationPrivacy.roundCoordinates(0.0, 181.0))
    }

    @Test fun cachedCoordinatesExpireInsteadOfPinningAStaleLocation() {
        val day = 24L * 60L * 60L * 1000L
        val now = 1_000_000_000L

        assertTrue(LocationPrivacy.isCoordinateCacheUsable(now - 1000L, now, day))
        assertTrue(LocationPrivacy.isCoordinateCacheUsable(now - day, now, day))
        assertFalse(LocationPrivacy.isCoordinateCacheUsable(now - day - 1L, now, day))
    }

    @Test fun coordinatesWithNoRecordedAgeAreTreatedAsExpired() {
        val day = 24L * 60L * 60L * 1000L

        assertFalse(LocationPrivacy.isCoordinateCacheUsable(0L, 1_000_000_000L, day))
        assertFalse(LocationPrivacy.isCoordinateCacheUsable(-1L, 1_000_000_000L, day))
    }

    @Test fun activeLocationProvidersPreferCoarseFriendlySourcesAndIgnorePassive() {
        assertEquals(
            listOf("network", "fused", "gps", "custom"),
            LocationProviderOrder.active(
                listOf("passive", "gps", "custom", "network", "fused", "network")
            )
        )
    }
}
