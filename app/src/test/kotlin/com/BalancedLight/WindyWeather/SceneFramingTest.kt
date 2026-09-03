package com.BalancedLight.WindyWeather

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneFramingTest {
    private val portrait = 1080.0f / 2400.0f
    private val landscape16x9 = 1920.0f / 1080.0f
    private val landscape20x9 = 2400.0f / 1080.0f
    private val landscapeUltrawide = 3.0f

    private val landNearHalfWidth = 4.0f * 3.5f
    private val landFarHalfWidth = 4.0f * 3.6f
    private val lawnHalfWidth = 4.0f * 3.5f
    private val groundCentreX = 1.5f

    private fun coveredHalfWidth(tilesEachSide: Int, quadHalfWidth: Float): Float =
        ((2 * tilesEachSide) + 1) * quadHalfWidth

    @Test
    fun `vertical extent does not depend on orientation`() {
        val portraitHeight = SceneFraming.halfHeight(23.0f)
        assertEquals(9.5269f, portraitHeight, 0.001f)
        assertEquals(portraitHeight, SceneFraming.halfHeight(23.0f), 0.0f)
        assertEquals(
            "half width must follow the aspect ratio",
            portraitHeight * landscape16x9,
            SceneFraming.halfWidth(23.0f, landscape16x9),
            0.001f
        )
    }

    @Test
    fun `portrait ground bands still draw as a single quad`() {
        for (halfWidth in listOf(landNearHalfWidth, landFarHalfWidth, lawnHalfWidth)) {
            assertEquals(
                "portrait must not gain tiles",
                0,
                SceneFraming.tilesEachSide(23.0f, portrait, halfWidth, groundCentreX)
            )
        }
    }

    @Test
    fun `landscape ground bands cover the frustum`() {
        val aspects = listOf(landscape16x9, landscape20x9, landscapeUltrawide)
        val bands = listOf(
            23.0f to landNearHalfWidth,
            24.0f to landFarHalfWidth,
            23.0f to lawnHalfWidth
        )
        for (aspect in aspects) {
            for ((depth, halfWidth) in bands) {
                val tiles = SceneFraming.tilesEachSide(depth, aspect, halfWidth, groundCentreX)
                assertTrue("aspect $aspect needs at least one mirrored copy", tiles >= 1)
                val covered = coveredHalfWidth(tiles, halfWidth)
                val required =
                    SceneFraming.halfWidth(depth, aspect) + groundCentreX + SceneFraming.COVERAGE_MARGIN
                assertTrue(
                    "aspect $aspect depth $depth covers $covered but needs $required",
                    covered >= required
                )
            }
        }
    }

    @Test
    fun `tile count grows only when the frustum outruns the band`() {
        val narrow = SceneFraming.tilesEachSide(23.0f, landscape16x9, landNearHalfWidth, groundCentreX)
        val wide = SceneFraming.tilesEachSide(23.0f, 6.0f, landNearHalfWidth, groundCentreX)
        assertEquals(1, narrow)
        assertTrue("a very wide surface needs more copies", wide > narrow)
    }

    @Test
    fun `ground parallax cannot expose an edge`() {
        // getGroundParallaxShift(1.2) sweeps the band across roughly +/- 4.5 world units.
        for (centre in listOf(-4.5f, -1.5f, 0.0f, 1.5f, 4.5f)) {
            val tiles = SceneFraming.tilesEachSide(23.0f, landscape20x9, landNearHalfWidth, centre)
            val covered = coveredHalfWidth(tiles, landNearHalfWidth)
            val required = SceneFraming.halfWidth(23.0f, landscape20x9) + abs(centre) +
                SceneFraming.COVERAGE_MARGIN
            assertTrue("centre $centre covers $covered but needs $required", covered >= required)
        }
    }

    @Test
    fun `portrait keeps the original cloud period`() {
        // The frame-counter construction this replaces travelled 0.025 * 2002 = 50.05 units.
        val span = SceneFraming.cloudSpan(27.0f, portrait, 2.0f * 4.0f)
        assertEquals(50.05f, span, 0.5f)
    }

    @Test
    fun `a cloud wrap always happens off screen`() {
        val depths = listOf(26.0f, 26.9f, 27.0f, 27.5f, 27.8f)
        val halfWidths = listOf(2.0f * 2.8f, 2.0f * 3.4f, 2.0f * 4.0f, 2.0f * 4.4f)
        for (aspect in listOf(portrait, landscape16x9, landscape20x9, landscapeUltrawide)) {
            for (depth in depths) {
                for (cloudHalfWidth in halfWidths) {
                    val leftLimit = SceneFraming.cloudLeftLimit(depth, aspect, cloudHalfWidth)
                    val span = SceneFraming.cloudSpan(depth, aspect, cloudHalfWidth)

                    // Leaving: at the limit the cloud's right edge is at or past the left frustum edge.
                    val rightEdgeAtLimit = leftLimit + SceneFraming.SKY_SHIFT + cloudHalfWidth
                    assertTrue(
                        "aspect $aspect depth $depth still visible at the wrap point",
                        rightEdgeAtLimit <= -SceneFraming.halfWidth(depth, aspect) + 0.001f
                    )

                    // Re-entering: after the wrap the left edge is at or past the right frustum edge.
                    val leftEdgeAfterWrap =
                        (leftLimit + span) + SceneFraming.SKY_SHIFT - cloudHalfWidth
                    assertTrue(
                        "aspect $aspect depth $depth re-enters on screen",
                        leftEdgeAfterWrap >= SceneFraming.halfWidth(depth, aspect) - 0.001f
                    )
                }
            }
        }
    }

    @Test
    fun `the empty gap between appearances stays constant across aspects`() {
        val depth = 27.0f
        val cloudHalfWidth = 2.0f * 4.0f
        for (aspect in listOf(landscape16x9, landscape20x9, landscapeUltrawide)) {
            val span = SceneFraming.cloudSpan(depth, aspect, cloudHalfWidth)
            val traversal = (2.0f * SceneFraming.halfWidth(depth, aspect)) + (2.0f * cloudHalfWidth)
            assertEquals(
                "aspect $aspect gap",
                SceneFraming.CLOUD_GAP,
                span - traversal,
                0.001f
            )
        }
    }

    @Test
    fun `portrait sky keeps its original scale and landscape widens to fit`() {
        val portraitScale = SceneFraming.skyScaleX(30.0f, portrait, 1.0f, 8.0f, 2.0f)
        assertEquals("portrait must not change", 2.0f, portraitScale, 0.0f)

        for (aspect in listOf(landscape16x9, landscape20x9, landscapeUltrawide)) {
            val scale = SceneFraming.skyScaleX(30.0f, aspect, 1.0f, 8.0f, 2.0f)
            val covered = scale * 8.0f
            val required = SceneFraming.halfWidth(30.0f, aspect) + 1.0f
            assertTrue("aspect $aspect covers $covered but needs $required", covered >= required)
        }
    }
}
