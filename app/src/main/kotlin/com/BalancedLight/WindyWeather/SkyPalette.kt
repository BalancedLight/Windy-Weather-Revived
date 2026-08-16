package com.BalancedLight.WindyWeather

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Vertical sky gradients as blendable keyframes.
 *
 * Every palette shares the same nine stop positions (V = 0 at the zenith, 1 at the bottom of the
 * sky quad), so crossfading two palettes is a flat per-stop lerp with no resampling.  All blending
 * and interpolation happens in linear light -- interpolating sRGB values directly drags the
 * night->dawn crossfade through a muddy grey and makes a half-lit sky read far too dark.
 *
 * Between stops the ramp uses monotone cubic Hermite (Fritsch-Carlson) rather than straight lines.
 * Plain linear interpolation leaves a visible Mach band at each of the eight joins; unclamped
 * Catmull-Rom fixes that but overshoots out of gamut on the steep twilight segments.
 *
 * The CLEAR_DAY / CLEAR_NIGHT / OVERCAST_DAY / OVERCAST_NIGHT keyframes are sampled from the
 * original sky_01 / sky_02 / sky_03 / sky_04 textures so the base look is unchanged.  The
 * peach/amber dawn and dusk keyframes are the baseline authored look; daily variations are
 * expressed as deltas from them so weather can use the same event character at a reduced strength.
 *
 * Android-free on purpose, so it can be unit tested alongside [TwilightTimeline].
 */
internal object SkyPalette {
    const val STOP_COUNT = 17
    const val COMPONENT_COUNT = STOP_COUNT * 3

    /** Full-strength colour variation for scenes where the sun remains visible. */
    const val FULL_VARIATION_STRENGTH = 1.0f

    /** Subtle colour variation for overcast, freezing, and precipitation scenes. */
    const val MUTED_VARIATION_STRENGTH = 0.30f

    /** Spacing between adjacent stops in V. */
    private const val STOP_SPACING = 1.0f / (STOP_COUNT - 1)

    enum class Family {
        CLEAR,
        OVERCAST
    }

    enum class DawnProfile {
        PEACH,
        ROSE_PINK,
        CORAL,
        GOLDEN
    }

    enum class DuskProfile {
        AMBER,
        ORANGE,
        CORAL_PINK,
        ROSE_VIOLET
    }

    /** The two independently selected event looks for one local calendar day. */
    data class DailyVariation(val dawn: DawnProfile, val dusk: DuskProfile) {
        /** Compact, stable identifier used by generated-texture cache keys. */
        val cacheKey: Int
            get() = dawn.ordinal or (dusk.ordinal shl 2)
    }

    enum class ForegroundTreatment {
        /** Match the selected twilight hue for clear, mostly-clear, and cloudy scenes. */
        FULL,

        /** Apply a softened version of the selected hue for fog, snow, and ice-cold scenes. */
        MUTED_MATCHED,

        /** Keep rain-family foregrounds faintly cool or neutral, never amber. */
        COOL_NEUTRAL
    }

    data class SceneAppearance(
        val variationStrength: Float,
        val foregroundTreatment: ForegroundTreatment
    )

    // Sampled from sky_02.jpg.
    private val CLEAR_NIGHT = linearize(
        0x010729, 0x01082E, 0x000A38, 0x020F47, 0x06175C, 0x092172,
        0x0B348F, 0x0B4D9E, 0x0E6AB4, 0x65B9E6, 0x74C9F0, 0x67C8F3,
        0x68C9F4, 0x68C9F4, 0x68C9F4, 0x68C9F4, 0x68C9F4
    )

    // Peach is the original authored dawn, retained as the baseline variation.
    private val CLEAR_DAWN_PEACH = linearize(
        0x16255C, 0x1B2C65, 0x22356F, 0x2C427B, 0x3A5288, 0x496698,
        0x5E7AA5, 0x7793AC, 0x94A0AE, 0xB99E9F, 0xD69C86, 0xE7A67C,
        0xF2B77A, 0xFBC88A, 0xFFD7A0, 0xFFDEAD, 0xFFE3B8
    )

