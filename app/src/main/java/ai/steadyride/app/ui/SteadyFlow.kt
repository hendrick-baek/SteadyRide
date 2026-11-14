package ai.steadyride.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

enum class Season(val label: String) {
    SPRING("봄"), SUMMER("여름"), AUTUMN("가을"), WINTER("겨울");

    companion object {
        /** Korean seasons by month. */
        fun now(): Season = when (LocalDate.now().monthValue) {
            in 3..5 -> SPRING
            in 6..8 -> SUMMER
            in 9..11 -> AUTUMN
            else -> WINTER
        }
    }
}

private class Particle(
    val right: Boolean,   // which edge band it lives in
    var x: Float,         // px offset inside the band
    var y: Float,         // px from the top
    val sizeDp: Float,
    val depth: Float,     // parallax: farther particles move a little less
    var rot: Float,       // degrees
    val spin: Float,      // +1 / -1
    val variant: Int,     // color / shape variation
    val breathPeriodS: Float,
    val breathPhase: Float,
    var scale: Float = 1f,
)

/**
 * Steady Flow: overlay for passengers in which particles along the left and right edges move
 * the way the body is pushed by the car's acceleration, so what the eyes see agrees
 * with what the inner ear feels.
 *
 *  - accelerating -> particles flow down (backwards)
 *  - braking      -> particles flow up
 *  - left turn    -> particles flow right, right turn -> left
 *
 * With no acceleration the particles stand still: idle drifting would itself be visual
 * motion that does not match the vehicle, the very conflict this is meant to remove.
 */
@Composable
fun SteadyFlowOverlay(lateralG: Float, longitudinalG: Float, season: Season, modifier: Modifier = Modifier) {
    val accel by rememberUpdatedState(lateralG to longitudinalG)
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val particles = remember(season, size) { spawn(size, density) }
    val shapes = remember { Shapes() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(particles) {
        var last = 0L
        var ax = 0f // acceleration after the first smoothing stage (m/s^2)
        var ay = 0f
        var vx = 0f // particle flow velocity after the second stage (px/s)
        var vy = 0f
        var heading = 90f // moving down -> drops point up, as if falling
        var clockS = 0.0
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                if (size.width == 0) return@withFrameNanos

                val (latG, longG) = accel
                // Two cascaded low-pass stages: the 10 Hz signal steps become a smooth
                // S-shaped ramp, so the flow eases in and out instead of jumping.
                val k1 = 1f - exp(-dt / SMOOTH_ACCEL_S)
                ax += (softDeadband(latG * G) - ax) * k1
                ay += (softDeadband(longG * G) - ay) * k1
                val gain = GAIN_DP * density
                // Body is pushed opposite to the vehicle's acceleration.
                val tx = -ax * gain
                val ty = ay * gain
                val k2 = 1f - exp(-dt / SMOOTH_FLOW_S)
                vx += (tx - vx) * k2
                vy += (ty - vy) * k2
                val speed = hypot(vx, vy)
                clockS += dt
                // "Breathing" while the flow is still: each particle slowly swells and shrinks
                // in place (no drift, so it adds no false motion). Fades out as the flow starts.
                val idle = 1f - (speed / (40f * density)).coerceIn(0f, 1f)
                val amp = BREATH_AMP * idle * idle
                if (speed > 15f * density) heading = Math.toDegrees(atan2(vy, vx).toDouble()).toFloat()

                val bandW = bandWidth(size, density)
                val h = size.height.toFloat()
                for (p in particles) {
                    p.x = wrap(p.x + vx * p.depth * dt, bandW)
                    p.y = wrap(p.y + vy * p.depth * dt, h)
                    p.scale = 1f + amp * sin((TWO_PI_D * clockS / p.breathPeriodS + p.breathPhase)).toFloat()
                    p.rot = when (season) {
                        Season.SUMMER -> heading - 90f      // drop tail trails behind the motion
                        Season.WINTER -> p.rot + p.spin * speed * dt * 0.08f
                        else -> p.rot + p.spin * speed * dt * 0.25f
                    }
                }
                frame = now
            }
        }
    }

    Canvas(modifier.onSizeChanged { size = it }) {
        frame // redraw every animation frame
        val bandW = bandWidth(size, density)
        for (p in particles) {
            val cx = if (p.right) this.size.width - bandW + p.x else p.x
            val s = p.sizeDp * density * p.scale
            when (season) {
                Season.WINTER -> drawSnow(shapes, cx, p.y, s, p.rot, p.variant)
                Season.AUTUMN -> drawLeaf(shapes, cx, p.y, s, p.rot, p.variant)
                Season.SPRING -> drawPetal(shapes, cx, p.y, s, p.rot, p.variant)
                Season.SUMMER -> drawDrop(shapes, cx, p.y, s, p.rot, p.variant)
            }
        }
    }
}

