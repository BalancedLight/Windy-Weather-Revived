package com.BalancedLight.WindyWeather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Resolves the daylight-dependent visuals from the local clock and the weather provider's
 * HHmm sunrise/sunset values.  It intentionally has no Android dependencies so its boundary
 * behaviour can be covered by unit tests.
 *
 * The sky colour is driven by [State.skyPosition], a continuous cycle position that runs
 * 0 -> 4 across a day and never jumps:
 *
 *     0 = night, 1 = dawn peak (exactly sunrise), 2 = day, 3 = dusk peak (exactly sunset), 4 = 0
 *
 * Both twilight bands are centred on their event, so the most colourful moment lands on the
 * sunrise/sunset instant itself rather than an hour to one side of it.
 */
internal object TwilightTimeline {
    const val TWILIGHT_WINDOW_MINUTES = 60
    const val TWILIGHT_HALF_WINDOW_MINUTES = 45
    const val CELESTIAL_GAP_MINUTES = 30
    const val MINUTES_PER_DAY = 1440.0f
    const val HORIZON_CENTER_Y = -5.0f
    const val BODY_ARC_CREST_Y = 7.0f

    const val SKY_NIGHT = 0.0f
    const val SKY_DAWN = 1.0f
    const val SKY_DAY = 2.0f
    const val SKY_DUSK = 3.0f
    const val SKY_CYCLE = 4.0f

    /** How much of the star field is still visible at the sunrise/sunset instant. */
    const val STARS_ALPHA_AT_EVENT = 0.35f

    enum class SkyPhase {
        NIGHT,
        MORNING,
        DAY,
        SUNSET
    }

    data class Rgb(val red: Float, val green: Float, val blue: Float)

    data class State(
        val phase: SkyPhase,
        val skyPosition: Float,
        val daylightProgress: Float?,
        val nightProgress: Float?,
        val hasValidDaylightData: Boolean
    ) {
        /**
         * Distance from night measured along the cycle: 0 at deep night, 1 at the sunrise or
         * sunset instant, 2 at midday.  Both halves of the day collapse onto the same curve.
         */
        val nightDistance: Float
            get() = min(skyPosition, SKY_CYCLE - skyPosition)

        /**
         * Full strength through the night, [STARS_ALPHA_AT_EVENT] at sunrise/sunset, gone by the
         * time the twilight band closes.
         */
        val twilightStarsAlpha: Float
            get() {
                val distance = nightDistance
                return if (distance <= 1.0f) {
                    1.0f - ((1.0f - STARS_ALPHA_AT_EVENT) * smoothstep(distance))
                } else {
                    STARS_ALPHA_AT_EVENT * (1.0f - smoothstep(distance - 1.0f))
                }
            }

        /** Foreground-light fade, peaking at the sunrise/sunset instant. */
        val twilightTintStrength: Float
            get() = (1.0f - abs(nightDistance - 1.0f)).coerceIn(0.0f, 1.0f)

        /**
         * Legacy amber tint retained for timeline-only callers.  The renderer uses
         * [SkyPalette.foregroundTint] so its foreground hue matches the daily sky variation.
         */
        val twilightTint: Rgb
            get() = Rgb(
                red = 1.0f,
                green = 1.0f - (0.18f * twilightTintStrength),
                blue = 1.0f - (0.30f * twilightTintStrength)
            )
    }

    fun resolve(nowMinutes: Int, sunriseHhmm: Int, sunsetHhmm: Int): State =
        resolve(nowMinutes.toFloat(), sunriseHhmm, sunsetHhmm)