    private val CLEAR_DAWN_ROSE_PINK = linearize(
        0x20245D, 0x292B6A, 0x37347B, 0x4A408A, 0x604E98, 0x785A9F,
        0x9169A1, 0xAB78A3, 0xC585A7, 0xDC90AD, 0xEE9AAF, 0xF8A6B4,
        0xFFB5C0, 0xFFC3CD, 0xFFCED8, 0xFFD6DF, 0xFFDEE6
    )

    private val CLEAR_DAWN_CORAL = linearize(
        0x1B285F, 0x23316D, 0x303B7B, 0x414A88, 0x565892, 0x6D668F,
        0x86718A, 0xA17A83, 0xBC827A, 0xD38A70, 0xE99466, 0xF5A166,
        0xFDB06E, 0xFFBD7D, 0xFFC98E, 0xFFD49E, 0xFFDBAA
    )

    private val CLEAR_DAWN_GOLDEN = linearize(
        0x17285E, 0x1D316D, 0x293D7D, 0x394C8B, 0x4C5C99, 0x6170A3,
        0x7781A6, 0x9292A0, 0xAD9D92, 0xC7A47F, 0xDDAA6A, 0xEDB15B,
        0xF8BD58, 0xFEC966, 0xFFD578, 0xFFDD8B, 0xFFE3A0
    )

    // Sampled from sky_01.jpg.
    private val CLEAR_DAY = linearize(
        0x2A70B6, 0x2974B8, 0x277FC6, 0x2B95D6, 0x34A7E6, 0x4DB7ED,
        0x68C4F1, 0x85D1F5, 0xB1E5FB, 0xDAF3FE, 0xF1FAFF, 0xF5FDFF,
        0xF5FDFF, 0xF5FDFF, 0xF5FDFF, 0xF5FDFF, 0xF5FDFF
    )

    // Amber is the original authored dusk, retained as the baseline variation.
    private val CLEAR_DUSK_AMBER = linearize(
        0x1B2A5E, 0x24336B, 0x2E3A76, 0x3B4080, 0x4C4585, 0x624A84,
        0x7A4F80, 0x95557A, 0xB05E72, 0xCB6A65, 0xE07A55, 0xED8F47,
        0xF5A33F, 0xFCB648, 0xFFC65C, 0xFFD074, 0xFFD98A
    )

    private val CLEAR_DUSK_ORANGE = linearize(
        0x1C295C, 0x25315F, 0x333863, 0x443D65, 0x5B4164, 0x754460,
        0x91495A, 0xAD5051, 0xC65A46, 0xDB6738, 0xEB762D, 0xF78928,
        0xFD992D, 0xFFA83C, 0xFFB84F, 0xFFC665, 0xFFD17C
    )

    private val CLEAR_DUSK_CORAL_PINK = linearize(
        0x282960, 0x34316E, 0x453978, 0x5B407E, 0x744681, 0x8E4C7F,
        0xA9557B, 0xC16076, 0xD96E70, 0xEB7B70, 0xF58B7B, 0xFC9B8C,
        0xFFAA9D, 0xFFB9AF, 0xFFC7BF, 0xFFD1CB, 0xFFD9D4
    )

    private val CLEAR_DUSK_ROSE_VIOLET = linearize(
        0x2B2760, 0x382E70, 0x4B357D, 0x623C87, 0x7B438D, 0x954A91,
        0xAD5593, 0xC26495, 0xD57599, 0xE786A2, 0xF197AE, 0xF9A8BB,
        0xFEB7C8, 0xFFC3D1, 0xFFCFD9, 0xFFD7DF, 0xFFDEE5
    )

