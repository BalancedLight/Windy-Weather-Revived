package com.BalancedLight.WindyWeather

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

internal object SamsungWeatherRepository {
    private val TAG = "SamsungWeatherRepo"
    val PERMISSION_READ_DANGEROUS_PROVIDER: String =
        "com.samsung.android.weather.permission.READ_DANGEROUS_PROVIDER"
    private val SAMSUNG_WEATHER_PACKAGES = arrayOf<String?>(
        "com.samsung.android.weather",
        "com.sec.android.daemonapp",
        "com.sec.android.daemonapp.ap",
        "com.sec.android.widgetapp.ap.accuweather",
        "com.sec.android.widgetapp.ap.accuweatherdaemon"
    )
    private val SAMSUNG_WEATHER_AUTHORITIES = arrayOf<String?>(
        "com.samsung.android.weather.content.provider.level.dangerous",
        "com.samsung.android.weather.content.provider.level.system",
        "com.samsung.android.weather.provider",
        "com.samsung.android.weather.content.provider",
        "com.sec.android.daemonapp.ap.accuweather.provider",
        "com.sec.android.widgetapp.ap.accuweather.provider"
    )
    private val DANGEROUS_PROVIDER_URIS = arrayOf<String?>(
        "content://com.samsung.android.weather.content.provider.level.dangerous/weatherinfo",
        "content://com.samsung.android.weather.content.provider.level.dangerous/weather",
        "content://com.samsung.android.weather.content.provider.level.dangerous/current",
        "content://com.samsung.android.weather.provider/dangerous/weather",
        "content://com.samsung.android.weather.provider/dangerous/current",
        "content://com.samsung.android.weather.provider/dangerous/current_weather",
        "content://com.samsung.android.weather.provider/dangerous"
    )
    private val FALLBACK_PROVIDER_URIS = arrayOf<String?>(
        "content://com.sec.android.daemonapp.ap.accuweather.provider/weatherinfo",
        "content://com.sec.android.daemonapp.ap.accuweather.provider/weather/current",
        "content://com.sec.android.daemonapp.ap.accuweather.provider/weather",
        "content://com.sec.android.widgetapp.ap.accuweather.provider/weatherinfo",
        "content://com.sec.android.widgetapp.ap.accuweather.provider/weather/current",
        "content://com.sec.android.widgetapp.ap.accuweather.provider/weather",
        "content://com.samsung.android.weather.provider/weather/current",
        "content://com.samsung.android.weather.provider/weather",
        "content://com.sec.android.daemonapp.ap.accuweather.provider"
    )

    val observerUris: Array<String?>
        get() {
            val merged =
                arrayOfNulls<String>(com.BalancedLight.WindyWeather.SamsungWeatherRepository.DANGEROUS_PROVIDER_URIS.size + com.BalancedLight.WindyWeather.SamsungWeatherRepository.FALLBACK_PROVIDER_URIS.size)
            System.arraycopy(
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.DANGEROUS_PROVIDER_URIS,
                0,
                merged,
                0,
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.DANGEROUS_PROVIDER_URIS.size
            )
            System.arraycopy(
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.FALLBACK_PROVIDER_URIS,
                0,
                merged,
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.DANGEROUS_PROVIDER_URIS.size,
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.FALLBACK_PROVIDER_URIS.size
            )
            return merged
        }

