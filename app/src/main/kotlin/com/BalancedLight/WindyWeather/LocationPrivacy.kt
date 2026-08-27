package com.BalancedLight.WindyWeather

import java.util.Locale

internal object LocationPrivacy {
    fun roundCoordinate(value: Double): Double = Math.round(value * 100.0) / 100.0

    fun roundCoordinates(latitude: Double, longitude: Double): RoundedCoordinates? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return RoundedCoordinates(
            roundCoordinate(latitude),
            roundCoordinate(longitude)
        )
    }

    /**
     * Coordinates persisted from an earlier refresh are only reused inside the retention
     * window. An entry with no recorded age predates that record and is treated as
     * expired, so the wallpaper cannot stay pinned to a location the device has left.
     */
    fun isCoordinateCacheUsable(storedMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        storedMs > 0L && nowMs - storedMs <= maxAgeMs

    fun storedCoordinates(latitude: Double?, longitude: Double?): RoundedCoordinates? {
        if (latitude == null || longitude == null) return null
        return roundCoordinates(latitude, longitude)
    }
}

internal data class RoundedCoordinates(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude == LocationPrivacy.roundCoordinate(latitude))
        require(longitude == LocationPrivacy.roundCoordinate(longitude))
    }

    val cacheKey: String
        get() = String.format(Locale.US, "%.2f,%.2f", latitude, longitude)
}
