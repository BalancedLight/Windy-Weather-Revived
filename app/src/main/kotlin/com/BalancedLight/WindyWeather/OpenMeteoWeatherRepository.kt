package com.BalancedLight.WindyWeather

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object OpenMeteoWeatherRepository {
    private val TAG = "OpenMeteoRepository"
    private val PREF_NAME = "weather_cache"
    private val KEY_CITY = "city"
    private val KEY_WEATHER_CODE = "weather_code"
    private val KEY_CURRENT_TEMP = "current_temp"
    private val KEY_HIGH = "high"
    private val KEY_LOW = "low"
    private val KEY_HUMIDITY = "humidity_percent"
    private val KEY_WIND_SPEED = "wind_speed_kmh"
    private val KEY_SUNRISE = "sunrise"
    private val KEY_SUNSET = "sunset"
    private val KEY_MOON = "moon"
    private val KEY_CODE_SOURCE = "code_source"
    private val KEY_LAST_UPDATE_MS = "last_update_ms"
    private val KEY_LAST_LAT = "last_lat"
    private val KEY_LAST_LON = "last_lon"
    private val KEY_LAST_COORD_MS = "last_coord_ms"
    private val DEFAULT_STALE_MAX_AGE_MS = 6L * 60L * 60L * 1000L
    private const val CURRENT_LOCATION_TIMEOUT_SECONDS = 5L

    /** A fix older than this is not trusted to answer "where is the device now". */
    private const val MAX_LAST_KNOWN_AGE_MS = 30L * 60L * 1000L

    /** Assumed horizontal error, in metres, for a fix that reports no accuracy. */
    private const val UNKNOWN_ACCURACY_METRES = 3000.0

    /** Metres of assumed drift per second of age, used to age out a stale fix. */
    private const val AGE_PENALTY_METRES_PER_SECOND = 1.0

    /**
     * Coordinates persisted from an earlier refresh expire after this window. Without an
     * expiry the very first fix ever cached is reused forever and the wallpaper silently
     * reports another region's weather.
     */
    private const val CACHED_COORDINATE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
    private val MAIN_HANDLER: Handler = Handler(Looper.getMainLooper())
    private val REFRESH_LOCK: Object = Object()
    private val PENDING_CALLBACKS: ArrayList<Callback?> = ArrayList()
    private var sRefreshInFlight = false

    fun refreshAsync(context: Context, callback: Callback?) {
        val appContext: Context = context.getApplicationContext()
        if (!LocationWeatherConsent.isTransferAllowed(appContext)) {
            val cached = readFromCache(appContext)
            if (callback != null) {
                MAIN_HANDLER.post { callback.onWeatherUpdated(cached) }
            }
            Log.d(TAG, "Weather transfer skipped because location consent is not active")
            return
        }
        var shouldStartRefresh = false
        kotlin.synchronized(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.REFRESH_LOCK) {
            if (callback != null) {
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PENDING_CALLBACKS.add(
                    callback
                )
            }
            if (!com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.sRefreshInFlight) {
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.sRefreshInFlight = true
                shouldStartRefresh = true
            }
        }
        if (!shouldStartRefresh) {
            return
        }
        com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.EXECUTOR.execute({
            var updated: WeatherSnapshot? = null
            val fallback: WeatherSnapshot? =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.readFromCache(appContext)
            try {
                updated = com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.fetchLatest(
                    appContext,
                    fallback
                )
                if (updated != null) {
                    com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.writeToCache(
                        appContext,
                        updated
                    )
                } else {
                    updated = fallback
                }
            } catch (e: Exception) {
                Log.e(
                    com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                    "Weather refresh task crashed",
                    e
                )
                updated = fallback
            }
            val callbacksToNotify: ArrayList<Callback?>?
            kotlin.synchronized(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.REFRESH_LOCK) {
                callbacksToNotify =
                    ArrayList(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PENDING_CALLBACKS)
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PENDING_CALLBACKS.clear()
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.sRefreshInFlight = false
            }
            if (callbacksToNotify?.isNotEmpty() == true && updated != null) {
                val finalUpdated: WeatherSnapshot? = updated
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.MAIN_HANDLER.post({
                    for (pendingCallback in callbacksToNotify) {
                        try {
                            pendingCallback?.onWeatherUpdated(finalUpdated)
                        } catch (callbackError: Exception) {
                            Log.e(
                                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                                "Weather callback failed",
                                callbackError
                            )
                        }
                    }
                })
            }
        })
    }

    fun readFromCache(context: Context?): WeatherSnapshot? {
        if (context == null) {
            return WeatherSnapshot.empty()
        }
        val preferences: SharedPreferences = context.getSharedPreferences(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PREF_NAME,
            Context.MODE_PRIVATE
        )
        val weatherCode: Int = preferences.getInt(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_WEATHER_CODE,
            WeatherSnapshot.UNKNOWN_WEATHER_CODE
        )
        if (weatherCode == WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
            return WeatherSnapshot.empty()
        }
        val fallbackCurrentTemp: Int = preferences.getInt(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_HIGH,
            0
        )
        val currentTemp =
            if (preferences.contains(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CURRENT_TEMP))
                preferences.getInt(
                    com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CURRENT_TEMP,
                    fallbackCurrentTemp
                )
            else
                fallbackCurrentTemp
        return WeatherSnapshot(
            weatherCode,
            currentTemp,
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_HIGH,
                0
            ),
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LOW,
                0
            ),
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_HUMIDITY,
                0
            ),
            preferences.getFloat(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_WIND_SPEED,
                0.0f
            ),
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_SUNRISE,
                600
            ),
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_SUNSET,
                1800
            ),
            preferences.getInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_MOON,
                13
            ),
            preferences.getString(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CITY,
                ""
            ) ?: "",
            preferences.getLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_UPDATE_MS,
                0L
            ),
            preferences.getString(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CODE_SOURCE,
                WeatherSnapshot.SOURCE_UNKNOWN
            ) ?: WeatherSnapshot.SOURCE_UNKNOWN
        )
    }

    fun isCacheStale(context: Context?, maxAgeMs: Long): Boolean {
        if (context == null) {
            return true
        }
        val preferences: SharedPreferences = context.getSharedPreferences(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PREF_NAME,
            Context.MODE_PRIVATE
        )
        val lastUpdateMs: Long = preferences.getLong(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_UPDATE_MS,
            0L
        )
        if (lastUpdateMs <= 0L) {
            return true
        }
        return System.currentTimeMillis() - lastUpdateMs > maxAgeMs
    }

    fun isCacheStale(context: Context): Boolean {
        return com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.isCacheStale(
            context,
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.DEFAULT_STALE_MAX_AGE_MS
        )
    }

    fun writeToCache(context: Context?, snapshot: WeatherSnapshot?) {
        if (context == null || snapshot == null) {
            return
        }
        val preferences: SharedPreferences = context.getSharedPreferences(
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PREF_NAME,
            Context.MODE_PRIVATE
        )
        preferences
            .edit()
            .putString(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CITY,
                snapshot.cityName
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_WEATHER_CODE,
                snapshot.weatherCode
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CURRENT_TEMP,
                snapshot.currentTempC
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_HIGH,
                snapshot.highTempC
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LOW,
                snapshot.lowTempC
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_HUMIDITY,
                snapshot.humidityPercent
            )
            .putFloat(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_WIND_SPEED,
                snapshot.windSpeedKmh
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_SUNRISE,
                snapshot.sunriseTime
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_SUNSET,
                snapshot.sunsetTime
            )
            .putInt(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_MOON,
                snapshot.moonPhase
            )
            .putLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_UPDATE_MS,
                snapshot.lastUpdatedMs
            )
            .putString(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_CODE_SOURCE,
                snapshot.codeSource
            )
            .apply()
    }

    private fun fetchLatest(context: Context, fallback: WeatherSnapshot?): WeatherSnapshot? {
        try {
            val preferences: SharedPreferences = context.getSharedPreferences(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.PREF_NAME,
                Context.MODE_PRIVATE
            )
            val coordinates: RoundedCoordinates =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.resolveLocation(
                    context,
                    preferences
                ) ?: run {
                    Log.w(TAG, "Weather refresh skipped because no consented location is available")
                    return null
                }
            val body: String =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.downloadForecast(
                    coordinates
                )
            val root: JSONObject = JSONObject(body)
            val daily: JSONObject = root.getJSONObject("daily")
            val current: JSONObject? = root.optJSONObject("current")

            val weatherCodes: JSONArray = daily.getJSONArray("weather_code")
            val highs: JSONArray = daily.getJSONArray("temperature_2m_max")
            val lows: JSONArray = daily.getJSONArray("temperature_2m_min")
            val sunrises: JSONArray = daily.getJSONArray("sunrise")
            val sunsets: JSONArray = daily.getJSONArray("sunset")

            val fallbackSunrise = if (fallback != null) fallback.sunriseTime else 600
            val fallbackSunset = if (fallback != null) fallback.sunsetTime else 1800
            val dailyWeatherCode: Int = weatherCodes.optInt(0, WeatherSnapshot.UNKNOWN_WEATHER_CODE)
            val currentWeatherCode: Int = if (current != null)
                current.optInt("weather_code", WeatherSnapshot.UNKNOWN_WEATHER_CODE)
            else
                WeatherSnapshot.UNKNOWN_WEATHER_CODE
            val weatherCode: Int
            val weatherCodeSource: String?
            if (currentWeatherCode != WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                weatherCode = currentWeatherCode
                weatherCodeSource = WeatherSnapshot.SOURCE_CURRENT
            } else {
                weatherCode = dailyWeatherCode
                weatherCodeSource = WeatherSnapshot.SOURCE_DAILY
            }
            val high = java.lang.Math.round(highs.optDouble(0, 0.0)).toInt()
            val low = java.lang.Math.round(lows.optDouble(0, 0.0)).toInt()
            val fallbackCurrentTemp = (high + low) / 2
            val currentTemp = if (current != null)
                java.lang.Math.round(current.optDouble("temperature_2m", fallbackCurrentTemp.toDouble())).toInt()
            else
                fallbackCurrentTemp
            val windSpeedKmh = if (current != null)
                current.optDouble("wind_speed_10m", 0.0).toFloat()
            else
                0.0f
            val humidityPercent = if (current != null)
                java.lang.Math.round(current.optDouble("relative_humidity_2m", 0.0)).toInt()
            else
                0
            val sunrise: Int =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.parseHourMinute(
                    sunrises.optString(
                        0,
                        ""
                    ), fallbackSunrise
                )
            val sunset: Int =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.parseHourMinute(
                    sunsets.optString(
                        0,
                        ""
                    ), fallbackSunset
                )
            val moonPhase = DistributionFeatures.resolveMoonPhase(
                coordinates.latitude,
                coordinates.longitude,
                fallback
            )

            val city = CachingPlaceNameResolver(
                AndroidGeocoderPlaceNameResolver(context),
                SharedPreferencesPlaceNameCache(preferences)
            ).resolve(coordinates).ifEmpty { fallback?.cityName.orEmpty() }

            Log.d(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                ("Resolved weatherCode=" + weatherCode
                        + " source=" + weatherCodeSource
                        + " currentTempC=" + currentTemp
                        + " humidity=" + humidityPercent
                        + " windSpeedKmh=" + windSpeedKmh
                        + " city=" + city
                        + " lat=" + coordinates.latitude
                        + " lon=" + coordinates.longitude)
            )

            return WeatherSnapshot(
                weatherCode,
                currentTemp,
                high,
                low,
                humidityPercent,
                windSpeedKmh,
                sunrise,
                sunset,
                moonPhase,
                city,
                System.currentTimeMillis(),
                weatherCodeSource
            )
        } catch (e: Exception) {
            Log.e(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                "Weather refresh failed",
                e
            )
            return null
        }
    }

    internal fun hasUsableCoordinates(context: Context?): Boolean {
        if (context == null || !LocationWeatherConsent.isTransferAllowed(context)) {
            return false
        }
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (readCachedCoordinates(preferences) != null) {
            return true
        }
        val lastKnown = getBestLastKnownLocation(appContext) ?: return false
        return LocationPrivacy.roundCoordinates(lastKnown.latitude, lastKnown.longitude) != null
    }

    /**
     * Order matters. A recent fix wins outright; otherwise we actively ask for one before
     * falling back to anything stale, so the wallpaper stops trailing the device.
     */
    private fun resolveLocation(
        context: Context,
        preferences: SharedPreferences
    ): RoundedCoordinates? {
        if (!LocationWeatherConsent.isTransferAllowed(context)) {
            return null
        }
        val lastKnown = bestFix(
            getBestLastKnownLocation(context),
            requestFusedLastLocation(context)
        )
        val location = lastKnown?.takeIf { it.isRecent(MAX_LAST_KNOWN_AGE_MS) }
            ?: requestFusedCurrentLocation(context)
            ?: requestPlatformCurrentLocation(context)
            ?: lastKnown
        if (location == null) {
            return readCachedCoordinates(preferences)
        }
        val coordinates = LocationPrivacy.roundCoordinates(location.latitude, location.longitude)
            ?: return readCachedCoordinates(preferences)
        preferences
            .edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToLongBits(coordinates.latitude))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToLongBits(coordinates.longitude))
            .putLong(KEY_LAST_COORD_MS, System.currentTimeMillis())
            .apply()
        return coordinates
    }

    private fun readCachedCoordinates(preferences: SharedPreferences): RoundedCoordinates? {
        // Entries written before coordinate ages were recorded have an unknown age, so they
        // are treated as expired rather than pinning the wallpaper to a long-gone location.
        val storedMs = preferences.getLong(KEY_LAST_COORD_MS, 0L)
        if (!LocationPrivacy.isCoordinateCacheUsable(
                storedMs,
                System.currentTimeMillis(),
                CACHED_COORDINATE_MAX_AGE_MS
            )
        ) {
            Log.d(TAG, "Ignoring cached coordinates that are expired or of unknown age")
            return null
        }
        val latitude = if (preferences.contains(KEY_LAST_LAT)) {
            java.lang.Double.longBitsToDouble(preferences.getLong(KEY_LAST_LAT, 0L))
        } else {
            null
        }
        val longitude = if (preferences.contains(KEY_LAST_LON)) {
            java.lang.Double.longBitsToDouble(preferences.getLong(KEY_LAST_LON, 0L))
        } else {
            null
        }
        return LocationPrivacy.storedCoordinates(latitude, longitude)
    }

    private fun Location.ageMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L

    private fun Location.isRecent(maxAgeMs: Long): Boolean = ageMs() in 0L..maxAgeMs

    /**
     * Rough "how wrong might this be", in metres, blending reported accuracy with age.
     * Lower is better. Providers disagree more than they admit, so the sharper fix wins
     * rather than whichever one answered first.
     */
    private fun Location.fixQuality(): Double {
        val reported = if (hasAccuracy() && accuracy > 0f) {
            accuracy.toDouble()
        } else {
            UNKNOWN_ACCURACY_METRES
        }
        val ageSeconds = ageMs().coerceAtLeast(0L) / 1000.0
        return reported + (ageSeconds * AGE_PENALTY_METRES_PER_SECOND)
    }

    private fun bestFix(vararg candidates: Location?): Location? =
        candidates.filterNotNull().minByOrNull { it.fixQuality() }

    private fun getBestLastKnownLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            ?: return null
        val providers = try {
            manager.getProviders(true)
        } catch (_: Exception) {
            emptyList()
        }
        var best: Location? = null
        for (provider in providers) {
            val candidate = try {
                val candidate: Location? = manager.getLastKnownLocation(provider)
                candidate
            } catch (_: SecurityException) {
                // Coarse-only callers may not read every provider below API 31.
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            best = bestFix(best, candidate)
        }
        return best
    }

    /**
     * Play services keeps its own coarse-granted cache, and it is the one fused call that
     * returns reliably under ACCESS_COARSE_LOCATION alone. It is only a candidate though:
     * its network-derived fix can be confidently wrong, so it is ranked, never trusted on
     * sight.
     */
    private fun requestFusedLastLocation(context: Context): Location? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null
        }
        return try {
            awaitLocationTask(
                LocationServices.getFusedLocationProviderClient(context).getLastLocation(),
                "Fused last location"
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Fused location rejected coarse access", error)
            null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Fused location is unavailable", error)
            null
        }
    }

    private fun requestFusedCurrentLocation(context: Context): Location? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Fused current location request skipped on the main thread")
            return null
        }
        val cancellation = CancellationTokenSource()
        return try {
            Log.d(TAG, "Waiting for a fused current approximate location")
            awaitLocationTask(
                // PRIORITY_HIGH_ACCURACY is a fine-location priority; without
                // ACCESS_FINE_LOCATION it is silently degraded and tends to stall.
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellation.token
                ),
                "Fused current location"
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Fused location rejected coarse access", error)
            null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Fused location is unavailable", error)
            null
        } finally {
            cancellation.cancel()
        }
    }

    private fun awaitLocationTask(task: Task<Location>, label: String): Location? {
        val result = AtomicReference<Location?>()
        val completed = CountDownLatch(1)
        task
            .addOnSuccessListener { location ->
                result.set(location)
                completed.countDown()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, label + " failed", error)
                completed.countDown()
            }
            .addOnCanceledListener { completed.countDown() }
        return try {
            if (completed.await(CURRENT_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                result.get()
            } else {
                Log.w(TAG, label + " timed out")
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun requestPlatformCurrentLocation(context: Context): Location? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Current location request skipped on the main thread")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            ?: return null
        val providers = try {
            // GPS is deliberately included: from API 31 a coarse-only caller receives a
            // coarsened GPS fix, and below that the per-provider SecurityException catch
            // skips it. Excluding it outright threw away the only fix on many devices.
            LocationProviderOrder.active(
                listOf(LocationManager.NETWORK_PROVIDER, "fused") +
                    manager.getProviders(true)
            ).filter { provider ->
                try {
                    manager.isProviderEnabled(provider)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (providers.isEmpty()) {
            Log.w(TAG, "Current location request skipped because no active provider is available")
            return null
        }

        val result = AtomicReference<Location?>()
        val completed = CountDownLatch(1)
        val cancellation = CancellationSignal()
        val executor = ContextCompat.getMainExecutor(context)
        var requested = 0
        // Every provider is asked at once and the first real answer wins, so the wait costs
        // one timeout in total rather than one per provider.
        for (provider in providers) {
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    cancellation,
                    executor
                ) { location ->
                    if (location != null && result.compareAndSet(null, location)) {
                        completed.countDown()
                    }
                }
                requested++
            } catch (error: SecurityException) {
                Log.w(TAG, "Location provider " + provider + " rejected coarse access", error)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Location provider " + provider + " is no longer available", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Location provider " + provider + " could not start", error)
            }
        }
        if (requested == 0) {
            Log.w(TAG, "Current location request skipped because no provider accepted it")
            return null
        }

        Log.d(TAG, "Waiting for a current approximate location")
        try {
            if (!completed.await(CURRENT_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Current approximate location request timed out")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            cancellation.cancel()
        }
        return result.get()
    }

    @Throws(IOException::class)
    private fun downloadForecast(coordinates: RoundedCoordinates): String {
        val endpoint: String? = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&current=weather_code,temperature_2m,relative_humidity_2m,wind_speed_10m&forecast_days=1&timezone=auto",
            coordinates.latitude,
            coordinates.longitude
        )
        val connection: HttpURLConnection = URL(endpoint).openConnection() as HttpURLConnection
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(10000)
        connection.setRequestMethod("GET")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "WindyWeather/1.0")

        try {
            val code: Int = connection.getResponseCode()
            if (code < 200 || code >= 300) {
                throw IOException("HTTP " + code)
            }
            return com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.readFully(connection.getInputStream())
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun readFully(inputStream: InputStream?): String {
        val builder: StringBuilder = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                builder.append(line)
            }
        }
        return builder.toString()
    }

    private fun parseHourMinute(isoDateTime: String?, fallback: Int): Int {
        if (isoDateTime == null || isoDateTime.isEmpty()) {
            return fallback
        }
        val marker: Int = isoDateTime.lastIndexOf('T')
        val timePart = if (marker >= 0 && marker + 6 <= isoDateTime.length)
            isoDateTime.substring(marker + 1, marker + 6)
        else
            isoDateTime
        val parts: List<String> = timePart.split(":")
        if (parts.size != 2) {
            return fallback
        }
        try {
            val hour: Int = java.lang.Integer.parseInt(parts[0])
            val minute: Int = java.lang.Integer.parseInt(parts[1])
            return (hour * 100) + minute
        } catch (e: NumberFormatException) {
            return fallback
        }
    }

    fun clearLocationDerivedCache(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    internal fun interface Callback {
        fun onWeatherUpdated(snapshot: WeatherSnapshot?)
    }

}