    fun isLikelySupported(context: Context?): Boolean {
        if (context == null || !isSamsungDevice()) {
            return false
        }
        val packageManager: PackageManager? = context.getPackageManager()
        if (packageManager == null) {
            return false
        }
        for (authority in com.BalancedLight.WindyWeather.SamsungWeatherRepository.SAMSUNG_WEATHER_AUTHORITIES) {
            if (authority == null) {
                continue
            }
            try {
                if (packageManager.resolveContentProvider(authority, 0) != null) {
                    return true
                }
            } catch (ignored: Exception) {
            }
        }
        for (packageName in com.BalancedLight.WindyWeather.SamsungWeatherRepository.SAMSUNG_WEATHER_PACKAGES) {
            if (packageName == null) {
                continue
            }
            try {
                packageManager.getPackageInfo(packageName, 0)
                return true
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    fun hasReadDangerousProviderPermission(context: Context?): Boolean {
        if (context == null || !isSamsungDevice()) {
            return false
        }
        try {
            return ContextCompat.checkSelfPermission(context, com.BalancedLight.WindyWeather.SamsungWeatherRepository.PERMISSION_READ_DANGEROUS_PROVIDER) == PackageManager.PERMISSION_GRANTED
        } catch (ignored: Exception) {
            return false
        }
    }

    fun fetchLatest(context: Context?): SamsungSnapshot? {
        if (context == null || !isSamsungDevice() || !com.BalancedLight.WindyWeather.SamsungWeatherRepository.isLikelySupported(
                context
            )
        ) {
            return null
        }
        var fromDangerousProvider: SamsungSnapshot? = null
        if (com.BalancedLight.WindyWeather.SamsungWeatherRepository.hasReadDangerousProviderPermission(
                context
            )
        ) {
            fromDangerousProvider =
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.queryUris(
                    context,
                    com.BalancedLight.WindyWeather.SamsungWeatherRepository.DANGEROUS_PROVIDER_URIS
                )
        } else {
            Log.d(
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.TAG,
                "Missing " + com.BalancedLight.WindyWeather.SamsungWeatherRepository.PERMISSION_READ_DANGEROUS_PROVIDER + ", using fallback provider only"
            )
        }
        if (fromDangerousProvider != null) {
            return fromDangerousProvider
        }
        return com.BalancedLight.WindyWeather.SamsungWeatherRepository.queryUris(
            context,
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.FALLBACK_PROVIDER_URIS
        )
    }

    fun isSamsungDevice(): Boolean = isSamsungManufacturer(Build.MANUFACTURER)

    internal fun isSamsungManufacturer(manufacturer: String?): Boolean {
        return manufacturer?.trim()?.equals("samsung", ignoreCase = true) == true
    }

    private fun queryUris(context: Context, uris: Array<String?>): SamsungSnapshot? {
        for (uriString in uris) {
            val uri: Uri?
            try {
                uri = Uri.parse(uriString)
            } catch (parseError: Exception) {
                continue
            }
            try {
                context.getContentResolver().query(uri, null, null, null, null).use { cursor ->
                    if (cursor == null) {
                        continue
                    }
                    var fallbackRow: SamsungSnapshot? = null
                    while (cursor.moveToNext()) {
                        val parsed: SamsungSnapshot? =
                            com.BalancedLight.WindyWeather.SamsungWeatherRepository.parseSnapshot(
                                cursor
                            )
                        if (parsed == null || !parsed.hasAnyData()) {
                            continue
                        }
                        if (com.BalancedLight.WindyWeather.SamsungWeatherRepository.isLikelyCurrentObservation(
                                parsed
                            )
                        ) {
                            Log.d(
                                com.BalancedLight.WindyWeather.SamsungWeatherRepository.TAG,
                                "Samsung weather data resolved from " + uriString
                            )
                            return parsed
                        }
                        if (fallbackRow == null) {
                            fallbackRow = parsed
                        }
                    }
                    if (fallbackRow != null) {
                        Log.d(
                            com.BalancedLight.WindyWeather.SamsungWeatherRepository.TAG,
                            "Samsung weather fallback row resolved from " + uriString
                        )
                        return fallbackRow
                    }
                }
            } catch (securityException: SecurityException) {
                Log.w(
                    com.BalancedLight.WindyWeather.SamsungWeatherRepository.TAG,
                    "No permission for Samsung provider " + uriString
                )
            } catch (error: Exception) {
                Log.w(
                    com.BalancedLight.WindyWeather.SamsungWeatherRepository.TAG,
                    "Samsung provider query failed for " + uriString + ": " + error.message
                )
            }
        }
        return null
    }

    private fun isLikelyCurrentObservation(snapshot: SamsungSnapshot?): Boolean {
        if (snapshot == null || snapshot.currentTempC == null) {
            return false
        }
        return snapshot.weatherCode != null || (snapshot.highTempC != null && snapshot.lowTempC != null)
    }

    private fun parseSnapshot(cursor: Cursor): SamsungSnapshot? {
        val weatherCode: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.resolveWeatherCode(cursor)
        val currentTemp: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_CURRENT_TEMP",
                "current_temp",
                "currenttemperature",
                "temp",
                "temperature",
                "temperature_now",
                "temp_now",
                "temp_current"
            )
        val highTemp: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_HIGH_TEMP",
                "high_temp",
                "hightemp",
                "temp_high",
                "max_temp",
                "today_high",
                "temp_max",
                "temperature_high",
                "temperature_max"
            )
        val lowTemp: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_LOW_TEMP",
                "low_temp",
                "lowtemp",
                "temp_low",
                "min_temp",
                "today_low",
                "temp_min",
                "temperature_low",
                "temperature_min"
            )
        val humidity: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_HUMIDITY",
                "COL_WEATHER_RELATIVE_HUMIDITY",
                "humidity",
                "humidity_percent",
                "humidity_value",
                "hum"
            )
        val windKmh: Float? = com.BalancedLight.WindyWeather.SamsungWeatherRepository.readFloat(
            cursor,
            "COL_WEATHER_WIND_SPEED",
            "COL_WEATHER_WIND_SPEED_KMH",
            "wind_speed_kmh",
            "wind_speed",
            "wind",
            "wind_kmh",
            "wind_kph"
        )
        val sunrise: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readTimeHHmm(
                cursor,
                "COL_WEATHER_SUNRISE_TIME",
                "sunrise",
                "sunrise_time",
                "sun_rise",
                "sunrisehour"
            )
        val sunset: Int? = com.BalancedLight.WindyWeather.SamsungWeatherRepository.readTimeHHmm(
            cursor,
            "COL_WEATHER_SUNSET_TIME",
            "sunset",
            "sunset_time",
            "sun_set",
            "sunsethour"
        )
        val city: String? = com.BalancedLight.WindyWeather.SamsungWeatherRepository.readString(
            cursor,
            "COL_WEATHER_NAME",
            "COL_WEATHER_NAME_ENG",
            "COL_WEATHER_LOCATION_SHORT_ADDRESS",
            "city",
            "city_name",
            "location_name",
            "location",
            "place",
            "loc_name",
            "name"
        )
        val updated: Long = com.BalancedLight.WindyWeather.SamsungWeatherRepository.readTimestampMs(
            cursor,
            "COL_WEATHER_UPDATE_TIME",
            "COL_WEATHER_TIME",
            "update_time",
            "last_update",
            "last_updated",
            "timestamp",
            "time",
            "obs_time",
            "observation_time"
        )

