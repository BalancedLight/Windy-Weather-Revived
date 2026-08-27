package com.BalancedLight.WindyWeather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceNameResolverTest {
    @Test fun citySelectionPrefersLocalityThenBroaderRegions() {
        assertEquals(
            "Pittsburgh",
            PlaceNameSelection.select("Pittsburgh", "Allegheny County", "Pennsylvania", "United States")
        )
        assertEquals(
            "Allegheny County",
            PlaceNameSelection.select(" ", "Allegheny County", "Pennsylvania", "United States")
        )
        assertEquals(
            "Pennsylvania",
            PlaceNameSelection.select(null, null, "Pennsylvania", "United States")
        )
    }

    @Test fun legacyCurrentLocationSentinelIsNeverSelectedOrCached() {
        assertNull(PlaceNameSelection.sanitize("Current location"))
        val cache = MemoryPlaceNameCache()
        val resolver = CachingPlaceNameResolver(
            PlaceNameResolver { "Current location" },
            cache
        )

        assertEquals("", resolver.resolve(coordinates(40.44, -79.99)))
        assertEquals(emptyMap<String, String>(), cache.values)
    }

    @Test fun successfulResolutionIsCachedByRoundedCoordinates() {
        val cache = MemoryPlaceNameCache()
        var calls = 0
        val resolver = CachingPlaceNameResolver(
            PlaceNameResolver {
                calls += 1
                if (calls == 1) "Pittsburgh" else null
            },
            cache
        )
        val pittsburgh = coordinates(40.44, -79.99)

        assertEquals("Pittsburgh", resolver.resolve(pittsburgh))
        assertEquals("Pittsburgh", resolver.resolve(pittsburgh))
        assertEquals("Pittsburgh", cache.values[pittsburgh.cacheKey])
        assertEquals(1, calls)
    }

    @Test fun failureDoesNotReuseCityFromDifferentRoundedCoordinates() {
        val cache = MemoryPlaceNameCache().apply {
            put(coordinates(40.44, -79.99).cacheKey, "Pittsburgh")
        }
        val resolver = CachingPlaceNameResolver(PlaceNameResolver { null }, cache)

        assertEquals("", resolver.resolve(coordinates(39.95, -75.17)))
    }

    @Test fun resolverReceivesOnlyRoundedCoordinates() {
        val expected = LocationPrivacy.roundCoordinates(40.4406, -79.9959)!!
        var received: RoundedCoordinates? = null
        val resolver = CachingPlaceNameResolver(
            PlaceNameResolver {
                received = it
                "Pittsburgh"
            },
            MemoryPlaceNameCache()
        )

        resolver.resolve(expected)

        assertEquals(40.44, received!!.latitude, 0.0)
        assertEquals(-80.0, received!!.longitude, 0.0)
    }

    private fun coordinates(latitude: Double, longitude: Double): RoundedCoordinates {
        return LocationPrivacy.roundCoordinates(latitude, longitude)!!
    }

    private class MemoryPlaceNameCache : PlaceNameCache {
        val values = mutableMapOf<String, String>()

        override fun get(coordinateKey: String): String? = values[coordinateKey]

        override fun put(coordinateKey: String, placeName: String) {
            values[coordinateKey] = placeName
        }
    }
}
