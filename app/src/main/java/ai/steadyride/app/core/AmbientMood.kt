package ai.steadyride.app.core

import ai.steadyride.app.vehicle.ManeuverKind
import ai.steadyride.app.vehicle.Turn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps the car's motion to one cabin-wide ambient color and brightness.
 * The Pleos SDK exposes the ambient light as a single zone (brightness 0..100,
 * color 0xRRGGBB), so motion is expressed by color and intensity, not by position.
 *
 *  - braking / slowing down -> warm amber
 *  - accelerating           -> cool teal
 *  - turning                -> violet
 *  - 0..2 s before a navigation maneuver the color already starts to shift, so the
 *    body gets a heads-up before the motion (anticipatory cue).
 *  - steady driving         -> the driver's own color and brightness
 */
object AmbientMood {
    const val AMBER = 0xFF9A3C
    const val TEAL = 0x2FD3C4
    const val VIOLET = 0x9B6BFF

    private const val G = 9.81f

    /** Returns (color 0xRRGGBB, brightness 0..100). */
    fun target(c: ComfortState, cue: Cue?, baseRgb: Int, baseBrightness: Int): Pair<Int, Int> {
        val long = c.longitudinalG * G
        val lat = abs(c.lateralG * G)
        var wBrake = ramp(-long, 0.3f, 3.0f)
        val wAccel = ramp(long, 0.3f, 2.5f)
        var wTurn = ramp(lat, 0.3f, 3.0f)

        if (cue != null) {
            if (cue.source == CueSource.NAVI && cue.secondsAhead <= 2f) {
                val pre = (1f - cue.secondsAhead / 2f) * 0.7f
                val isTurn = cue.turn != Turn.NONE && cue.kind != ManeuverKind.SPEED_BUMP && cue.kind != ManeuverKind.DOWNHILL
                if (isTurn) wTurn = max(wTurn, pre) else wBrake = max(wBrake, pre)
            }
            if (cue.source == CueSource.BRAKE) wBrake = max(wBrake, 0.4f)
        }

        val total = wBrake + wAccel + wTurn
        if (total < 0.01f) return baseRgb to baseBrightness
        val mood = mix(listOf(AMBER to wBrake, TEAL to wAccel, VIOLET to wTurn))
        val strength = min(1f, max(wBrake, max(wAccel, wTurn)))
        val rgb = lerp(baseRgb, mood, strength)
        val brightness = (baseBrightness + (100 - baseBrightness) * 0.7f * strength).roundToInt()
        return rgb to brightness.coerceIn(0, 100)
    }

    private fun ramp(x: Float, from: Float, to: Float) = ((x - from) / (to - from)).coerceIn(0f, 1f)

    private fun mix(colors: List<Pair<Int, Float>>): Int {
        val w = colors.sumOf { it.second.toDouble() }.toFloat()
        var r = 0f; var g = 0f; var b = 0f
        for ((c, k) in colors) {
            r += ((c shr 16) and 0xFF) * k / w
            g += ((c shr 8) and 0xFF) * k / w
            b += (c and 0xFF) * k / w
        }
        return rgb(r, g, b)
    }

    fun lerp(a: Int, b: Int, t: Float): Int = rgb(
        ((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t,
        ((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t,
        (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t,
    )

    fun rgb(r: Float, g: Float, b: Float): Int =
        (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
}