private fun spawn(size: IntSize, density: Float): List<Particle> {
    if (size.width == 0) return emptyList()
    val rnd = Random(7)
    val bandW = bandWidth(size, density)
    val h = size.height.toFloat()
    return List(PER_SIDE * 2) { i ->
        val right = i % 2 == 1
        val row = i / 2
        Particle(
            right = right,
            x = rnd.nextFloat() * bandW,
            // Even vertical spread with jitter so the bands never look empty.
            y = (row + rnd.nextFloat() * 0.8f) * h / PER_SIDE,
            sizeDp = 14f + rnd.nextFloat() * 14f,
            depth = 0.7f + rnd.nextFloat() * 0.5f,
            rot = rnd.nextFloat() * 360f,
            spin = if (rnd.nextBoolean()) 1f else -1f,
            variant = rnd.nextInt(4),
            breathPeriodS = 2.6f + rnd.nextFloat() * 2.2f,
            breathPhase = rnd.nextFloat() * 6.283f,
        )
    }
}

private fun bandWidth(size: IntSize, density: Float) = max(72f * density, size.width * 0.09f)
/** Ignores tiny accelerations without a step at the threshold. */
private fun softDeadband(a: Float) = if (abs(a) < DEADBAND) 0f else a - DEADBAND * Math.signum(a)
private fun wrap(v: Float, max: Float): Float = ((v % max) + max) % max

// ---------------- Shapes (unit size, centered on 0,0) ----------------

private class Shapes {
    val maple = Path().apply {
        // Tips and notches of a five-lobed maple leaf in polar form (angle deg, radius).
        val pts = listOf(
            90f to 0.28f,
            138f to 0.50f, 150f to 0.64f, 162f to 0.50f,
            180f to 0.40f,
            196f to 0.74f, 208f to 0.94f, 220f to 0.72f,
            240f to 0.48f,
            258f to 0.80f, 270f to 1.00f, 282f to 0.80f,
            300f to 0.48f,
            320f to 0.72f, 332f to 0.94f, 344f to 0.74f,
            360f to 0.40f,
            378f to 0.50f, 390f to 0.64f, 402f to 0.50f,
        )
        pts.forEachIndexed { i, (deg, r) ->
            val a = Math.toRadians(deg.toDouble())
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    val petal = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-0.75f, 0.55f, -0.75f, -0.45f, -0.38f, -0.95f)
        lineTo(0f, -0.68f) // the notch of a cherry blossom petal
        lineTo(0.38f, -0.95f)
        cubicTo(0.75f, -0.45f, 0.75f, 0.55f, 0f, 1f)
        close()
    }

    val drop = Path().apply {
        moveTo(0f, -1f)
        cubicTo(0.22f, -0.6f, 0.6f, -0.2f, 0.6f, 0.34f)
        arcTo(Rect(-0.6f, -0.26f, 0.6f, 0.94f), 0f, 180f, false)
        cubicTo(-0.6f, -0.2f, -0.22f, -0.6f, 0f, -1f)
        close()
    }
}