    // Sampled from sky_04.png.  Below V ~= 0.78 the source art is pure white padding that the
    // lawn covers, so the last real colour is carried down instead -- a cubic through that cliff
    // would drag the curve upward inside the visible region.
    private val OVERCAST_NIGHT = linearize(
        0x0C242D, 0x0C222B, 0x0D222C, 0x132832, 0x192D3A, 0x283D47,
        0x30464C, 0x3A4E54, 0x5B696D, 0x717D80, 0x717D7F, 0x717D7F,
        0x727D7F, 0x717D7E, 0x717D7E, 0x717D7E, 0x717D7E
    )

    private val OVERCAST_DAWN = linearize(
        0x141F2A, 0x17232E, 0x1B2833, 0x22303C, 0x2B3945, 0x36434F,
        0x434E58, 0x505960, 0x5F6367, 0x716B6B, 0x82746E, 0x917E72,
        0x9E8878, 0xA99482, 0xAE9A88, 0xAE9A88, 0xAE9A88
    )

    // Sampled from sky_03.png, same white-padding treatment as OVERCAST_NIGHT.
    private val OVERCAST_DAY = linearize(
        0x213849, 0x223A4B, 0x2C4456, 0x3E596E, 0x4E6A7F, 0x567287,
        0x5C798E, 0x5A778B, 0x7C8F97, 0x99AAAD, 0x96A6A8, 0x96A6A8,
        0x97A6A8, 0x97A7A8, 0x97A7A8, 0x97A7A8, 0x97A7A8
    )

    private val OVERCAST_DUSK = linearize(
        0x16222E, 0x182632, 0x1D2B38, 0x253441, 0x2F3E4A, 0x3B4752,
        0x4A505A, 0x5B5A61, 0x6E6367, 0x836969, 0x96706A, 0xA87A69,
        0xB78468, 0xC28E6C, 0xC89370, 0xC89370, 0xC89370
    )

    private data class PaletteProfile(
        val stops: FloatArray,
        val foregroundTint: TwilightTimeline.Rgb,
        val coolNeutralTint: TwilightTimeline.Rgb
    )

