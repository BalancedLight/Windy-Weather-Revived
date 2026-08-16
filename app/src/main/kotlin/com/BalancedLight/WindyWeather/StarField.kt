package com.BalancedLight.WindyWeather

import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Generates the night-sky star field that used to be baked into `sky_02.jpg`.
 *
 * The original bitmap carried roughly a thousand star pixels spread across the top ~66% of the
 * screen; a plain gradient loses all of them.  This rebuilds an equivalent field procedurally so
 * it can be drawn as its own layer and faded independently through twilight.
 *
 * Deterministic: the same [SEED] produces the same sky on every launch and every device, so the
 * stars do not shuffle when the wallpaper reloads.
 *
 * Output is premultiplied RGBA, matching `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` and the
 * codebase convention of `shortdraw(gl, alpha, alpha)`.
 *
 * Android-free so it can be unit tested.
 */
internal object StarField {
    const val SEED = 0x5EED5A11L

    /** Candidate stars considered before the density falloff rejects any. */
    const val CANDIDATE_COUNT = 260

    /** Above this V the field is at full density (V matches screen V; 0 is the top). */
    const val FULL_DENSITY_V = 0.28f

    /**
     * Density reaches zero here.  The baked field in sky_02 stopped at image V 0.55, which maps to
     * 66% of screen height, so this keeps roughly the same horizon cutoff.
     */
    const val HORIZON_V = 0.68f

    /**
     * Fills [dst] with a transparent field of stars.  [dst] must hold `width * height * 4` bytes.
     * Written with absolute puts so no large scratch array is allocated on the GL thread.
     */
    fun generateInto(dst: ByteBuffer, width: Int, height: Int) {
        val required = width * height * 4
        require(dst.capacity() >= required) { "buffer too small for ${width}x$height" }

        for (i in 0 until required) {
            dst.put(i, 0)
        }

        var state = SEED
        fun next(): Float {
            state = (state * 6364136223846793005L) + 1442695040888963407L
            return (((state ushr 24) and 0xFFFFFF).toFloat()) / 16777216.0f
        }

        for (candidate in 0 until CANDIDATE_COUNT) {
            val xUnit = next()
            val yUnit = next()
            val density = densityAt(yUnit)
            if (next() > density) {
                continue
            }

            // Magnitude is heavily weighted toward faint so a handful of bright stars stand out;
            // multiplying by density makes stars near the horizon dim as well as thin out.
            val brightness = next().pow(2.2f) * (0.55f + (0.45f * density))
            val warmth = next()
            val shapeRoll = next()

            val x = (xUnit * width).toInt().coerceIn(0, width - 1)
            val y = (yUnit * height).toInt().coerceIn(0, height - 1)

            plot(dst, width, height, x, y, brightness, warmth)
            if (shapeRoll > 0.78f) {
                val neighbour = brightness * 0.30f
                plot(dst, width, height, x - 1, y, neighbour, warmth)
                plot(dst, width, height, x + 1, y, neighbour, warmth)
                plot(dst, width, height, x, y - 1, neighbour, warmth)
                plot(dst, width, height, x, y + 1, neighbour, warmth)
            }
            if (shapeRoll > 0.96f) {
                val corner = brightness * 0.12f
                plot(dst, width, height, x - 1, y - 1, corner, warmth)
                plot(dst, width, height, x + 1, y - 1, corner, warmth)
                plot(dst, width, height, x - 1, y + 1, corner, warmth)
                plot(dst, width, height, x + 1, y + 1, corner, warmth)
            }
        }

        dst.position(0)
    }

    /** Full density near the zenith, smoothly falling to nothing by [HORIZON_V]. */
    fun densityAt(v: Float): Float {
        if (v <= FULL_DENSITY_V) {
            return 1.0f
        }
        if (v >= HORIZON_V) {
            return 0.0f
        }
        val t = (v - FULL_DENSITY_V) / (HORIZON_V - FULL_DENSITY_V)
        return 1.0f - TwilightTimeline.smoothstep(t)
    }

    private fun plot(
        dst: ByteBuffer,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        brightness: Float,
        warmth: Float
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return
        }
        val alpha = brightness.coerceIn(0.0f, 1.0f)
        if (alpha <= 0.0f) {
            return
        }
        // Scatter slightly between cool blue-white and warm white, premultiplied by alpha.
        val red = alpha * (0.88f + (0.12f * warmth))
        val green = alpha * (0.92f + (0.08f * warmth))
        val blue = alpha * (1.0f - (0.10f * warmth))

        val index = ((y * width) + x) * 4
        // Additive accumulation so overlapping halos brighten rather than overwrite.
        addByte(dst, index, red)
        addByte(dst, index + 1, green)
        addByte(dst, index + 2, blue)
        addByte(dst, index + 3, alpha)
    }

    private fun addByte(dst: ByteBuffer, index: Int, addition: Float) {
        val current = dst.get(index).toInt() and 0xFF
        val updated = (current + (addition * 255.0f).roundToInt()).coerceIn(0, 255)
        dst.put(index, updated.toByte())
    }
}
