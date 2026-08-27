package com.BalancedLight.WindyWeather

import android.content.Context
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal fun interface PlaceNameResolver {
    fun resolve(coordinates: RoundedCoordinates): String?
}

internal interface PlaceNameCache {
    fun get(coordinateKey: String): String?
    fun put(coordinateKey: String, placeName: String)
}

internal class CachingPlaceNameResolver(
    private val resolver: PlaceNameResolver,
    private val cache: PlaceNameCache
) {
    fun resolve(coordinates: RoundedCoordinates): String {
        val cached = PlaceNameSelection.sanitize(cache.get(coordinates.cacheKey))
        if (cached != null) return cached

        val resolved = PlaceNameSelection.sanitize(
            try {
                resolver.resolve(coordinates)
            } catch (_: Exception) {
                null
            }
        )
        if (resolved != null) {
            cache.put(coordinates.cacheKey, resolved)
            return resolved
        }
        return ""
    }
}

internal object PlaceNameSelection {
    private const val LEGACY_SENTINEL = "Current location"

    fun select(
        locality: String?,
        subAdminArea: String?,
        adminArea: String?,
        countryName: String?
    ): String? {
        return listOf(locality, subAdminArea, adminArea, countryName)
            .firstNotNullOfOrNull(::sanitize)
    }

    fun sanitize(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(LEGACY_SENTINEL, ignoreCase = true)) {
            return null
        }
        return trimmed
    }
}

internal class SharedPreferencesPlaceNameCache(
    private val preferences: SharedPreferences
) : PlaceNameCache {
    override fun get(coordinateKey: String): String? {
        if (preferences.getString(KEY_COORDINATES, null) != coordinateKey) return null
        return preferences.getString(KEY_PLACE_NAME, null)
    }

    override fun put(coordinateKey: String, placeName: String) {
        preferences.edit()
            .putString(KEY_COORDINATES, coordinateKey)
            .putString(KEY_PLACE_NAME, placeName)
            .apply()
    }

    private companion object {
        private const val KEY_COORDINATES = "place_name_coordinates"
        private const val KEY_PLACE_NAME = "place_name"
    }
}

internal class AndroidGeocoderPlaceNameResolver(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val legacyExecutor: ExecutorService = LEGACY_EXECUTOR
) : PlaceNameResolver {
    private val appContext = context.applicationContext

    override fun resolve(coordinates: RoundedCoordinates): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveAsync(geocoder, coordinates)
        } else {
            resolveLegacy(geocoder, coordinates)
        }
        val address = addresses.firstOrNull() ?: return null
        return PlaceNameSelection.select(
            address.locality,
            address.subAdminArea,
            address.adminArea,
            address.countryName
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun resolveAsync(
        geocoder: Geocoder,
        coordinates: RoundedCoordinates
    ): List<Address> {
        val result = AtomicReference<List<Address>>(emptyList())
        val completed = CountDownLatch(1)
        geocoder.getFromLocation(
            coordinates.latitude,
            coordinates.longitude,
            1,
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    result.set(addresses.toList())
                    completed.countDown()
                }

                override fun onError(errorMessage: String?) {
                    completed.countDown()
                }
            }
        )
        return try {
            if (completed.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else emptyList()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        geocoder: Geocoder,
        coordinates: RoundedCoordinates
    ): List<Address> {
        val task = legacyExecutor.submit<List<Address>> {
            geocoder.getFromLocation(coordinates.latitude, coordinates.longitude, 1).orEmpty()
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            emptyList()
        } catch (_: TimeoutException) {
            task.cancel(true)
            emptyList()
        } catch (_: ExecutionException) {
            emptyList()
        }
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MS = 5_000L
        private val LEGACY_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "windy-place-name").apply { isDaemon = true }
        }
    }
}
