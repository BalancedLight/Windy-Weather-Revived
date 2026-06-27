package com.BalancedLight.WindyWeather

import org.json.JSONException
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
        val coords: String? = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val endpoint = ("https://aa.usno.navy.mil/api/rstt/oneday"
                + "?date=" + URLEncoder.encode(dateYYYYMMDD, "UTF-8")
                + "&coords=" + URLEncoder.encode(coords, "UTF-8")
                + "&tz=" + URLEncoder.encode(tz, "UTF-8"))

        val connection: HttpURLConnection = URL(endpoint).openConnection() as HttpURLConnection
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(10000)
        connection.setRequestMethod("GET")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(
            "User-Agent",
            com.BalancedLight.WindyWeather.UsnoMoonClient.Companion.USER_AGENT
        )

        try {
            val code: Int = connection.getResponseCode()
            val responseStream: InputStream? = if (code >= 200 && code < 300)
                connection.getInputStream()
            else
                connection.getErrorStream()
            val body: String = if (responseStream != null) readFully(responseStream) else ""
            if (code < 200 || code >= 300) {
                throw IOException("USNO HTTP " + code + " " + body)
            }
            return parseMoonInfo(body)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun parseMoonInfo(body: String?): MoonInfo {
        try {
            val root = JSONObject(body ?: "")
            val apiError: String = root.optString("error", "")
            if (!apiError.isEmpty()) {
                throw IOException("USNO error: " + apiError)
            }

            val properties: JSONObject? = root.optJSONObject("properties")
            val data: JSONObject? =
                if (properties != null) properties.optJSONObject("data") else null
            if (data == null) {
                throw IOException("USNO response missing properties.data")
            }

            val phase: String = data.optString("curphase", "").trim()
            if (phase.isEmpty()) {
                throw IOException("USNO response missing curphase")
            }

            val fracIllumRaw: Any? = data.opt("fracillum")
            val fracIllum = parseFracIllumPercent(fracIllumRaw)
            return MoonInfo(phase, fracIllum)
        } catch (e: JSONException) {
            throw IOException("USNO parse failure", e)
        }
    }

    @Throws(IOException::class)
    private fun parseFracIllumPercent(value: Any?): Double {
        if (value is Number) {
            return normalizePercent(value.toDouble())
        }
        if (value is String) {
            var raw: String = value.trim()
            if (raw.endsWith("%")) {
                raw = raw.substring(0, raw.length - 1).trim()
            }
            if (raw.isEmpty()) {
                throw IOException("USNO response missing fracillum")
            }
            try {
                return normalizePercent(java.lang.Double.parseDouble(raw))
            } catch (e: NumberFormatException) {
                throw IOException("USNO fracillum not numeric: " + value, e)
            }
        }
        throw IOException("USNO response missing fracillum")
    }

    private fun normalizePercent(value: Double): Double {
        val percent = if (value <= 1.0) value * 100.0 else value
        if (percent.isNaN() || percent.isInfinite()) {
            return 0.0
        }
        return Math.max(0.0, Math.min(100.0, percent))
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

    companion object {
        private val USER_AGENT = "WindyWeather/1.0"
    }
}
