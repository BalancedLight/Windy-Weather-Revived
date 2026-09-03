package com.BalancedLight.WindyWeather

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.tan

/**
 * Frustum geometry for the wallpaper scene.
 *
 * `gluPerspective(45, aspect, 0.1, 40)` fixes the *vertical* field of view, so the visible world
 * height at a given depth is the same in both orientations and only the width follows the aspect
 * ratio.  At z = 23 that is 5.36 world units of half-width in portrait against 16.94 at 16:9 — a
 * 3.16x difference horizontally and none at all vertically.
 *
 * The landscape branches this replaces tried to cover that by scaling X alone, which stretched
 * every sprite (a rotor disc came out 1.45x wider than tall) and still left the lawn too narrow to
 * reach the turbine bases.  Instead the scene keeps its portrait proportions in both orientations
 * and the extra width is covered by repeating the ground bands and by widening the sky gradient,
 * which has no horizontal detail to distort.
 *
 * Android-free so it can be unit tested.
 */
internal object SceneFraming {
    /** Half of the 45 degree vertical field of view passed to `gluPerspective`. */
    private const val HALF_FOV_RADIANS = 0.39269908169872414

    /** Constant horizontal shift applied to every sky-depth layer. */
    const val SKY_SHIFT = 2.5f

    /** World units a cloud travels per frame unit; unchanged from the original renderer. */
    const val CLOUD_SPEED = 0.025f

    /**
     * Off-screen distance between one cloud leaving on the left and the same slot re-entering on
     * the right.  Chosen so a portrait phone keeps the cadence the original frame-counter
     * construction produced (`0.025 * 2002` = 50.05 world units of travel per cycle).
     */
    const val CLOUD_GAP = 24.0f

    /** Lower bound on a cloud's cycle so portrait keeps its original period. */
    const val MIN_CLOUD_SPAN = 50.05f

    /** Slack added to ground coverage so a rounding error never exposes the frustum edge. */
    const val COVERAGE_MARGIN = 0.5f

    fun halfHeight(zAbs: Float): Float = tan(HALF_FOV_RADIANS).toFloat() * zAbs

    fun halfWidth(zAbs: Float, aspect: Float): Float = halfHeight(zAbs) * aspect

    /**
     * Mirrored copies needed on each side of a ground band so it spans the frustum.
     *
     * Copies sit at `centreX + i * 2 * quadHalfWidth`, so `2n + 1` copies reach
     * `(2n + 1) * quadHalfWidth` either side of the centre.  [centreX] is taken as an absolute
     * value because ground parallax slides the band both ways.
     */
    fun tilesEachSide(zAbs: Float, aspect: Float, quadHalfWidth: Float, centreX: Float): Int {
        if (quadHalfWidth <= 0.0f) {
            return 0
        }
        val needed = halfWidth(zAbs, aspect) + abs(centreX) + COVERAGE_MARGIN
        val spans = needed / quadHalfWidth
        return max(0.0f, ceil((spans - 1.0f) / 2.0f)).toInt()
    }

    /**
     * X below which a cloud of half-width [cloudHalfWidth] has fully left the screen.  Positions
     * are stored before [SKY_SHIFT] is applied, matching the draw calls.
     */
    fun cloudLeftLimit(zAbs: Float, aspect: Float, cloudHalfWidth: Float): Float =
        -halfWidth(zAbs, aspect) - SKY_SHIFT - cloudHalfWidth

    /**
     * Distance a cloud travels before it repeats: the full off-screen-right to off-screen-left
     * traversal plus [CLOUD_GAP] of empty sky.  Because this is measured against the real frustum
     * it grows with the screen, which is what the original construction could not do — its travel
     * was pinned to the frame counter at 50.05 units regardless of orientation, so in landscape a
     * cloud could not cross the ~66 unit window and had to be teleported mid-screen.
     */
    fun cloudSpan(zAbs: Float, aspect: Float, cloudHalfWidth: Float): Float =
        max(MIN_CLOUD_SPAN, (2.0f * halfWidth(zAbs, aspect)) + (2.0f * cloudHalfWidth) + CLOUD_GAP)

    /**
     * X scale for the sky and night-cover quads.  These are vertical gradients, so widening them
     * horizontally is invisible and avoids repeating a layer that has nothing to repeat.
     * [meshHalfWidth] is the mesh half-extent (8 for `Square`), [minScale] the portrait value.
     */
    fun skyScaleX(
        zAbs: Float,
        aspect: Float,
        centreX: Float,
        meshHalfWidth: Float,
        minScale: Float
    ): Float {
        val needed = halfWidth(zAbs, aspect) + abs(centreX) + COVERAGE_MARGIN
        return max(minScale, needed / meshHalfWidth)
    }
}
