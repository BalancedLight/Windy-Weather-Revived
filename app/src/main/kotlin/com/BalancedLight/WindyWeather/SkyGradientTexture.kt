package com.BalancedLight.WindyWeather

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * The sky backdrop, generated at runtime instead of decoded from a bitmap.
 *
 * The gradient is purely vertical, so the texture only needs to be a few pixels wide: at
 * [DEFAULT_HEIGHT] rows it costs 32 KB against the 16 MB a 2048x2048 sky bitmap occupied -- plus
 * the 16 MB transient decode buffer that came with every scene reload.
 */
internal class SkyGradientTexture : GeneratedQuad() {
    private val stops = FloatArray(SkyPalette.COMPONENT_COUNT)
    private val tangents = FloatArray(SkyPalette.COMPONENT_COUNT)
    private val rgb = FloatArray(3)

    private var pixels: ByteBuffer? = null
    private var height = 0
    private var lastFamilyOrdinal = NO_KEY
    private var lastQuantisedPosition = NO_KEY
    private var lastVariationKey = NO_KEY
    private var lastVariationStrengthKey = NO_KEY

    override fun release(gl: GL10?) {
        super.release(gl)
        this.lastFamilyOrdinal = NO_KEY
        this.lastQuantisedPosition = NO_KEY
        this.lastVariationKey = NO_KEY
        this.lastVariationStrengthKey = NO_KEY
    }

    /**
     * Rebuilds and uploads the gradient if the scene family, daily variation, or [skyPosition]
     * has moved far enough to matter.
     *
     * [skyPosition] is quantised to [STEPS_PER_KEYFRAME] steps per keyframe segment, which holds
     * every regeneration under one 8-bit level of change while keeping the rebuild rate to roughly
     * once every five seconds during twilight -- and to zero outside it, where the position is
     * constant.
     */
    fun ensureUpdated(
        gl: GL10?,
        family: SkyPalette.Family,
        skyPosition: Float,
        variation: SkyPalette.DailyVariation,
        variationStrength: Float,
        maxTextureSize: Int
    ) {
        if (gl == null) {
            return
        }
        val targetHeight = resolveSize(DEFAULT_HEIGHT, MIN_HEIGHT, maxTextureSize)
        val quantised = (skyPosition * STEPS_PER_KEYFRAME).roundToInt()
        val strengthKey = (variationStrength.coerceIn(0.0f, 1.0f) * 1000.0f).roundToInt()
        if (this.isReady && this.height == targetHeight && this.lastFamilyOrdinal == family.ordinal
            && this.lastQuantisedPosition == quantised && this.lastVariationKey == variation.cacheKey
            && this.lastVariationStrengthKey == strengthKey
        ) {
            return
        }

        if (this.height != targetHeight || this.pixels == null) {
            this.height = targetHeight
            this.pixels = ByteBuffer
                .allocateDirect(WIDTH * targetHeight * 4)
                .order(ByteOrder.nativeOrder())
            // The existing texture storage is now the wrong size.
            discardStorage(gl)
        }

        val buffer = this.pixels ?: return
        SkyPalette.blendInto(family, skyPosition, variation, variationStrength, this.stops)
        SkyPalette.computeTangents(this.stops, this.tangents)
        writePixels(buffer, targetHeight)

        if (!this.textureAllocated) {
            allocate(gl, WIDTH, targetHeight, buffer)
        } else {
            replace(gl, WIDTH, targetHeight, buffer)
        }

        this.lastFamilyOrdinal = family.ordinal
        this.lastQuantisedPosition = quantised
        this.lastVariationKey = variation.cacheKey
        this.lastVariationStrengthKey = strengthKey
        this.isReady = true
    }

    private fun writePixels(buffer: ByteBuffer, rows: Int) {
        buffer.clear()
        for (y in 0 until rows) {
            val v = (y + 0.5f) / rows
            SkyPalette.sampleInto(this.stops, this.tangents, v, this.rgb)
            val red = ditheredByte(SkyPalette.linearToSrgb(this.rgb[0]), y, 0)
            val green = ditheredByte(SkyPalette.linearToSrgb(this.rgb[1]), y, 1)
            val blue = ditheredByte(SkyPalette.linearToSrgb(this.rgb[2]), y, 2)
            for (x in 0 until WIDTH) {
                buffer.put(red)
                buffer.put(green)
                buffer.put(blue)
                buffer.put(OPAQUE)
            }
        }
        buffer.position(0)
    }

    /**
     * Rounds an sRGB component to 8 bits with +/-1 LSB triangular-PDF noise.
     *
     * Without this the sky contours badly: the clear-day blue channel climbs only ~65 levels
     * across the whole visible band, which is one hard step every ~37 screen pixels.  `GL_DITHER`
     * cannot help -- an 8-bit texture feeding an 8-bit framebuffer has no spare precision to
     * dither with, so the noise has to be baked in here.
     *
     * The noise is a pure function of (row, channel), so the pattern is identical on every
     * regeneration and stays put while the gradient moves underneath it.  A re-seeded pattern
     * would visibly crawl each time the sky updated.
     */
    private fun ditheredByte(srgb: Float, y: Int, channel: Int): Byte {
        val scaled = srgb * 255.0f
        val seed = (y * 3) + channel
        val noise = hashToUnit(seed) + hashToUnit(seed xor MIX) - 1.0f
        return (scaled + noise).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun hashToUnit(seed: Int): Float {
        var h = (seed * 1664525) + 1013904223
        h = h xor (h ushr 16)
        h *= HASH_MULTIPLIER
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFFFF) / 16777216.0f
    }

    companion object {
        /**
         * Four identical columns.  One would do for a vertical gradient, but four keeps each row
         * at 16 bytes -- satisfying the default `GL_UNPACK_ALIGNMENT` of 4 -- and avoids driver
         * corner cases around degenerate texture widths.
         */
        const val WIDTH = 4

        /**
         * Only ~78% of the texture is ever on screen, so 2048 rows land at roughly 1.5 screen
         * pixels per texel on a tall phone: the coarsest resolution at which the baked dither
         * still survives magnification.
         */
        const val DEFAULT_HEIGHT = 2048
        const val MIN_HEIGHT = 256

        /** Quantisation steps per keyframe segment; see [ensureUpdated]. */
        const val STEPS_PER_KEYFRAME = 256

        private const val NO_KEY = Int.MIN_VALUE
        private val OPAQUE = 0xFF.toByte()
        private val MIX = 0x9E3779B9.toInt()
        private val HASH_MULTIPLIER = 0x85EBCA6B.toInt()
    }
}
