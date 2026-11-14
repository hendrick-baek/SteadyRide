package ai.steadyride.app.audio

import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.CueSource
import ai.steadyride.app.vehicle.ManeuverKind
import ai.steadyride.app.vehicle.Turn
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Sound-based motion sickness relief. All audio is synthesized in code (no asset files).
 *
 * 1. Directional cue: a soft rising "whoosh" that sweeps across the cabin toward the side
 *    of the upcoming turn (see [CueSynth]), played about 1 s before the maneuver (lead time used in the
 *    Volvo/Chalmers and Reuten et al. anticipatory-cue studies).
 * 2. 100 Hz pure tone: one minute of a 100 Hz sine before/at departure
 *    (Kagawa et al., 2025, Environmental Health and Preventive Medicine).
 *
 * Pleos audio policy: every stream requests focus with GAIN_TRANSIENT_MAY_DUCK so music
 * is only ducked, and each sound starts with ~180 ms of silence so the amplifier wakes up
 * before the audible part.
 */
class CueSound(context: Context, private val note: (String) -> Unit) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val cueThread = Executors.newSingleThreadExecutor()
    private val toneThread = Executors.newSingleThreadExecutor()

    @Volatile private var toneCancel = false
    @Volatile private var tonePlaying = false

    private val _toneRemainingS = MutableStateFlow<Int?>(null)
    /** Seconds left of the 100 Hz tone, or null when it is not playing. */
    val toneRemainingS: StateFlow<Int?> = _toneRemainingS.asStateFlow()

    // ---------------- Directional cue ----------------

    fun playCue(cue: Cue) {
        cueThread.execute {
            val pcm = when {
                cue.kind == ManeuverKind.SPEED_BUMP -> CueSynth.bump()
                cue.turn == Turn.LEFT -> CueSynth.turn(-1f)
                cue.turn == Turn.RIGHT -> CueSynth.turn(1f)
                else -> CueSynth.glide() // braking, downhill
            }
            val focus = requestFocus(null) ?: run { Log.w(TAG, "cue: focus denied"); return@execute }
            try {
                playStatic(pcm)
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
        val side = when (cue.turn) { Turn.LEFT -> "왼쪽"; Turn.RIGHT -> "오른쪽"; Turn.NONE -> "가운데" }
        note("예고음($side): ${cue.text}")
    }

    /** Plays the left and then the right turn sound, to check volume and stereo while parked. */
    fun preview() {
        cueThread.execute {
            val focus = requestFocus(null) ?: return@execute
            try {
                playStatic(CueSynth.turn(-1f))
                Thread.sleep(250)
                playStatic(CueSynth.turn(1f))
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
        note("예고음 미리 듣기: 왼쪽 → 오른쪽")
    }

    private fun playStatic(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(stereoFormat())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size / 2 * 1000L) / SR + 50)
            track.stop()
        } catch (e: Exception) {
            Log.w(TAG, "cue playback", e)
        } finally {
            track.release()
        }
    }

    // ---------------- 100 Hz tone ----------------

    val isTonePlaying: Boolean get() = tonePlaying

    fun startTone(seconds: Int = TONE_S) {
        if (tonePlaying) return
        tonePlaying = true
        toneCancel = false
        _toneRemainingS.value = seconds
        note("100Hz 사운드 ${seconds}초 시작")
        toneThread.execute { runTone(seconds) }
    }

    fun stopTone() {
        if (tonePlaying) toneCancel = true
    }

    private fun runTone(seconds: Int) {
        val focus = requestFocus { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) toneCancel = true
        }
        if (focus == null) {
            note("100Hz 사운드: 오디오 포커스 거부")
            finishTone()
            return
        }
        val chunk = SR / 10 // 100 ms
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(stereoFormat())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, chunk * 4 * 2))
            .build()
        val buf = ShortArray(chunk * 2)
        val total = seconds * SR
        val step = 2.0 * PI * TONE_HZ / SR
        var phase = 0.0
        var i = 0
        var fadeOutFrom = -1 // frame index where a cancel fade started
        var completed = false
        try {
            buf.fill(0)
            repeat(2) { track.write(buf, 0, buf.size) } // ~200 ms pre-roll silence
            track.play()
            while (i < total) {
                if (toneCancel && fadeOutFrom < 0) fadeOutFrom = i
                for (k in 0 until chunk) {
                    val idx = i + k
                    var env = min(1.0, min(idx.toDouble() / (FADE_S * SR), (total - idx).toDouble() / (FADE_S * SR)))
                    if (fadeOutFrom >= 0) env *= (1.0 - (idx - fadeOutFrom) / (0.3 * SR)).coerceAtLeast(0.0)
                    val s = (TONE_AMP * env.coerceAtLeast(0.0) * sin(phase) * Short.MAX_VALUE).toInt().toShort()
                    buf[k * 2] = s
                    buf[k * 2 + 1] = s
                    phase += step
                }
                if (phase > 2 * PI) phase %= 2 * PI
                track.write(buf, 0, buf.size)
                i += chunk
                _toneRemainingS.value = ((total - i) / SR) + 1
                if (fadeOutFrom >= 0 && i - fadeOutFrom > 0.3 * SR) break
            }
            completed = fadeOutFrom < 0
            track.stop()
        } catch (e: Exception) {
            Log.w(TAG, "tone playback", e)
        } finally {
            track.release()
            audio.abandonAudioFocusRequest(focus)
            note(if (completed) "100Hz 사운드 완료" else "100Hz 사운드 중지")
            finishTone()
        }
    }

    private fun finishTone() {
        tonePlaying = false
        toneCancel = false
        _toneRemainingS.value = null
    }

    // ---------------- Common ----------------

    private fun requestFocus(onChange: ((Int) -> Unit)?): AudioFocusRequest? {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { onChange?.invoke(it) }
            .build()
        return if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    }

    private fun stereoFormat() = AudioFormat.Builder()
        .setSampleRate(SR)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    fun release() {
        toneCancel = true
        cueThread.shutdown()
        toneThread.shutdown()
    }

    internal companion object {
        const val TAG = "SteadyRide"
        const val SR = CueSynth.SR
        const val PREROLL_S = CueSynth.PREROLL_S
        const val TONE_HZ = 100.0
        const val TONE_S = 60
        const val TONE_AMP = 0.85   // near full scale; final loudness is the car's volume (study: ~61-66 dBA)
        const val FADE_S = 1.5
    }
}