    /**
     * Sub-minute overload.  The sky gradient moves continuously, so sampling the clock only once
     * a minute would step it several levels at a time; callers on the render path pass fractional
     * minutes so the ramp stays smooth.
     */
    fun resolve(nowMinutes: Float, sunriseHhmm: Int, sunsetHhmm: Int): State {
        val sunriseMinutes = hhmmToMinutes(sunriseHhmm) ?: return fallbackState(nowMinutes)
        val sunsetMinutes = hhmmToMinutes(sunsetHhmm) ?: return fallbackState(nowMinutes)
        if (sunsetMinutes - sunriseMinutes <= TWILIGHT_WINDOW_MINUTES * 2) {
            return fallbackState(nowMinutes)
        }

        val current = nowMinutes.coerceIn(0.0f, MINUTES_PER_DAY - 0.0001f)
        val half = twilightHalfWindow(sunriseMinutes, sunsetMinutes).toFloat()
        val daylightProgress = ((current - sunriseMinutes) /
            (sunsetMinutes - sunriseMinutes).toFloat()).coerceIn(0.0f, 1.0f)
        val isNight = current < sunriseMinutes || current >= sunsetMinutes

        return State(
            phase = phaseFor(current, sunriseMinutes, sunsetMinutes, half),
            skyPosition = skyPosition(current, sunriseMinutes, sunsetMinutes, half),
            daylightProgress = if (isNight) null else daylightProgress,
            nightProgress = if (isNight) moonProgress(current, sunriseMinutes, sunsetMinutes) else null,
            hasValidDaylightData = true
        )
    }

    /**
     * Half-width of each twilight band, shrunk on short days or short nights so the dawn and
     * dusk bands can never overlap each other.
     */
    fun twilightHalfWindow(sunriseMinutes: Int, sunsetMinutes: Int): Int {
        val dayLength = sunsetMinutes - sunriseMinutes
        val nightLength = 1440 - dayLength
        return min(
            TWILIGHT_HALF_WINDOW_MINUTES,
            min((dayLength - 1) / 2, (nightLength - 1) / 2)
        ).coerceAtLeast(1)
    }

    private fun skyPosition(
        current: Float,
        sunriseMinutes: Int,
        sunsetMinutes: Int,
        half: Float
    ): Float {
        return when {
            current < sunriseMinutes - half -> SKY_NIGHT
            current <= sunriseMinutes + half -> ((current - sunriseMinutes) / half) + SKY_DAWN
            current < sunsetMinutes - half -> SKY_DAY
            current <= sunsetMinutes + half -> SKY_DUSK + ((current - sunsetMinutes) / half)
            else -> SKY_NIGHT
        }.coerceIn(SKY_NIGHT, SKY_CYCLE)
    }

    private fun phaseFor(
        current: Float,
        sunriseMinutes: Int,
        sunsetMinutes: Int,
        half: Float
    ): SkyPhase {
        return when {
            current < sunriseMinutes - half -> SkyPhase.NIGHT
            current <= sunriseMinutes + half -> SkyPhase.MORNING
            current < sunsetMinutes - half -> SkyPhase.DAY
            current <= sunsetMinutes + half -> SkyPhase.SUNSET
            else -> SkyPhase.NIGHT
        }
    }

    fun arcHeight(progress: Float): Float = sin(progress.coerceIn(0.0f, 1.0f) * PI).toFloat()

    fun bodyArcY(progress: Float): Float {
        val easedHeight = arcHeight(progress)
        return HORIZON_CENTER_Y + ((BODY_ARC_CREST_Y - HORIZON_CENTER_Y) * easedHeight * easedHeight)
    }

    fun smoothstep(t: Float): Float {
        val clamped = t.coerceIn(0.0f, 1.0f)
        return clamped * clamped * (3.0f - (2.0f * clamped))
    }

    fun hhmmToMinutes(hhmm: Int): Int? {
        val hour = hhmm / 100
        val minute = hhmm % 100
        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }
        return (hour * 60) + minute
    }

    private fun fallbackState(nowMinutes: Float): State {
        val clamped = nowMinutes.coerceIn(0.0f, MINUTES_PER_DAY - 0.0001f)
        return if (clamped >= 360.0f && clamped < 1080.0f) {
            State(SkyPhase.DAY, SKY_DAY, null, null, false)
        } else {
            State(SkyPhase.NIGHT, SKY_NIGHT, null, null, false)
        }
    }

    private fun moonProgress(current: Float, sunriseMinutes: Int, sunsetMinutes: Int): Float? {
        val timelineCurrent =
            if (current < sunriseMinutes) current + MINUTES_PER_DAY else current
        val moonStart = (sunsetMinutes + CELESTIAL_GAP_MINUTES).toFloat()
        val moonEnd = (sunriseMinutes + 1440 - CELESTIAL_GAP_MINUTES).toFloat()
        if (timelineCurrent < moonStart || timelineCurrent >= moonEnd) {
            return null
        }
        return ((timelineCurrent - moonStart) / (moonEnd - moonStart)).coerceIn(0.0f, 1.0f)
    }
}