        val snapshot: SamsungSnapshot =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.SamsungSnapshot(
                weatherCode,
                currentTemp,
                highTemp,
                lowTemp,
                humidity,
                windKmh,
                sunrise,
                sunset,
                city,
                updated
            )
        return if (snapshot.hasAnyData()) snapshot else null
    }

    private fun resolveWeatherCode(cursor: Cursor): Int? {
        val explicitCode: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_CODE",
                "weather_code",
                "condition_code",
                "current_condition_code",
                "weather_id"
            )
        val conditionText: String? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readString(
                cursor,
                "COL_WEATHER_WEATHER_TEXT",
                "weather_text",
                "condition_text",
                "condition",
                "description",
                "state",
                "icon_text"
            )

        if (explicitCode != null && com.BalancedLight.WindyWeather.SamsungWeatherRepository.isOpenMeteoCode(
                explicitCode
            )
        ) {
            return explicitCode
        }
        if (conditionText != null && !conditionText.isEmpty()) {
            val mapped: Int =
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.mapConditionTextToOpenMeteo(
                    conditionText
                )
            if (mapped != WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                return mapped
            }
        }
        if (explicitCode != null) {
            val mapped: Int =
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.mapSamsungConditionToOpenMeteo(
                    explicitCode,
                    conditionText
                )
            if (mapped != WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                return mapped
            }
        }

        val iconCode: Int? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readRoundedInt(
                cursor,
                "COL_WEATHER_CONVERTED_ICON_NUM",
                "converted_icon_num",
                "converted_icon",
                "legacy_icon",
                "legacy_icon_num",
                "COL_WEATHER_ICON_NUM",
                "icon_num",
                "weather_icon",
                "condition_icon",
                "icon"
            )
        if (iconCode != null) {
            val mapped: Int =
                com.BalancedLight.WindyWeather.SamsungWeatherRepository.mapSamsungConditionToOpenMeteo(
                    iconCode,
                    conditionText
                )
            if (mapped != WeatherSnapshot.UNKNOWN_WEATHER_CODE) {
                return mapped
            }
        }
        return null
    }

    private fun mapSamsungConditionToOpenMeteo(code: Int, conditionText: String?): Int {
        if (com.BalancedLight.WindyWeather.SamsungWeatherRepository.isOpenMeteoCode(code)) {
            return code
        }
        when (code) {
            1, 2, 30, 33, 34 -> return 0
            3, 4, 5, 35, 36 -> return 1
            6, 7, 8, 38 -> return 3
            11 -> return 45
            12, 13, 14, 18, 39, 40 -> return 61
            15, 16, 17, 41, 42 -> return 95
            19, 20, 21, 22, 23, 24, 26 -> return 73
            25, 29, 43, 44 -> return 66
            else -> {
                if (conditionText != null && !conditionText.isEmpty()) {
                    return com.BalancedLight.WindyWeather.SamsungWeatherRepository.mapConditionTextToOpenMeteo(
                        conditionText
                    )
                }
                return WeatherSnapshot.UNKNOWN_WEATHER_CODE
            }
        }
    }

    private fun isOpenMeteoCode(code: Int): Boolean {
        when (code) {
            0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99 -> return true
            else -> return false
        }
    }

    private fun mapConditionTextToOpenMeteo(text: String): Int {
        val normalized: String = text.lowercase(Locale.US)
        if (normalized.contains("thunder")) {
            return 95
        }
        if (normalized.contains("sleet") || normalized.contains("freezing rain") || normalized.contains(
                "ice"
            )
        ) {
            return 66
        }
        if (normalized.contains("snow") || normalized.contains("flurr")) {
            return 73
        }
        if (normalized.contains("shower")) {
            return 80
        }
        if (normalized.contains("rain") || normalized.contains("drizzle")) {
            return 61
        }
        if (normalized.contains("fog") || normalized.contains("mist") || normalized.contains("haze")) {
            return 45
        }
        if (normalized.contains("partly")) {
            return 1
        }
        if (normalized.contains("overcast") || normalized.contains("cloudy") || normalized.contains(
                "cloud"
            )
        ) {
            return 3
        }
        if (normalized.contains("clear") || normalized.contains("sunny")) {
            return 0
        }
        return WeatherSnapshot.UNKNOWN_WEATHER_CODE
    }

    private fun readRoundedInt(cursor: Cursor, vararg columns: String?): Int? {
        val value: Double? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readDouble(cursor, *columns)
        if (value == null) {
            return null
        }
        return java.lang.Math.round(value).toInt()
    }

    private fun readFloat(cursor: Cursor, vararg columns: String?): Float? {
        val value: Double? =
            com.BalancedLight.WindyWeather.SamsungWeatherRepository.readDouble(cursor, *columns)
        if (value == null) {
            return null
        }
        return value.toFloat()
    }

    private fun readDouble(cursor: Cursor, vararg columns: String?): Double? {
        val index: Int = com.BalancedLight.WindyWeather.SamsungWeatherRepository.findColumnIndex(
            cursor,
            *columns
        )
        if (index < 0 || cursor.isNull(index)) {
            return null
        }
        try {
            val type: Int = cursor.getType(index)
            if (type == Cursor.FIELD_TYPE_INTEGER || type == Cursor.FIELD_TYPE_FLOAT) {
                return cursor.getDouble(index)
            }
            if (type == Cursor.FIELD_TYPE_STRING) {
                val text: String? = cursor.getString(index)
                if (text == null || text.trim().isEmpty()) {
                    return null
                }
                return java.lang.Double.parseDouble(text.trim())
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun readString(cursor: Cursor, vararg columns: String?): String? {
        val index: Int = com.BalancedLight.WindyWeather.SamsungWeatherRepository.findColumnIndex(
            cursor,
            *columns
        )
        if (index < 0 || cursor.isNull(index)) {
            return null
        }
        try {
            val value: String? = cursor.getString(index)
            if (value == null) {
                return null
            }
            val trimmed: String = value.trim()
            return if (trimmed.isEmpty()) null else trimmed
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun readTimestampMs(cursor: Cursor, vararg columns: String?): Long {
        val index: Int = com.BalancedLight.WindyWeather.SamsungWeatherRepository.findColumnIndex(
            cursor,
            *columns
        )
        if (index < 0 || cursor.isNull(index)) {
            return 0L
        }
        try {
            val raw: Long
            val type: Int = cursor.getType(index)
            if (type == Cursor.FIELD_TYPE_INTEGER) {
                raw = cursor.getLong(index)
            } else {
                val text: String? = cursor.getString(index)
                if (text == null || text.trim().isEmpty()) {
                    return 0L
                }
                raw = java.lang.Long.parseLong(text.trim())
            }
            if (raw < 100000000000L) {
                return raw * 1000L
            }
            return raw
        } catch (ignored: Exception) {
            return 0L
        }
    }

    private fun readTimeHHmm(cursor: Cursor, vararg columns: String?): Int? {
        val index: Int = com.BalancedLight.WindyWeather.SamsungWeatherRepository.findColumnIndex(
            cursor,
            *columns
        )
        if (index < 0 || cursor.isNull(index)) {
            return null
        }
        try {
            val type: Int = cursor.getType(index)
            if (type == Cursor.FIELD_TYPE_INTEGER) {
                val raw: Long = cursor.getLong(index)
                if (raw > 100000000000L) {
                    return com.BalancedLight.WindyWeather.SamsungWeatherRepository.toHHmmFromMs(raw)
                }
                if (raw > 1000000000L) {
                    return com.BalancedLight.WindyWeather.SamsungWeatherRepository.toHHmmFromMs(raw * 1000L)
                }
                val asInt = raw.toInt()
                if (asInt >= 0 && asInt <= 2359) {
                    return asInt
                }
            }
            val text: String? = cursor.getString(index)
            if (text == null || text.trim().isEmpty()) {
                return null
            }
            return com.BalancedLight.WindyWeather.SamsungWeatherRepository.parseTimeText(text.trim())
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun toHHmmFromMs(epochMs: Long): Int {
        val calendar: java.util.Calendar = java.util.Calendar.getInstance()
        calendar.setTimeInMillis(epochMs)
        return (calendar.get(java.util.Calendar.HOUR_OF_DAY) * 100) + calendar.get(java.util.Calendar.MINUTE)
    }

    private fun parseTimeText(text: String): Int? {
        var value = text
        val tMarker: Int = value.lastIndexOf('T')
        if (tMarker >= 0 && tMarker + 1 < value.length) {
            value = value.substring(tMarker + 1)
        }
        val spaceMarker: Int = value.indexOf(' ')
        if (spaceMarker > 0) {
            value = value.substring(0, spaceMarker)
        }
        if (value.contains(":")) {
            val parts: List<String> = value.split(":")
            if (parts.size >= 2) {
                try {
                    val hour: Int = java.lang.Integer.parseInt(parts[0])
                    val minute: Int = java.lang.Integer.parseInt(parts[1])
                    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                        return (hour * 100) + minute
                    }
                } catch (ignored: NumberFormatException) {
                }
            }
        }
        try {
            val numeric: Long = java.lang.Long.parseLong(value)
            if (numeric > 100000000000L) {
                return com.BalancedLight.WindyWeather.SamsungWeatherRepository.toHHmmFromMs(numeric)
            }
            if (numeric > 1000000000L) {
                return com.BalancedLight.WindyWeather.SamsungWeatherRepository.toHHmmFromMs(numeric * 1000L)
            }
            if (numeric >= 0 && numeric <= 2359) {
                return numeric.toInt()
            }
        } catch (ignored: NumberFormatException) {
        }
        return null
    }

    private fun findColumnIndex(cursor: Cursor, vararg columns: String?): Int {
        val names: Array<String?> = cursor.getColumnNames()
        for (candidate in columns) {
            if (candidate == null) {
                continue
            }
            for (i in names.indices) {
                if (candidate.equals(names[i])) {
                    return i
                }
            }
        }
        return -1
    }

    internal class SamsungSnapshot(
        weatherCode: Int?,
        currentTempC: Int?,
        highTempC: Int?,
        lowTempC: Int?,
        humidityPercent: Int?,
        windSpeedKmh: Float?,
        sunriseTime: Int?,
        sunsetTime: Int?,
        cityName: String?,
        lastUpdatedMs: Long
    ) {
        val weatherCode: Int?
        val currentTempC: Int?
        val highTempC: Int?
        val lowTempC: Int?
        val humidityPercent: Int?
        val windSpeedKmh: Float?
        val sunriseTime: Int?
        val sunsetTime: Int?
        val cityName: String?
        val lastUpdatedMs: Long

        init {
            this.weatherCode = weatherCode
            this.currentTempC = currentTempC
            this.highTempC = highTempC
            this.lowTempC = lowTempC
            this.humidityPercent = humidityPercent
            this.windSpeedKmh = windSpeedKmh
            this.sunriseTime = sunriseTime
            this.sunsetTime = sunsetTime
            this.cityName = cityName
            this.lastUpdatedMs = lastUpdatedMs
        }

        fun hasAnyData(): Boolean {
            return weatherCode != null || currentTempC != null || highTempC != null || lowTempC != null || humidityPercent != null || windSpeedKmh != null || sunriseTime != null || sunsetTime != null || (cityName != null && !cityName.isEmpty())
        }

        val isCompleteForOverride: Boolean
            get() = weatherCode != null && currentTempC != null && highTempC != null && lowTempC != null
    }
}