private inline fun DrawScope.at(x: Float, y: Float, scale: Float, rot: Float, block: DrawScope.() -> Unit) {
    withTransform({
        translate(x, y)
        rotate(rot, pivot = Offset.Zero)
        scale(scale, scale, pivot = Offset.Zero)
    }, block)
}

private val snowWhite = Color(0xFFF4F9FF)

private fun DrawScope.drawSnow(sh: Shapes, x: Float, y: Float, s: Float, rot: Float, v: Int) {
    if (v == 0) {
        // Crystal: six arms with small branches.
        at(x, y, s * 0.6f, rot) {
            val st = Stroke(0.12f, cap = StrokeCap.Round)
            repeat(6) { i ->
                withTransform({ rotate(i * 60f, pivot = Offset.Zero) }) {
                    drawLine(snowWhite.copy(alpha = 0.9f), Offset.Zero, Offset(0f, -1f), st.width, StrokeCap.Round)
                    drawLine(snowWhite.copy(alpha = 0.8f), Offset(0f, -0.55f), Offset(-0.25f, -0.8f), st.width, StrokeCap.Round)
                    drawLine(snowWhite.copy(alpha = 0.8f), Offset(0f, -0.55f), Offset(0.25f, -0.8f), st.width, StrokeCap.Round)
                }
            }
        }
    } else {
        val r = s * (0.28f + 0.08f * v)
        drawCircle(
            Brush.radialGradient(listOf(snowWhite, snowWhite.copy(alpha = 0.55f), Color.Transparent), Offset(x, y), r * 1.6f),
            radius = r * 1.6f, center = Offset(x, y),
        )
    }
}

private val leafColors = listOf(Color(0xFFD9472B), Color(0xFFE8862E), Color(0xFFE9B33A), Color(0xFFA8322D))

private fun DrawScope.drawLeaf(sh: Shapes, x: Float, y: Float, s: Float, rot: Float, v: Int) {
    at(x, y, s, rot) {
        val c = leafColors[v]
        drawPath(sh.maple, c.copy(alpha = 0.88f))
        // Stem and center vein.
        drawLine(c.copy(alpha = 0.95f), Offset(0f, 0.25f), Offset(0f, 0.75f), 0.08f, StrokeCap.Round)
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, 0.25f), Offset(0f, -0.8f), 0.05f, StrokeCap.Round)
    }
}

private val petalColors = listOf(Color(0xFFF6B7C8), Color(0xFFF9D0DC), Color(0xFFF2A0B5), Color(0xFFFBE3EA))

private fun DrawScope.drawPetal(sh: Shapes, x: Float, y: Float, s: Float, rot: Float, v: Int) {
    at(x, y, s * 0.75f, rot) {
        val c = petalColors[v]
        drawPath(
            sh.petal,
            Brush.verticalGradient(listOf(c.copy(alpha = 0.92f), Color(0xFFFFF4F7).copy(alpha = 0.92f)), startY = -1f, endY = 1f),
        )
    }
}

private val dropTop = Color(0xFFBFE6FA)
private val dropBottom = Color(0xFF4BA3D6)

private fun DrawScope.drawDrop(sh: Shapes, x: Float, y: Float, s: Float, rot: Float, v: Int) {
    at(x, y, s * (0.55f + 0.06f * v), rot) {
        drawPath(sh.drop, Brush.verticalGradient(listOf(dropTop.copy(alpha = 0.55f), dropBottom.copy(alpha = 0.85f)), startY = -1f, endY = 0.94f))
        drawCircle(Color.White.copy(alpha = 0.75f), radius = 0.13f, center = Offset(-0.22f, 0.3f))
    }
}

private const val G = 9.81f
private const val PER_SIDE = 16
private const val GAIN_DP = 110f    // dp/s of particle flow per m/s^2 of acceleration
private const val SMOOTH_ACCEL_S = 0.22f
private const val SMOOTH_FLOW_S = 0.3f
private const val DEADBAND = 0.08f
private const val BREATH_AMP = 0.12f   // +-12 % size while idle
private const val TWO_PI_D = 2 * Math.PI
