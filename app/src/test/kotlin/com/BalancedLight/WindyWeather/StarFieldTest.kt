package com.BalancedLight.WindyWeather

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarFieldTest {
    private val size = 256

    private fun generate(): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(size * size * 4)
            .order(ByteOrder.nativeOrder())
        StarField.generateInto(buffer, size, size)
        return buffer
    }

    private fun alphaAt(buffer: ByteBuffer, x: Int, y: Int): Int =
        buffer.get((((y * size) + x) * 4) + 3).toInt() and 0xFF

    @Test
    fun `generates the same field every time`() {
        val first = generate()
        val second = generate()

        for (i in 0 until size * size * 4) {
            assertEquals("byte $i", first.get(i), second.get(i))
        }
    }

    @Test
    fun `puts no stars below the horizon cutoff`() {
        val buffer = generate()
        val firstRowBelowHorizon = Math.ceil((StarField.HORIZON_V * size).toDouble()).toInt()

        for (y in firstRowBelowHorizon until size) {
            for (x in 0 until size) {
                assertEquals("star at ($x, $y)", 0, alphaAt(buffer, x, y))
            }
        }
    }

    @Test
    fun `fills the upper sky with stars`() {
        val buffer = generate()
        var lit = 0
        for (y in 0 until (size * 0.28f).toInt()) {
            for (x in 0 until size) {
                if (alphaAt(buffer, x, y) > 0) {
                    lit++
                }
            }
        }
        assertTrue("expected stars near the zenith, found $lit", lit > 20)
    }

    @Test
    fun `thins out toward the horizon`() {
        val buffer = generate()

        fun litBetween(fromV: Float, toV: Float): Int {
            var lit = 0
            for (y in (fromV * size).toInt() until (toV * size).toInt()) {
                for (x in 0 until size) {
                    if (alphaAt(buffer, x, y) > 0) {
                        lit++
                    }
                }
            }
            return lit
        }

        // Equal-height bands: the one nearer the horizon must be sparser.
        val upper = litBetween(0.10f, 0.30f)
        val lower = litBetween(0.45f, 0.65f)
        assertTrue("upper=$upper lower=$lower", lower < upper)
    }

    @Test
    fun `density falls off monotonically`() {
        assertEquals(1.0f, StarField.densityAt(0.0f), 0.0001f)
        assertEquals(1.0f, StarField.densityAt(StarField.FULL_DENSITY_V), 0.0001f)
        assertEquals(0.0f, StarField.densityAt(StarField.HORIZON_V), 0.0001f)
        assertEquals(0.0f, StarField.densityAt(1.0f), 0.0001f)

        var previous = 1.0f
        for (step in 0..200) {
            val current = StarField.densityAt(step / 200.0f)
            assertTrue("density rose at step $step", current <= previous + 0.0001f)
            previous = current
        }
    }

    @Test
    fun `stays premultiplied so it composites correctly`() {
        val buffer = generate()
        for (i in 0 until size * size) {
            val base = i * 4
            val alpha = buffer.get(base + 3).toInt() and 0xFF
            for (channel in 0 until 3) {
                val value = buffer.get(base + channel).toInt() and 0xFF
                assertTrue(
                    "pixel $i channel $channel = $value exceeds alpha $alpha",
                    value <= alpha
                )
            }
        }
    }
}
