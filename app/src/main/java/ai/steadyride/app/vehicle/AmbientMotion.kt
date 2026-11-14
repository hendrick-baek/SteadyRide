package ai.steadyride.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.steadyride.app.core.AmbientMood
import ai.steadyride.app.core.ComfortState
import ai.steadyride.app.core.Cue
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** What the cabin ambient light currently shows. */
data class AmbientLook(val rgb: Int, val brightness: Int)

/**
 * Drives the cabin ambient light from [AmbientMood]: color and brightness follow the
 * car's motion. Changes are smoothed (~0.5 s) so the light never flickers or flashes
 * at the driver, and the car is updated at most 5 times per second and only when the
 * look actually changes. The driver's own color, brightness and "auto color by drive
 * mode" setting are saved first and restored when the feature stops.
 */
class AmbientMotion(context: Context, private val note: (String) -> Unit) {

    private val vehicle: Vehicle? = runCatching { Vehicle(context).also { it.initialize() } }
        .onFailure { Log.w(TAG, "ambient: vehicle", it) }
        .getOrNull()

    private val _look = MutableStateFlow<AmbientLook?>(null)
    val look: StateFlow<AmbientLook?> = _look.asStateFlow()

    private var areas: List<SeatArea> = emptyList()
    private var base: AmbientLook? = null
    private var savedAuto: Boolean? = null
    private var active = false

    // Smoothed state (0..255 per channel, 0..100 brightness).
    private var r = 0f
    private var g = 0f
    private var b = 0f
    private var br = 0f
    private var lastSent: AmbientLook? = null
    private var lastSentNs = 0L

    init {
        val light = vehicle?.getLight()
        light?.checkAmbientLightBrightnessColorCapabilities({ _, list ->
            areas = list
            val a = list.firstOrNull() ?: return@checkAmbientLightBrightnessColorCapabilities
            light.getAmbientLightBrightnessColor(a, { color ->
                light.getAmbientLightBrightness(a, { bright ->
                    base = AmbientLook(color ?: 0xFFFFFF, bright ?: 40)
                    _look.value = base
                }, { base = AmbientLook(color ?: 0xFFFFFF, 40) })
            }, { Log.w(TAG, "ambient: read color", it) })
        }, { note("실내 조명 색 제어 미지원") })
    }

    fun update(c: ComfortState, cue: Cue?, enabled: Boolean, dtS: Float) {
        val baseLook = base ?: return
        if (!enabled) {
            if (active) restore()
            return
        }
        if (!active) start(baseLook)

        val (rgb, bright) = AmbientMood.target(c, cue, baseLook.rgb, baseLook.brightness)
        val k = 1f - exp(-dtS / SMOOTH_S)
        r += (((rgb shr 16) and 0xFF) - r) * k
        g += (((rgb shr 8) and 0xFF) - g) * k
        b += ((rgb and 0xFF) - b) * k
        br += (bright - br) * k

        val now = System.nanoTime()
        if (now - lastSentNs < MIN_INTERVAL_NS) return
        val look = AmbientLook(AmbientMood.rgb(r, g, b), br.toInt())
        val prev = lastSent
        if (prev != null && distance(prev, look) < 6 && abs(prev.brightness - look.brightness) < 2) return
        send(look)
        lastSentNs = now
    }

    /** Puts the driver's own ambient setting back. */
    fun restore() {
        if (!active) return
        active = false
        base?.let { send(it) }
        val light = vehicle?.getLight()
        savedAuto?.let { auto -> if (auto) light?.setAmbientColorAutoByDriveModeEnabled(true, {}, {}) }
        note("실내 조명을 원래 설정으로 복원")
    }

    fun release() {
        restore()
        runCatching { vehicle?.release() }
    }

    private fun start(baseLook: AmbientLook) {
        active = true
        r = ((baseLook.rgb shr 16) and 0xFF).toFloat()
        g = ((baseLook.rgb shr 8) and 0xFF).toFloat()
        b = (baseLook.rgb and 0xFF).toFloat()
        br = baseLook.brightness.toFloat()
        lastSent = null
        // A drive-mode color scheme would fight our colors; switch it off while we run.
        val light = vehicle?.getLight() ?: return
        light.getAmbientColorAutoByDriveModeEnabled({ on ->
            savedAuto = on
            if (on == true) light.setAmbientColorAutoByDriveModeEnabled(false, {}, {})
        }, {})
        note("실내 조명이 차의 움직임을 따라가요")
    }

    private fun send(look: AmbientLook) {
        val light = vehicle?.getLight() ?: return
        areas.forEach { a ->
            light.setAmbientLightBrightnessColor(a, look.rgb, { _, _ -> }, { Log.w(TAG, "ambient color", it) })
            light.setAmbientLightBrightness(a, look.brightness, { _, _ -> }, { Log.w(TAG, "ambient brightness", it) })
        }
        lastSent = look
        _look.value = look
    }

    private fun distance(a: AmbientLook, b: AmbientLook): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        return max(abs(ch(a.rgb, 16) - ch(b.rgb, 16)), max(abs(ch(a.rgb, 8) - ch(b.rgb, 8)), abs(ch(a.rgb, 0) - ch(b.rgb, 0))))
    }

    private companion object {
        const val TAG = "SteadyRide"
        const val SMOOTH_S = 0.5f
        const val MIN_INTERVAL_NS = 200_000_000L // at most 5 updates per second
    }
}
