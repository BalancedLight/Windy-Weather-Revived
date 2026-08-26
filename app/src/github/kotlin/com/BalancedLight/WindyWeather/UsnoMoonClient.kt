package com.BalancedLight.WindyWeather

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class UsnoMoonClient {
    @Throws(IOException::class)
    fun fetchMoonInfo(dateYYYYMMDD: String?, lat: Double, lon: Double, tz: String?): MoonInfo {
        val coords = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        val endpoint = "https://aa.usno.navy.mil/api/rstt/oneday" +
            "?date=" + URLEncoder.encode(dateYYYYMMDD, "UTF-8") +
            "&coords=" + URLEncoder.encode(coords, "UTF-8") +
            "&tz=" + URLEncoder.encode(tz, "UTF-8")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "WindyWeather/1.1")
        try {
            val code = connection.responseCode
            val response = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = response?.let(::readFully).orEmpty()
            if (code !in 200..299) throw IOException("USNO HTTP $code")
            return parseMoonInfo(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMoonInfo(body: String): MoonInfo {
        val root = JSONObject(body)
        root.optString("error", "").takeIf { it.isNotEmpty() }?.let {
            throw IOException("USNO error: $it")
        }
        val data = root.optJSONObject("properties")?.optJSONObject("data")
            ?: throw IOException("USNO response missing properties.data")
        val phase = data.optString("curphase", "").trim()
        if (phase.isEmpty()) throw IOException("USNO response missing curphase")
        return MoonInfo(phase, parsePercent(data.opt("fracillum")))
    }

    private fun parsePercent(value: Any?): Double {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().removeSuffix("%").trim().toDoubleOrNull()
            else -> null
        } ?: throw IOException("USNO response missing fracillum")
        val percent = if (number <= 1.0) number * 100.0 else number
        return percent.coerceIn(0.0, 100.0)
    }

    private fun readFully(inputStream: InputStream): String =
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                var line: String?
                while (reader.readLine().also { line = it } != null) append(line)
            }
        }
}
