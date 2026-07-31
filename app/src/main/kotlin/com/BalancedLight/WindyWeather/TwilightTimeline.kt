package com.BalancedLight.WindyWeather

import kotlin.math.PI
import kotlin.math.sin

/**
 * Resolves the daylight-dependent visuals from the local clock and the weather provider's
 * HHmm sunrise/sunset values.  It intentionally has no Android dependencies so its boundary
 * behaviour can be covered by unit tests.
 */
internal object TwilightTimeline {
    const val TWILIGHT_WINDOW_MINUTES = 60
    const val CELESTIAL_GAP_MINUTES = 30
    const val HORIZON_CENTER_Y = -5.0f
    const val BODY_ARC_CREST_Y = 7.0f

    enum class SkyPhase {
        NIGHT,
        MORNING,
        DAY,
        SUNSET
    }

    data class Rgb(val red: Float, val green: Float, val blue: Float)

    data class State(
        val phase: SkyPhase,
        val daylightProgress: Float?,
        val nightProgress: Float?,
        val twilightProgress: Float,
        val hasValidDaylightData: Boolean
    ) {
        val twilightTint: Rgb
            get() {
                val strength = when (phase) {
                    SkyPhase.MORNING -> 1.0f - twilightProgress
                    SkyPhase.SUNSET -> twilightProgress
                    else -> 0.0f
                }
                return Rgb(
                    red = 1.0f,
                    green = 1.0f - (0.18f * strength),
                    blue = 1.0f - (0.30f * strength)
                )
            }
    }

    fun resolve(nowMinutes: Int, sunriseHhmm: Int, sunsetHhmm: Int): State {
        val sunriseMinutes = hhmmToMinutes(sunriseHhmm) ?: return fallbackState(nowMinutes)
        val sunsetMinutes = hhmmToMinutes(sunsetHhmm) ?: return fallbackState(nowMinutes)
        if (sunsetMinutes - sunriseMinutes <= TWILIGHT_WINDOW_MINUTES * 2) {
            return fallbackState(nowMinutes)
        }

        val current = nowMinutes.coerceIn(0, 1439)
        val daylightProgress = ((current - sunriseMinutes).toFloat() /
            (sunsetMinutes - sunriseMinutes).toFloat()).coerceIn(0.0f, 1.0f)

        return when {
            current < sunriseMinutes -> {
                State(
                    phase = SkyPhase.NIGHT,
                    daylightProgress = null,
                    nightProgress = moonProgress(current, sunriseMinutes, sunsetMinutes),
                    twilightProgress = 0.0f,
                    hasValidDaylightData = true
                )
            }

            current < sunriseMinutes + TWILIGHT_WINDOW_MINUTES -> State(
                phase = SkyPhase.MORNING,
                daylightProgress = daylightProgress,
                nightProgress = null,
                twilightProgress = ((current - sunriseMinutes).toFloat() /
                    TWILIGHT_WINDOW_MINUTES.toFloat()).coerceIn(0.0f, 1.0f),
                hasValidDaylightData = true
            )

            current < sunsetMinutes - TWILIGHT_WINDOW_MINUTES -> State(
                phase = SkyPhase.DAY,
                daylightProgress = daylightProgress,
                nightProgress = null,
                twilightProgress = 0.0f,
                hasValidDaylightData = true
            )

            current < sunsetMinutes -> State(
                phase = SkyPhase.SUNSET,
                daylightProgress = daylightProgress,
                nightProgress = null,
                twilightProgress = ((current - (sunsetMinutes - TWILIGHT_WINDOW_MINUTES)).toFloat() /
                    TWILIGHT_WINDOW_MINUTES.toFloat()).coerceIn(0.0f, 1.0f),
                hasValidDaylightData = true
            )

            else -> {
                State(
                    phase = SkyPhase.NIGHT,
                    daylightProgress = null,
                    nightProgress = moonProgress(current, sunriseMinutes, sunsetMinutes),
                    twilightProgress = 0.0f,
                    hasValidDaylightData = true
                )
            }
        }
    }

    fun arcHeight(progress: Float): Float = sin(progress.coerceIn(0.0f, 1.0f) * PI).toFloat()

    fun bodyArcY(progress: Float): Float {
        val easedHeight = arcHeight(progress)
        return HORIZON_CENTER_Y + ((BODY_ARC_CREST_Y - HORIZON_CENTER_Y) * easedHeight * easedHeight)
    }

    fun hhmmToMinutes(hhmm: Int): Int? {
        val hour = hhmm / 100
        val minute = hhmm % 100
        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }
        return (hour * 60) + minute
    }

    private fun fallbackState(nowMinutes: Int): State {
        return if (nowMinutes.coerceIn(0, 1439) in 360..1079) {
            State(SkyPhase.DAY, null, null, 0.0f, false)
        } else {
            State(SkyPhase.NIGHT, null, null, 0.0f, false)
        }
    }

    private fun moonProgress(current: Int, sunriseMinutes: Int, sunsetMinutes: Int): Float? {
        val timelineCurrent = if (current < sunriseMinutes) current + 1440 else current
        val moonStart = sunsetMinutes + CELESTIAL_GAP_MINUTES
        val moonEnd = sunriseMinutes + 1440 - CELESTIAL_GAP_MINUTES
        if (timelineCurrent < moonStart || timelineCurrent >= moonEnd) {
            return null
        }
        return ((timelineCurrent - moonStart).toFloat() / (moonEnd - moonStart).toFloat())
            .coerceIn(0.0f, 1.0f)
    }
}
