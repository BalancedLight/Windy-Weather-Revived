package com.BalancedLight.WindyWeather

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private const val DEFAULT_LAT = 37.7749
    private val DEFAULT_LON = -122.4194
    private val DEFAULT_STALE_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
    private val MAIN_HANDLER: Handler = Handler(Looper.getMainLooper())
    private val REFRESH_LOCK: Object = Object()
    private val PENDING_CALLBACKS: ArrayList<Callback?> = ArrayList()
    private var sRefreshInFlight = false

    fun refreshAsync(context: Context, callback: Callback?) {
        val appContext: Context = context.getApplicationContext()
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
            val latLon: LatLon =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.resolveLocation(
                    context,
                    preferences
                )
            val body: String =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.downloadForecast(
                    latLon.latitude,
                    latLon.longitude
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
            val moonPhase: Int =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.resolveMoonPhase(
                    latLon,
                    fallback
                )

            var city: String =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.resolveCityName(
                    context,
                    latLon.latitude,
                    latLon.longitude
                )
            if (city.isEmpty() && fallback != null) {
                city = fallback.cityName
            }
            if (city.isEmpty()) {
                city = "Current location"
            }

            Log.d(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                ("Resolved weatherCode=" + weatherCode
                        + " source=" + weatherCodeSource
                        + " currentTempC=" + currentTemp
                        + " humidity=" + humidityPercent
                        + " windSpeedKmh=" + windSpeedKmh
                        + " city=" + city
                        + " lat=" + latLon.latitude
                        + " lon=" + latLon.longitude)
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

    private fun resolveLocation(context: Context, preferences: SharedPreferences): LatLon {
        val cachedLat: Double = java.lang.Double.longBitsToDouble(
            preferences.getLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_LAT,
                java.lang.Double.doubleToLongBits(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.DEFAULT_LAT)
            )
        )
        val cachedLon: Double = java.lang.Double.longBitsToDouble(
            preferences.getLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_LON,
                java.lang.Double.doubleToLongBits(com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.DEFAULT_LON)
            )
        )
        if (!com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.hasLocationPermission(context)) {
            return com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.LatLon(
                cachedLat,
                cachedLon
            )
        }
        val location: Location? =
            com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.getBestLastKnownLocation(
                context
            )
        if (location == null) {
            return com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.LatLon(
                cachedLat,
                cachedLon
            )
        }
        preferences
            .edit()
            .putLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_LAT,
                java.lang.Double.doubleToLongBits(location.getLatitude())
            )
            .putLong(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.KEY_LAST_LON,
                java.lang.Double.doubleToLongBits(location.getLongitude())
            )
            .apply()
        return com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.LatLon(
            location.getLatitude(),
            location.getLongitude()
        )
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getBestLastKnownLocation(context: Context): Location? {
        try {
            val manager: LocationManager? =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            if (manager == null) {
                return null
            }
            val providers: List<String> = manager.getProviders(true)
            var best: Location? = null
            for (provider in providers) {
                val candidate: Location? = manager.getLastKnownLocation(provider)
                if (candidate == null) {
                    continue
                }
                if (best == null) {
                    best = candidate
                    continue
                }
                if (candidate.getTime() > best.getTime()) {
                    best = candidate
                }
            }
            return best
        } catch (ignored: SecurityException) {
            return null
        }
    }

    private fun resolveCityName(context: Context?, latitude: Double, longitude: Double): String {
        val safeContext = context ?: return ""
        if (!Geocoder.isPresent()) {
            return ""
        }
        try {
            val geocoder = Geocoder(safeContext, Locale.getDefault())
            val addresses: List<Address?>? = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses == null || addresses.isEmpty()) {
                return ""
            }
            val address: Address = addresses[0] ?: return ""
            val locality: String? = address.getLocality()
            if (locality != null && !locality.isEmpty()) {
                return locality
            }
            val admin: String? = address.getAdminArea()
            if (admin != null && !admin.isEmpty()) {
                return admin
            }
            val country: String? = address.getCountryName()
            if (country != null) {
                return country
            }
        } catch (ignored: IOException) {
        } catch (ignored: IllegalArgumentException) {
        } catch (ignored: SecurityException) {
        }
        return ""
    }

    @Throws(IOException::class)
    private fun downloadForecast(latitude: Double, longitude: Double): String {
        val endpoint: String? = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&current=weather_code,temperature_2m,relative_humidity_2m,wind_speed_10m&forecast_days=1&timezone=auto",
            latitude,
            longitude
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

    private fun resolveMoonPhase(latLon: LatLon, fallback: WeatherSnapshot?): Int {
        try {
            val date: String =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.todayYYYYMMDD()
            val tzOffset: String =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.currentUtcOffsetHours()
            val moonInfo: MoonInfo =
                UsnoMoonClient().fetchMoonInfo(date, latLon.latitude, latLon.longitude, tzOffset)
            val moonPhase: Int = MoonPhaseMapper.toLegacyIndex(moonInfo.phase, moonInfo.fracIllum)
            Log.d(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                ("USNO moon success phase=" + moonInfo.phase
                        + " fracIllum=" + moonInfo.fracIllum
                        + " mappedIndex=" + moonPhase
                        + " tz=" + tzOffset
                        + " date=" + date)
            )
            return moonPhase
        } catch (e: Exception) {
            val fallbackPhase: Int =
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.calculateMoonPhase()
            Log.w(
                com.BalancedLight.WindyWeather.OpenMeteoWeatherRepository.TAG,
                ("USNO moon fallback to local calculation phase=" + fallbackPhase
                        + " reason=" + e.message)
            )
            return fallbackPhase
        }
    }

    private fun todayYYYYMMDD(): String {
        val calendar: Calendar = Calendar.getInstance()
        val year: Int = calendar.get(Calendar.YEAR)
        val month: Int = calendar.get(Calendar.MONTH) + 1
        val day: Int = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun currentUtcOffsetHours(): String {
        val zone: TimeZone = TimeZone.getDefault()
        val offsetHours: Double = zone.getOffset(System.currentTimeMillis()) / 3600000.0
        if (Math.abs(offsetHours - Math.rint(offsetHours)) < 0.000001) {
            return String.format(Locale.US, "%.0f", offsetHours)
        }
        if (Math.abs((offsetHours * 2.0) - Math.rint(offsetHours * 2.0)) < 0.000001) {
            return String.format(Locale.US, "%.1f", offsetHours)
        }
        return String.format(Locale.US, "%.2f", offsetHours)
    }

    private fun calculateMoonPhase(): Int {
        val calendar: Calendar = Calendar.getInstance()
        var year: Int = calendar.get(Calendar.YEAR)
        var month: Int = calendar.get(Calendar.MONTH) + 1
        val day: Int = calendar.get(Calendar.DAY_OF_MONTH)
        if (month < 3) {
            year--
            month += 12
        }
        month++
        var days = (365.25 * year) + (30.6 * month) + day - 694039.09
        days /= 29.5305882
        val cycle: Double = days - Math.floor(days)
        val phase = java.lang.Math.round(cycle * 26.0).toInt()
        if (phase < 0) {
            return 0
        }
        return Math.min(phase, 26)
    }

    internal fun interface Callback {
        fun onWeatherUpdated(snapshot: WeatherSnapshot?)
    }

    private class LatLon(val latitude: Double, val longitude: Double)
}

