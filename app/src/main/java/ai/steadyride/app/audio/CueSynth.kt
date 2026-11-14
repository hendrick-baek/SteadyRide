package ai.steadyride.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Offline synthesizer for the cue sounds: a smooth sci-fi "hum" in the spirit of a UFO
 * gliding past, not a mechanical/engine sound.
 *
 * Voice: a gently FM'd sine (soft, glassy overtones) with two slightly detuned copies for
 * a slow hovering beat, a faint high shimmer, light vibrato and a soft tremolo "warble".
 * No noise and no sub-octave, which is what made the earlier version sound like an engine.
 * The pitch bends like a Doppler pass-by while the sound travels across the stereo field
 * (level + time difference between the ears), then a spacious reverb tail is added.
 */
internal object CueSynth {

    const val SR = 48_000
    const val PREROLL_S = 0.18f
    private const val TWO_PI = (2 * PI).toFloat()
    private const val PEAK = 0.89f
    private const val ITD_SAMPLES = 30 // ~0.6 ms: max delay of the far ear

    /** "Woooong" passing from the opposite side across to [side] (-1 left, +1 right). */
    fun turn(side: Float): ShortArray {
        val dur = 1.4f
        return render(dur, tailS = 0.9f, panOf = { t ->
            side * (-0.9f + 1.9f * smoothstep(t / dur))
        }) { t, v ->
            // Doppler: a little higher while approaching, lower once it has passed.
            val f = 300f * (1.06f - 0.12f * smoothstep(t / dur))
            hann(t / dur).pow(0.8f) * v.hum(f, t, warble = 0.35f)
        }
    }

    /** Two short "bwoong" pulses with a downward bend, centered: speed bump ahead. */
    fun bump(): ShortArray = render(0.8f, tailS = 0.8f, panOf = { 0f }) { t, v ->
        val pulse = 0.34f
        val k = if (t < 0.4f) 0 else 1
        val u = t - k * 0.4f
        if (u > pulse) {
            0f
        } else {
            val f = 210f - 55f * smoothstep(u / pulse)
            sin(PI.toFloat() * u / pulse).coerceAtLeast(0f).pow(1.5f) * v.hum(f, t, warble = 0.2f) * (if (k == 0) 1f else 0.85f)
        }
    }

    /** Descending "woooo", centered with a slight sway: braking or downhill. */
    fun glide(): ShortArray {
        val dur = 1.1f
        return render(dur, tailS = 0.8f, panOf = { t -> 0.2f * sin(TWO_PI * t / dur) }) { t, v ->
            val f = 360f - 130f * smoothstep(t / dur)
            hann(t / dur).pow(0.9f) * v.hum(f, t, warble = 0.5f)
        }
    }

    // ---------------- Voice ----------------

    /** Phase-continuous oscillators for one sound. */
    class Voice {
        private var pc = 0f  // carrier
        private var pm = 0f  // FM modulator
        private var p2 = 0f  // chorus up
        private var p3 = 0f  // chorus down
        private var ps = 0f  // shimmer

        /** Smooth hum at [f] Hz; [warble] 0..1 is the depth of the UFO-like tremolo. */
        fun hum(f: Float, t: Float, warble: Float): Float {
            val vib = 1f + 0.012f * sin(TWO_PI * 5.2f * t) * min(1f, t / 0.35f)
            val fc = f * vib
            pc = (pc + TWO_PI * fc / SR) % TWO_PI
            pm = (pm + TWO_PI * fc * 2f / SR) % TWO_PI
            p2 = (p2 + TWO_PI * fc * 1.004f / SR) % TWO_PI
            p3 = (p3 + TWO_PI * fc * 0.996f / SR) % TWO_PI
            ps = (ps + TWO_PI * fc * 2.997f / SR) % TWO_PI
            val core = sin(pc + 0.55f * sin(pm))
            val chorus = 0.5f * (sin(p2) + sin(p3))
            val shimmer = 0.07f * sin(ps)
            val trem = 1f - warble * 0.5f * (0.5f - 0.5f * cos(TWO_PI * 7.5f * t))
            return trem * (0.6f * core + 0.35f * chorus + shimmer)
        }
    }

    private fun render(
        durS: Float,
        tailS: Float,
        panOf: (Float) -> Float,
        sample: (t: Float, voice: Voice) -> Float,
    ): ShortArray {
        val n = (durS * SR).toInt()
        val total = n + (tailS * SR).toInt() + ITD_SAMPLES
        val mono = FloatArray(n)
        val pan = FloatArray(n)
        val voice = Voice()
        for (i in 0 until n) {
            val t = i / SR.toFloat()
            mono[i] = sample(t, voice)
            pan[i] = panOf(t).coerceIn(-1f, 1f)
        }

        // Spatialize: equal-power level difference plus a small delay at the far ear.
        val l = FloatArray(total)
        val r = FloatArray(total)
        for (i in 0 until n) {
            val p = pan[i]
            val a = (p + 1f) * (PI.toFloat() / 4f)
            val gl = cos(a)
            val gr = sin(a)
            val dl = if (p > 0f) (p * ITD_SAMPLES).toInt() else 0
            val dr = if (p < 0f) (-p * ITD_SAMPLES).toInt() else 0
            l[i + dl] += mono[i] * gl
            r[i + dr] += mono[i] * gr
        }

        reverb(l, r)
        return toPcm(l, r)
    }

    /** Small stereo room: parallel damped combs into series all-passes (Freeverb style). */
    private fun reverb(l: FloatArray, r: FloatArray, wet: Float = 0.3f) {
        val combL = intArrayOf(1214, 1293, 1390, 1476)
        val combR = combL.map { it + 25 }.toIntArray()
        val outL = process(l, combL)
        val outR = process(r, combR)
        for (i in l.indices) {
            l[i] = l[i] * (1f - wet * 0.5f) + outL[i] * wet
            r[i] = r[i] * (1f - wet * 0.5f) + outR[i] * wet
        }
    }

    private fun process(x: FloatArray, combs: IntArray): FloatArray {
        val sum = FloatArray(x.size)
        for (d in combs) {
            val buf = FloatArray(d)
            var idx = 0
            var store = 0f
            for (i in x.indices) {
                val out = buf[idx]
                store = out * 0.7f + store * 0.3f // damping
                buf[idx] = x[i] + store * 0.82f
                idx = (idx + 1) % d
                sum[i] += out * 0.25f
            }
        }
        var y = sum
        for (d in intArrayOf(556, 441)) {
            val buf = FloatArray(d)
            var idx = 0
            val out = FloatArray(y.size)
            for (i in y.indices) {
                val b = buf[idx]
                out[i] = -y[i] + b
                buf[idx] = y[i] + b * 0.5f
                idx = (idx + 1) % d
            }
            y = out
        }
        return y
    }

    private fun toPcm(l: FloatArray, r: FloatArray): ShortArray {
        var peak = 1e-6f
        for (i in l.indices) peak = max(peak, max(abs(l[i]), abs(r[i])))
        val g = PEAK / peak
        val pre = (PREROLL_S * SR).toInt()
        val out = ShortArray((pre + l.size) * 2)
        // Short fade at the very end so the reverb tail never clicks.
        val fade = SR / 20
        for (i in l.indices) {
            val f = min(1f, (l.size - i).toFloat() / fade)
            out[(pre + i) * 2] = (l[i] * g * f * Short.MAX_VALUE).toInt().toShort()
            out[(pre + i) * 2 + 1] = (r[i] * g * f * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun hann(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return (0.5f - 0.5f * cos(TWO_PI * c)).coerceAtLeast(0f) // never negative: pow() would give NaN
    }
}