    private val DAWN_PROFILES = arrayOf(
        PaletteProfile(
            CLEAR_DAWN_PEACH,
            TwilightTimeline.Rgb(1.0f, 0.84f, 0.74f),
            TwilightTimeline.Rgb(0.96f, 0.98f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DAWN_ROSE_PINK,
            TwilightTimeline.Rgb(1.0f, 0.75f, 0.86f),
            TwilightTimeline.Rgb(0.97f, 0.98f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DAWN_CORAL,
            TwilightTimeline.Rgb(1.0f, 0.78f, 0.68f),
            TwilightTimeline.Rgb(0.96f, 0.98f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DAWN_GOLDEN,
            TwilightTimeline.Rgb(1.0f, 0.88f, 0.63f),
            TwilightTimeline.Rgb(0.95f, 0.98f, 1.0f)
        )
    )

    private val DUSK_PROFILES = arrayOf(
        PaletteProfile(
            CLEAR_DUSK_AMBER,
            TwilightTimeline.Rgb(1.0f, 0.82f, 0.70f),
            TwilightTimeline.Rgb(0.96f, 0.98f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DUSK_ORANGE,
            TwilightTimeline.Rgb(1.0f, 0.72f, 0.56f),
            TwilightTimeline.Rgb(0.94f, 0.97f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DUSK_CORAL_PINK,
            TwilightTimeline.Rgb(1.0f, 0.75f, 0.74f),
            TwilightTimeline.Rgb(0.97f, 0.98f, 1.0f)
        ),
        PaletteProfile(
            CLEAR_DUSK_ROSE_VIOLET,
            TwilightTimeline.Rgb(0.98f, 0.72f, 0.88f),
            TwilightTimeline.Rgb(0.96f, 0.97f, 1.0f)
        )
    )

    /**
     * Writes the linear-light gradient stops for [family] at cycle position [skyPosition] into
     * [outLinear], which must hold [COMPONENT_COUNT] floats.  Caller-supplied so the render loop
     * allocates nothing.
     */
    fun blendInto(family: Family, skyPosition: Float, outLinear: FloatArray) {
        blendInto(
            family,
            skyPosition,
            DEFAULT_DAILY_VARIATION,
            FULL_VARIATION_STRENGTH,
            outLinear
        )
    }

    /**
     * Writes the linear-light gradient stops for [family] at [skyPosition] using [variation].
     * [variationStrength] controls how far an event keyframe can move from the established base
     * palette; it is full for visible-sun scenes and muted for bad weather.
     */
    fun blendInto(
        family: Family,
        skyPosition: Float,
        variation: DailyVariation,
        variationStrength: Float,
        outLinear: FloatArray
    ) {
        require(outLinear.size >= COMPONENT_COUNT) { "outLinear must hold $COMPONENT_COUNT floats" }
        val position = skyPosition.coerceIn(TwilightTimeline.SKY_NIGHT, TwilightTimeline.SKY_CYCLE)
        val index = position.toInt().coerceIn(0, 3)
        val mix = TwilightTimeline.smoothstep(position - index)
        for (i in 0 until COMPONENT_COUNT) {
            val from = keyframeComponent(family, index, variation, variationStrength, i)
            val to = keyframeComponent(family, index + 1, variation, variationStrength, i)
            outLinear[i] = from + ((to - from) * mix)
        }
    }

    /**
     * Selects independent dawn and dusk profiles from the local date.  The hash is deterministic
     * and does not use a runtime [java.util.Random], so a scene reload cannot change the sky.
     */
    fun dailyVariation(year: Int, dayOfYear: Int): DailyVariation =
        dailyVariationForDateToken(localDateToken(year, dayOfYear))

    /** Compact local-date identity retained by the renderer's existing twilight cache. */
    fun localDateToken(year: Int, dayOfYear: Int): Int =
        (year * DATE_TOKEN_DAY_MULTIPLIER) + dayOfYear.coerceIn(1, 366)

    fun dailyVariationForDateToken(dateToken: Int): DailyVariation {
        return DailyVariation(
            dawn = DAWN_PROFILE_VALUES[profileIndex(dateToken, DAWN_SALT, DAWN_PROFILE_VALUES.size)],
            dusk = DUSK_PROFILE_VALUES[profileIndex(dateToken, DUSK_SALT, DUSK_PROFILE_VALUES.size)]
        )
    }

    /**
     * Resolves the selected event hue to a foreground multiplier.  The timeline owns only the
     * fade strength; palette ownership stays here so the sky and foreground cannot drift apart.
     */
    fun foregroundTint(
        variation: DailyVariation,
        skyPosition: Float,
        twilightStrength: Float,
        treatment: ForegroundTreatment
    ): TwilightTimeline.Rgb {
        val strength = twilightStrength.coerceIn(0.0f, 1.0f)
        if (strength <= 0.0f) {
            return NEUTRAL_TINT
        }
        val profile = profileForPosition(variation, skyPosition)
        val target = when (treatment) {
            ForegroundTreatment.FULL,
            ForegroundTreatment.MUTED_MATCHED -> profile.foregroundTint

            ForegroundTreatment.COOL_NEUTRAL -> profile.coolNeutralTint
        }
        val treatmentStrength = if (treatment == ForegroundTreatment.MUTED_MATCHED) {
            MUTED_VARIATION_STRENGTH
        } else {
            FULL_VARIATION_STRENGTH
        }
        return lerpRgb(NEUTRAL_TINT, target, strength * treatmentStrength)
    }

    private fun keyframeComponent(
        family: Family,
        keyframeIndex: Int,
        variation: DailyVariation,
        variationStrength: Float,
        component: Int
    ): Float {
        val base = baseKeyframe(family, keyframeIndex)[component]
        if (keyframeIndex != 1 && keyframeIndex != 3) {
            return base
        }
        val selected = profileForKeyframe(variation, keyframeIndex).stops[component]
        val baseline = baselineProfileForKeyframe(keyframeIndex).stops[component]
        val strength = variationStrength.coerceIn(0.0f, FULL_VARIATION_STRENGTH)
        return (base + ((selected - baseline) * strength)).coerceIn(0.0f, 1.0f)
    }

    private fun baseKeyframe(family: Family, keyframeIndex: Int): FloatArray {
        return when (family) {
            Family.CLEAR -> when (keyframeIndex) {
                0, 4 -> CLEAR_NIGHT
                1 -> CLEAR_DAWN_PEACH
                2 -> CLEAR_DAY
                3 -> CLEAR_DUSK_AMBER
                else -> throw IllegalArgumentException("Unknown sky keyframe $keyframeIndex")
            }

            Family.OVERCAST -> when (keyframeIndex) {
                0, 4 -> OVERCAST_NIGHT
                1 -> OVERCAST_DAWN
                2 -> OVERCAST_DAY
                3 -> OVERCAST_DUSK
                else -> throw IllegalArgumentException("Unknown sky keyframe $keyframeIndex")
            }
        }
    }

    private fun profileForKeyframe(variation: DailyVariation, keyframeIndex: Int): PaletteProfile {
        return when (keyframeIndex) {
            1 -> DAWN_PROFILES[variation.dawn.ordinal]
            3 -> DUSK_PROFILES[variation.dusk.ordinal]
            else -> throw IllegalArgumentException("Sky keyframe $keyframeIndex has no twilight profile")
        }
    }

    private fun baselineProfileForKeyframe(keyframeIndex: Int): PaletteProfile {
        return when (keyframeIndex) {
            1 -> DAWN_PROFILES[DawnProfile.PEACH.ordinal]
            3 -> DUSK_PROFILES[DuskProfile.AMBER.ordinal]
            else -> throw IllegalArgumentException("Sky keyframe $keyframeIndex has no twilight profile")
        }
    }

    private fun profileForPosition(variation: DailyVariation, skyPosition: Float): PaletteProfile {
        return if (skyPosition < TwilightTimeline.SKY_DAY) {
            DAWN_PROFILES[variation.dawn.ordinal]
        } else {
            DUSK_PROFILES[variation.dusk.ordinal]
        }
    }

    private fun profileIndex(dateToken: Int, salt: Int, profileCount: Int): Int {
        var hash = dateToken xor salt
        hash = (hash xor (hash ushr 16)) * HASH_MULTIPLIER_A
        hash = (hash xor (hash ushr 15)) * HASH_MULTIPLIER_B
        hash = hash xor (hash ushr 16)
        return (hash and Int.MAX_VALUE) % profileCount
    }

    private fun lerpRgb(
        from: TwilightTimeline.Rgb,
        to: TwilightTimeline.Rgb,
        amount: Float
    ): TwilightTimeline.Rgb {
        val mix = amount.coerceIn(0.0f, 1.0f)
        return TwilightTimeline.Rgb(
            red = from.red + ((to.red - from.red) * mix),
            green = from.green + ((to.green - from.green) * mix),
            blue = from.blue + ((to.blue - from.blue) * mix)
        )
    }

    /**
     * Fritsch-Carlson tangents for [stopsLinear], written into [outTangents].  Recomputed once per
     * gradient change, not per sampled row.
     */
    fun computeTangents(stopsLinear: FloatArray, outTangents: FloatArray) {
        require(outTangents.size >= COMPONENT_COUNT) { "outTangents must hold $COMPONENT_COUNT floats" }
        val secants = FloatArray(STOP_COUNT - 1)
        for (channel in 0 until 3) {
            for (segment in 0 until STOP_COUNT - 1) {
                val here = stopsLinear[(segment * 3) + channel]
                val next = stopsLinear[((segment + 1) * 3) + channel]
                secants[segment] = (next - here) / STOP_SPACING
            }

            outTangents[channel] = secants[0]
            outTangents[((STOP_COUNT - 1) * 3) + channel] = secants[STOP_COUNT - 2]
            for (stop in 1 until STOP_COUNT - 1) {
                outTangents[(stop * 3) + channel] = (secants[stop - 1] + secants[stop]) * 0.5f
            }

            // Clamp so the curve stays monotone on every segment and cannot overshoot its stops.
            for (segment in 0 until STOP_COUNT - 1) {
                val secant = secants[segment]
                val lowIndex = (segment * 3) + channel
                val highIndex = ((segment + 1) * 3) + channel
                if (secant == 0.0f) {
                    outTangents[lowIndex] = 0.0f
                    outTangents[highIndex] = 0.0f
                    continue
                }
                var alpha = outTangents[lowIndex] / secant
                var beta = outTangents[highIndex] / secant
                if (alpha < 0.0f) {
                    alpha = 0.0f
                    outTangents[lowIndex] = 0.0f
                }
                if (beta < 0.0f) {
                    beta = 0.0f
                    outTangents[highIndex] = 0.0f
                }
                val magnitude = (alpha * alpha) + (beta * beta)
                if (magnitude > 9.0f) {
                    val scale = 3.0f / sqrt(magnitude)
                    outTangents[lowIndex] = scale * alpha * secant
                    outTangents[highIndex] = scale * beta * secant
                }
            }
        }
    }

    /**
     * Samples the gradient at [v] (0 = zenith, 1 = bottom of the quad), writing linear-light RGB
     * into [outRgb].
     */
    fun sampleInto(
        stopsLinear: FloatArray,
        tangents: FloatArray,
        v: Float,
        outRgb: FloatArray
    ) {
        val clamped = v.coerceIn(0.0f, 1.0f)
        val scaled = clamped * (STOP_COUNT - 1)
        val segment = scaled.toInt().coerceIn(0, STOP_COUNT - 2)
        val t = scaled - segment
        val tt = t * t
        val ttt = tt * t
        val h00 = (2.0f * ttt) - (3.0f * tt) + 1.0f
        val h10 = ttt - (2.0f * tt) + t
        val h01 = (-2.0f * ttt) + (3.0f * tt)
        val h11 = ttt - tt
        for (channel in 0 until 3) {
            val lowIndex = (segment * 3) + channel
            val highIndex = ((segment + 1) * 3) + channel
            outRgb[channel] = (h00 * stopsLinear[lowIndex]) +
                (h10 * STOP_SPACING * tangents[lowIndex]) +
                (h01 * stopsLinear[highIndex]) +
                (h11 * STOP_SPACING * tangents[highIndex])
        }
    }

    fun srgbToLinear(component: Float): Float {
        return if (component <= 0.04045f) {
            component / 12.92f
        } else {
            ((component + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    fun linearToSrgb(component: Float): Float {
        val clamped = component.coerceIn(0.0f, 1.0f)
        return if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            (1.055f * clamped.pow(1.0f / 2.4f)) - 0.055f
        }
    }

    private fun linearize(vararg stops: Int): FloatArray {
        require(stops.size == STOP_COUNT) { "expected $STOP_COUNT stops, got ${stops.size}" }
        val out = FloatArray(COMPONENT_COUNT)
        for (stop in stops.indices) {
            val rgb = stops[stop]
            out[stop * 3] = srgbToLinear(((rgb shr 16) and 0xFF) / 255.0f)
            out[(stop * 3) + 1] = srgbToLinear(((rgb shr 8) and 0xFF) / 255.0f)
            out[(stop * 3) + 2] = srgbToLinear((rgb and 0xFF) / 255.0f)
        }
        return out
    }

    val DEFAULT_DAILY_VARIATION = DailyVariation(DawnProfile.PEACH, DuskProfile.AMBER)
    private val DAWN_PROFILE_VALUES = DawnProfile.values()
    private val DUSK_PROFILE_VALUES = DuskProfile.values()
    private val NEUTRAL_TINT = TwilightTimeline.Rgb(1.0f, 1.0f, 1.0f)
    private const val DATE_TOKEN_DAY_MULTIPLIER = 512
    private const val DAWN_SALT = 0x6D2B79F5
    private const val DUSK_SALT = 0x1B873593
    private const val HASH_MULTIPLIER_A = 0x7FEB352D
    private val HASH_MULTIPLIER_B = 0x846CA68B.toInt()
}
