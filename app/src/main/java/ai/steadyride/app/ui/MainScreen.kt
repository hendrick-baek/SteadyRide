package ai.steadyride.app.ui

import ai.steadyride.app.R
import ai.steadyride.app.SourceMode
import ai.steadyride.app.UiState
import ai.steadyride.app.core.CoachTip
import ai.steadyride.app.core.CoachTopic
import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.CueSource
import ai.steadyride.app.core.Phase
import ai.steadyride.app.core.RiskLevel
import ai.steadyride.app.core.RoutePlanner
import ai.steadyride.app.core.sicknessGrade
import ai.steadyride.app.core.ScenarioPhase
import ai.steadyride.app.vehicle.AmbientLook
import ai.steadyride.app.vehicle.ManeuverKind
import ai.steadyride.app.vehicle.SeatPos
import ai.steadyride.app.vehicle.Turn
import android.view.Display
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

// =====================================================================================
// Design language: near-black cabin surfaces, hairline borders, light-weight large type,
// monochrome controls (white = selected) and color used only for meaning
// (risk level, upcoming motion, live ambient light).
// =====================================================================================

private object Ink {
    val bg = Color(0xFF050607)
    val bgTop = Color(0xFF0D1114)
    val surface = Color(0x0DFFFFFF)     // white 5 %
    val surfaceHi = Color(0x17FFFFFF)   // white 9 %
    val hairline = Color(0x1AFFFFFF)    // white 10 %
    val hairlineHi = Color(0x33FFFFFF)
    val text = Color(0xFFF2F4F5)
    val text2 = Color(0xFFA7AFB4)
    val text3 = Color(0xFF656D72)
    val navi = Color(0xFF8FD3E8)        // ice blue: route-based foresight
    val calm = Color(0xFF7FD6B7)
    val watch = Color(0xFFE9C27A)
    val high = Color(0xFFEE8C74)
}

/** Hyundai Harmony: the brand typeface of Hyundai Motor Group (L / M / B cuts). */
private val Harmony = FontFamily(
    Font(R.font.harmony_light, FontWeight.ExtraLight),
    Font(R.font.harmony_light, FontWeight.Light),
    Font(R.font.harmony_medium, FontWeight.Normal),
    Font(R.font.harmony_medium, FontWeight.Medium),
    Font(R.font.harmony_bold, FontWeight.SemiBold),
    Font(R.font.harmony_bold, FontWeight.Bold),
)

private fun RiskLevel.color() = when (this) {
    RiskLevel.CALM -> Ink.calm
    RiskLevel.WATCH -> Ink.watch
    RiskLevel.HIGH -> Ink.high
}

private fun ambientColor(look: AmbientLook?) = look?.let { Color(0xFF000000 or it.rgb.toLong()) }

/** Small letter-spaced caption, the "eyebrow" above values. */
@Composable
private fun Caption(text: String, color: Color = Ink.text3, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 15.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

@Composable
private fun T(
    text: String,
    size: TextUnit,
    color: Color = Ink.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text, color = color, textAlign = align, maxLines = maxLines, modifier = modifier,
        style = LocalTextStyle.current.merge(TextStyle(fontSize = size, fontWeight = weight, lineHeight = size * 1.28f)),
    )
}

private enum class Detail(val title: String) {
    COMFORT("멀미 완화 모드"), MOTION("스테디 플로우 · 실내 조명"), SOUND("공간 사운드"), ROUTE("멀미 최소화 경로")
}

private class Actions(
    val onMode: (SourceMode) -> Unit,
    val onComfort: (Boolean?) -> Unit,
    val onRouteSearch: () -> Unit,
    val onRouteTolerance: (Int) -> Unit,
    val onCueSound: (Boolean) -> Unit,
    val onTone: () -> Unit,
    val onPreview: () -> Unit,
    val onAmbient: (Boolean) -> Unit,
    val onNamyangScenario: () -> Unit,
    val onNavigationScreen: () -> Unit,
    val onRouteResearch: () -> Unit,
)

private class MotionSettings(
    val on: Boolean,
    val seasonPick: Int,
    val season: Season,
    val allowed: Boolean,
    val onToggle: (Boolean) -> Unit,
    val onSeason: (Int) -> Unit,
)

@Composable
fun MainScreen(
    s: UiState,
    actionLog: List<String>,
    ambientLook: AmbientLook?,
    onAmbient: (Boolean) -> Unit,
    onMode: (SourceMode) -> Unit,
    onComfort: (Boolean?) -> Unit,
    onRouteSearch: () -> Unit,
    onRouteTolerance: (Int) -> Unit,
    onCueSound: (Boolean) -> Unit,
    onTone: () -> Unit,
    onPreviewSound: () -> Unit,
    onNamyangScenario: () -> Unit,
    onNavigationScreen: () -> Unit,
    onRouteResearch: () -> Unit,
) {
    var flowOn by rememberSaveable { mutableStateOf(true) }
    var seasonPick by rememberSaveable { mutableIntStateOf(-1) } // -1 = follow the calendar
    var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
    val season = if (seasonPick < 0) Season.now() else Season.entries[seasonPick]
    val onPassengerDisplay = (LocalView.current.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY
    // Guide: no moving graphics where the driver may look while driving. Steady Flow stays on
    // the passenger display; the demo plays them anyway so the effect can be shown.
    val motionAllowed = !s.vehicle.drivingRestricted || onPassengerDisplay || s.mode == SourceMode.DEMO
    val motion = MotionSettings(flowOn, seasonPick, season, motionAllowed, { flowOn = it }, { seasonPick = it })
    val actions = Actions(onMode, onComfort, onRouteSearch, onRouteTolerance, onCueSound, onTone, onPreviewSound, onAmbient,
        onNamyangScenario, onNavigationScreen, onRouteResearch)

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Harmony)) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink.bgTop, Ink.bg, Ink.bg))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            TopBar(s, actions)
            Spacer(Modifier.height(24.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val right: @Composable (Modifier) -> Unit = { m ->
                    RightColumn(s, ambientLook, motion, actions, detail, { detail = it }, m)
                }
                if (maxWidth > maxHeight) {
                    Row {
                        Stage(s, ambientLook, Modifier.weight(1.3f).fillMaxHeight())
                        Spacer(Modifier.width(28.dp))
                        right(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column {
                        Stage(s, ambientLook, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(24.dp))
                        right(Modifier.fillMaxWidth().weight(1.15f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            BottomLine(s, actionLog)
        }
        if (motion.on && motion.allowed && s.vehicle.hasPassenger) {
            SteadyFlowOverlay(
                lateralG = s.comfort.lateralG,
                longitudinalG = s.comfort.longitudinalG,
                season = season,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun TopBar(s: UiState, a: Actions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("STEADY RIDE", color = Ink.text, fontSize = 19.sp, letterSpacing = 5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(28.dp))
        val phaseColor = when (s.comfort.phase) {
            Phase.DRIVING -> Ink.calm
            Phase.STOPPED -> Ink.watch
            Phase.PARKED -> Ink.text3
        }
        Dot(phaseColor)
        Spacer(Modifier.width(8.dp))
        T(s.comfort.phase.label, 17.sp, Ink.text2)
        if (s.vehicle.drivingRestricted) {
            Spacer(Modifier.width(22.dp))
            Dot(Ink.watch)
            Spacer(Modifier.width(8.dp))
            T("주행 규제", 17.sp, Ink.text2)
        }
        Spacer(Modifier.weight(1f))
        // Source switch as quiet text tabs.
        SourceMode.entries.forEach { m ->
            val on = m == s.mode
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { a.onMode(m) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T(m.label, 16.sp, if (on) Ink.text else Ink.text3, if (on) FontWeight.Medium else FontWeight.Normal)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(22.dp).height(2.dp).clip(CircleShape).background(if (on) Ink.text else Color.Transparent))
            }
        }
    }
}

@Composable
private fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------- Stage (left)

/**
 * The passenger's main view: what the car is about to do. Its glow takes the live
 * cabin ambient color, so screen and cabin light read as one system.
 */
@Composable
private fun Stage(s: UiState, look: AmbientLook?, modifier: Modifier) {
    val cue = if (s.vehicle.hasPassenger) s.comfort.cue else null
    val tint = when {
        cue == null -> Ink.text2
        cue.source == CueSource.NAVI -> Ink.navi
        else -> Ink.watch
    }
    val glowTarget = ambientColor(look)?.takeIf { look!!.brightness > 0 } ?: tint
    val glow by animateColorAsState(glowTarget, tween(900), label = "glow")
    val glowAlpha by animateFloatAsState(if (cue != null) 0.30f else 0.16f, tween(900), label = "glowA")
    val shape = RoundedCornerShape(36.dp)

    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Ink.surface)
            .background(Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent), radius = 1100f, center = Offset(600f, 120f)))
            .border(1.dp, Ink.hairline, shape)
            .padding(36.dp),
    ) {
        // Short stage (split screen next to the navigation): smaller glyph, no seat map.
        val compact = maxHeight < 520.dp
        Caption("다음 움직임", modifier = Modifier.align(Alignment.TopStart))

        Column(
            Modifier.fillMaxSize().padding(vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                cue != null -> CueHero(cue, tint, compact)
                s.comfort.phase == Phase.PARKED -> ParkedHero(s)
                else -> CalmHero(s)
            }
        }

        if (!compact) SeatMap(s, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun CueHero(cue: Cue, tint: Color, compact: Boolean) {
    CueGlyph(cue, tint, Modifier.size(if (compact) 150.dp else 210.dp))
    Spacer(Modifier.height(34.dp))
    val (whenText, whatText) = cueParts(cue)
    T(whenText, 26.sp, tint, FontWeight.Normal)
    Spacer(Modifier.height(6.dp))
    T(whatText, 54.sp, Ink.text, FontWeight.Light, TextAlign.Center)
    Spacer(Modifier.height(14.dp))
    Caption(if (cue.source == CueSource.NAVI) "경로 기반 예측" else "실시간 차량 신호", tint.copy(alpha = 0.8f))
}

@Composable
private fun CalmHero(s: UiState) {
    T(if (s.vehicle.hasPassenger) "당분간 큰 움직임 없음" else "동승자 없음", 22.sp, Ink.text2)
    val tip = s.coach?.takeIf { s.comfort.phase == Phase.DRIVING || it.topic != CoachTopic.LEARNING }
    if (tip != null) CoachBlock(tip)
    // The next maneuver beyond the cue horizon, centered under the coaching.
    nextBeyondHorizon(s)?.let {
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("다음", modifier = Modifier.padding(end = 12.dp))
            T(it, 22.sp, Ink.text2)
        }
    }
}

@Composable
private fun CoachBlock(tip: CoachTip) {
    Spacer(Modifier.height(44.dp))
    val color = when (tip.topic) {
        CoachTopic.SMOOTH -> Ink.calm
        CoachTopic.LEARNING -> Ink.text3
        else -> Ink.watch
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 7)
        Spacer(Modifier.width(10.dp))
        Caption("운전 코칭", color)
    }
    Spacer(Modifier.height(18.dp))
    T(tip.title, 40.sp, Ink.text, FontWeight.Light, TextAlign.Center)
    Spacer(Modifier.height(14.dp))
    T(tip.body, 19.sp, Ink.text2, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    tip.evidence?.let {
        Spacer(Modifier.height(18.dp))
        T(it, 15.sp, color)
    }
}

@Composable
private fun ParkedHero(s: UiState) {
    T("출발 준비 완료", 48.sp, Ink.text, FontWeight.Light)
    Spacer(Modifier.height(14.dp))
    val n = s.vehicle.occupied.count { it.value }
    T(if (n > 0) "동승자 ${n}명 탑승 · 출발하면 멀미 케어가 시작돼요" else "동승자가 타면 자동으로 준비해요", 19.sp, Ink.text2)
    Spacer(Modifier.height(30.dp))
    Caption("지난 주행")
    Spacer(Modifier.height(8.dp))
    T(
        if (s.comfort.harshEvents == 0) "동승자에게 편안한 주행이었어요"
        else "급조향·급가감속 ${s.comfort.harshEvents}회 · 코너 진입 전 미리 감속해 보세요",
        17.sp, Ink.text2,
    )
}

private fun cueParts(cue: Cue): Pair<String, String> {
    val what = when {
        cue.kind == ManeuverKind.SPEED_BUMP -> "과속방지턱"
        cue.kind == ManeuverKind.DOWNHILL -> "내리막"
        cue.kind == ManeuverKind.U_TURN -> "유턴"
        cue.source == CueSource.BRAKE -> "감속"
        cue.turn == Turn.LEFT -> "왼쪽 회전"
        cue.turn == Turn.RIGHT -> "오른쪽 회전"
        else -> "움직임"
    }
    val whenText = if (cue.secondsAhead >= 0.5f) "%.0f초 뒤".format(cue.secondsAhead) else "지금"
    return whenText to what
}

private fun nextBeyondHorizon(s: UiState): String? {
    val m = s.vehicle.maneuvers.minByOrNull { it.distanceM } ?: return null
    val dir = when (m.turn) { Turn.LEFT -> "왼쪽 "; Turn.RIGHT -> "오른쪽 "; Turn.NONE -> "" }
    return "%.0fm · %s%s".format(m.distanceM, dir, m.kind.label)
}

/** Thin-line glyph with a countdown ring: arrow for turns, arc for bumps, bar for braking. */
@Composable
private fun CueGlyph(cue: Cue, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val ring = 3.dp.toPx()
        val frac = if (cue.secondsAhead > 0f) (cue.secondsAhead / 4f).coerceIn(0f, 1f) else 0f
        drawCircle(Ink.hairline, radius = w / 2 - ring, style = Stroke(ring))
        drawCircle(tint.copy(alpha = 0.06f), radius = w / 2 - ring * 2)
        if (frac > 0f) {
            drawArc(
                tint, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                topLeft = Offset(ring, ring), size = Size(w - 2 * ring, w - 2 * ring),
                style = Stroke(ring * 1.4f, cap = StrokeCap.Round),
            )
        }
        val c = w / 2
        val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        when {
            cue.kind == ManeuverKind.SPEED_BUMP || cue.kind == ManeuverKind.DOWNHILL -> {
                p.moveTo(c - w * 0.26f, c + w * 0.08f)
                p.quadraticTo(c, c - w * 0.22f, c + w * 0.26f, c + w * 0.08f)
            }
            cue.turn == Turn.NONE -> {
                p.moveTo(c - w * 0.2f, c); p.lineTo(c + w * 0.2f, c)
            }
            else -> {
                val d = if (cue.turn == Turn.RIGHT) 1f else -1f
                p.moveTo(c - d * w * 0.12f, c + w * 0.22f)
                p.lineTo(c - d * w * 0.12f, c - w * 0.02f)
                p.quadraticTo(c - d * w * 0.12f, c - w * 0.10f, c - d * w * 0.04f, c - w * 0.10f)
                p.lineTo(c + d * w * 0.17f, c - w * 0.10f)
                p.moveTo(c + d * w * 0.07f, c - w * 0.20f)
                p.lineTo(c + d * w * 0.17f, c - w * 0.10f)
                p.lineTo(c + d * w * 0.07f, c)
            }
        }
        drawPath(p, Ink.text, style = stroke)
    }
}

/** Top view of the cabin with occupied passenger seats lit. */
@Composable
private fun SeatMap(s: UiState, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 44.dp, height = 74.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(Ink.hairlineHi, size = size, cornerRadius = CornerRadius(w * 0.42f), style = Stroke(1.5.dp.toPx()))
            val r = w * 0.12f
            fun seat(x: Float, y: Float, on: Boolean?) {
                when (on) {
                    null -> drawCircle(Ink.text3, r * 0.75f, Offset(x, y), style = Stroke(1.5.dp.toPx()))
                    true -> drawCircle(Ink.calm, r, Offset(x, y))
                    false -> drawCircle(Ink.hairlineHi, r, Offset(x, y))
                }
            }
            seat(w * 0.32f, h * 0.38f, null) // driver
            seat(w * 0.68f, h * 0.38f, s.vehicle.occupied[SeatPos.FRONT_PASSENGER])
            seat(w * 0.32f, h * 0.68f, s.vehicle.occupied[SeatPos.REAR_LEFT])
            seat(w * 0.68f, h * 0.68f, s.vehicle.occupied[SeatPos.REAR_RIGHT])
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Caption("동승자")
            Spacer(Modifier.height(4.dp))
            val seats = SeatPos.entries.filter { s.vehicle.occupied[it] == true }.joinToString(" · ") { it.label }
            T(seats.ifEmpty { "없음" }, 16.sp, Ink.text2)
        }
    }
}

// ---------------------------------------------------------------- Right column

@Composable
private fun RightColumn(
    s: UiState,
    look: AmbientLook?,
    motion: MotionSettings,
    a: Actions,
    detail: Detail?,
    open: (Detail?) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (detail == null) {
            ScenarioCard(s, a)
            Spacer(Modifier.height(26.dp))
            RiskGauge(s)
            Spacer(Modifier.height(26.dp))
            Tiles(s, look, motion, a, open)
        } else {
            DetailPanel(detail, s, look, motion, a) { open(null) }
        }
    }
}

@Composable
private fun RiskGauge(s: UiState) {
    val c = s.comfort
    val color = c.level.color()
    val sweep by animateFloatAsState(240f * (c.risk / 100f), tween(600), label = "risk")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 9.dp.toPx()
                val tl = Offset(st, st)
                val sz = Size(size.width - 2 * st, size.height - 2 * st)
                drawArc(Ink.hairline, 150f, 240f, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
                if (sweep > 0.5f) {
                    drawArc(
                        Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))),
                        150f, sweep, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("${c.riskInt}", 80.sp, Ink.text, FontWeight.ExtraLight)
                T(c.level.label, 18.sp, color)
            }
        }
        Spacer(Modifier.width(26.dp))
        Column {
            Caption("동승자 멀미 위험")
            Spacer(Modifier.height(14.dp))
            Metric("좌우", "%.2f G".format(c.lateralG))
            Spacer(Modifier.height(8.dp))
            Metric("앞뒤", "%.2f G".format(c.longitudinalG))
            Spacer(Modifier.height(8.dp))
            Metric("차속", s.vehicle.speedKmh?.let { "%.0f km/h".format(it) } ?: "—")
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label, 16.sp, Ink.text3, modifier = Modifier.width(52.dp))
        T(value, 23.sp, Ink.text, FontWeight.Light)
    }
}

@Composable
private fun Tiles(s: UiState, look: AmbientLook?, motion: MotionSettings, a: Actions, open: (Detail?) -> Unit) {
    val comfortValue = when (s.manualComfort) {
        null -> if (s.comfortModeOn) "자동 · 작동 중" else "자동 · 대기"
        true -> "켜짐"
        false -> "꺼짐"
    }
    val tone = s.toneRemainingS
    val r = s.route
    val routeValue = when {
        s.mode == SourceMode.LIVE && r.analysis != null -> "현재 경로 멀미 ${r.analysis.sicknessIndex}"
        else -> r.plan?.chosen?.let { "${it.label} +${r.plan.delayMin(it)}분" } ?: "+${r.toleranceMin}분 이내"
    }
    val rows = listOf(
        listOf(
            TileSpec(Icon.WIND, "완화 모드", comfortValue, s.comfortModeOn) { open(Detail.COMFORT) },
            TileSpec(Icon.PARTICLES, "스테디 플로우", if (!motion.on) "꺼짐" else if (!motion.allowed) "동승자 화면 전용" else motion.season.label + " · " + motion.season.particle, motion.on && motion.allowed) { open(Detail.MOTION) },
        ),
        listOf(
            TileSpec(Icon.LIGHT, "실내 조명", if (s.ambientOn) "움직임 연동" else "꺼짐", s.ambientOn, swatch = ambientColor(look)?.takeIf { s.ambientOn }) { open(Detail.MOTION) },
            TileSpec(Icon.STEREO, "공간 사운드", if (s.cueSoundOn) "회전 1초 전" else "꺼짐", s.cueSoundOn) { open(Detail.SOUND) },
        ),
        listOf(
            TileSpec(Icon.SINE, "100Hz 사운드", if (tone != null) "재생 중 ${tone}초" else "탭하여 1분 재생", tone != null) { a.onTone() },
            TileSpec(Icon.ROUTE, "편안한 경로", if (r.searching) "경로 비교 중" else routeValue, r.applied != null) { open(Detail.ROUTE) },
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { FeatureTile(it, Modifier.weight(1f)) }
            }
        }
    }
}

private class TileSpec(
    val icon: Icon,
    val title: String,
    val value: String,
    val active: Boolean,
    val swatch: Color? = null,
    val onClick: () -> Unit,
)

@Composable
private fun FeatureTile(t: TileSpec, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val bg by animateColorAsState(if (t.active) Ink.surfaceHi else Ink.surface, tween(400), label = "tile")
    Column(
        modifier
            .heightIn(min = 152.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (t.active) Ink.hairlineHi else Ink.hairline, shape)
            .clickable(onClick = t.onClick)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(t.icon, if (t.active) Ink.text else Ink.text2, Modifier.size(34.dp))
            Spacer(Modifier.weight(1f))
            when {
                t.swatch != null -> Box(Modifier.size(14.dp).clip(CircleShape).background(t.swatch).border(1.dp, Ink.hairlineHi, CircleShape))
                t.active -> Dot(Ink.calm, 7)
            }
        }
        Spacer(Modifier.height(30.dp))
        T(t.title, 21.sp, Ink.text, FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        T(t.value, 17.sp, if (t.active) Ink.text2 else Ink.text3, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Detail panels

@Composable
private fun DetailPanel(d: Detail, s: UiState, look: AmbientLook?, m: MotionSettings, a: Actions, back: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = back).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Icon.BACK, Ink.text2, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(d.title, 26.sp, Ink.text, FontWeight.Light)
    }
    Spacer(Modifier.height(26.dp))
    when (d) {
        Detail.COMFORT -> ComfortDetail(s, a)
        Detail.MOTION -> MotionDetail(s, look, m, a)
        Detail.SOUND -> SoundDetail(s, a)
        Detail.ROUTE -> RouteDetail(s, a)
    }
}

@Composable
private fun Section(title: String, sub: String? = null, content: @Composable () -> Unit) {
    Caption(title)
    sub?.let { Spacer(Modifier.height(6.dp)); T(it, 15.sp, Ink.text3) }
    Spacer(Modifier.height(12.dp))
    content()
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun ComfortDetail(s: UiState, a: Actions) {
    Section("작동 방식", "멀미 위험이 '높음'이 되면 외기 유입, 풍량 증가, 조수석 통풍을 켜고 낮아지면 원래대로 돌려놓아요") {
        val idx = when (s.manualComfort) { null -> 0; true -> 1; false -> 2 }
        Pills(listOf("자동", "켜기", "끄기"), idx) { a.onComfort(listOf(null, true, false)[it]) }
    }
    Section("현재 상태") {
        T(if (s.comfortModeOn) "작동 중" else "대기", 30.sp, if (s.comfortModeOn) Ink.calm else Ink.text, FontWeight.Light)
    }
}

@Composable
private fun MotionDetail(s: UiState, look: AmbientLook?, m: MotionSettings, a: Actions) {
    Section("스테디 플로우", if (m.allowed) "화면 가장자리 입자가 차의 가속·제동·회전을 따라 흘러요" else "주행 중에는 동승자 화면에서만 표시돼요") {
        Pills(listOf("켬", "끔"), if (m.on) 0 else 1) { m.onToggle(it == 0) }
    }
    Section("계절 테마") {
        Pills(listOf("자동") + Season.entries.map { it.label }, m.seasonPick + 1) { m.onSeason(it - 1) }
    }
    Section("실내 조명 연동", "제동 앰버 · 가속 청록 · 회전 보라, 기동 2초 전부터 색이 먼저 바뀌어요") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pills(listOf("켬", "끔"), if (s.ambientOn) 0 else 1) { a.onAmbient(it == 0) }
            Spacer(Modifier.width(22.dp))
            val c = ambientColor(look)
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(c?.copy(alpha = 0.3f + 0.7f * (look?.brightness ?: 0) / 100f) ?: Ink.surface)
                    .border(1.dp, Ink.hairlineHi, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            T(look?.let { "밝기 ${it.brightness}" } ?: "조명 정보를 읽는 중", 16.sp, Ink.text2)
        }
    }
}

@Composable
private fun SoundDetail(s: UiState, a: Actions) {
    Section("방향 예고음", "회전 1초 전, 소리가 반대편에서 회전하는 쪽으로 지나가요") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pills(listOf("켬", "끔"), if (s.cueSoundOn) 0 else 1) { a.onCueSound(it == 0) }
            Spacer(Modifier.width(16.dp))
            GhostButton("미리 듣기", a.onPreview)
        }
    }
    Section("100Hz 사운드", "출발할 때 1분 자동 재생 · 최신 연구에서 멀미 완화 효과가 보고된 주파수") {
        val tone = s.toneRemainingS
        PrimaryButton(if (tone != null) "재생 중 ${tone}초 · 중지" else "1분 재생", a.onTone)
    }
}

private val TOLERANCES = listOf(5, 10, 15, 20)

@Composable
private fun ScenarioCard(s: UiState, a: Actions) {
    val scenario = s.scenario
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.surface)
        .border(1.dp, Ink.navi.copy(alpha = 0.3f), shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption("내비 시나리오", Ink.navi)
        T("현재 위치 → 남양연구소", 25.sp, Ink.text, FontWeight.Medium)
        T("화성시 현대연구소로 150 · 내비의 현재 위치에서 출발", 15.sp, Ink.text2)
        PrimaryButton(
            if (scenario.busy) "내비 확인 중…" else "남양연구소로 안내 시작",
            a.onNamyangScenario,
            enabled = !scenario.busy && !s.route.searching && !s.vehicle.drivingRestricted,
        )
        if (s.vehicle.drivingRestricted) T("정차 후 설정할 수 있어요", 15.sp, Ink.watch)
        T(scenario.message, 16.sp, when (scenario.phase) {
            ScenarioPhase.READY -> Ink.calm
            ScenarioPhase.ERROR -> Ink.watch
            else -> Ink.text2
        })
        if (scenario.phase == ScenarioPhase.READY) {
            T("%.1f km · 약 %d분".format((scenario.distanceM ?: 0) / 1000.0,
                ((scenario.durationS ?: 0) + 59) / 60), 22.sp, Ink.navi)
            T("경로 확인 시점의 내비 예상값", 14.sp, Ink.text3)
        }
        if (s.mode == SourceMode.LIVE && !scenario.busy) {
            GhostButton("내비 화면 열기", a.onNavigationScreen)
        }
    }
}

@Composable
private fun RouteDetail(s: UiState, a: Actions) {
    val r = s.route
    // Korea: settings are locked while driving (NO_SETUP); the demo stays editable for presenting.
    val canEdit = !s.vehicle.drivingRestricted || s.mode == SourceMode.DEMO
    ScenarioCard(s, a)
    Spacer(Modifier.height(24.dp))
    if (s.mode == SourceMode.LIVE) {
        LiveRouteAnalysis(s, a, canEdit)
        return
    }
    Section("허용 도착 지연", "최단 경로보다 이만큼까지 늦어도 괜찮아요") {
        if (canEdit) {
            Pills(TOLERANCES.map { "+${it}분" }, TOLERANCES.indexOf(r.toleranceMin).coerceAtLeast(0)) { a.onRouteTolerance(TOLERANCES[it]) }
        } else {
            T("+${r.toleranceMin}분 · 주행 중에는 변경할 수 없어요", 17.sp, Ink.text2)
        }
    }
    PrimaryButton(
        when {
            r.searching -> "경로 비교 중 ${r.progress?.let { "${it.first}/${it.second}" } ?: ""}"
            r.plan != null -> "다시 탐색"
            else -> "멀미 적은 경로 탐색"
        },
        enabled = !r.searching && !s.scenario.busy,
        onClick = a.onRouteSearch,
    )
    r.plan?.let { plan ->
        Spacer(Modifier.height(22.dp))
        plan.candidates.forEach { c ->
            val chosen = c.option == plan.chosen?.option
            val within = plan.withinTolerance(c)
            val delay = plan.delayMin(c)
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(shape)
                    .background(if (chosen) Ink.surfaceHi else Color.Transparent)
                    .border(1.dp, if (chosen) Ink.hairlineHi else Color.Transparent, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chosen) Dot(Ink.calm) else Spacer(Modifier.width(8.dp))
                Spacer(Modifier.width(14.dp))
                T(c.label, 18.sp, if (within) Ink.text else Ink.text3, if (chosen) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                T(
                    when {
                        !within -> "+${delay}분 · 범위 초과"
                        delay == 0 -> "최단 · 멀미 ${c.sicknessIndex}"
                        else -> "+${delay}분 · 멀미 ${c.sicknessIndex}"
                    },
                    16.sp, if (chosen) Ink.calm else if (within) Ink.text2 else Ink.text3,
                )
            }
        }
    }
    r.message?.let {
        Spacer(Modifier.height(16.dp))
        T(it, 16.sp, Ink.text2)
    }
}

/** Appends the Korean particle that fits the last syllable (받침 → [withFinal], else [withoutFinal]). */
private fun josa(word: String, withFinal: String, withoutFinal: String): String {
    val c = word.lastOrNull() ?: return word + withoutFinal
    val hasFinal = c in '가'..'힣' && (c - '가') % 28 != 0
    return word + if (hasFinal) withFinal else withoutFinal
}

private fun gradeColor(index: Int) = when {
    index <= 30 -> Ink.calm
    index <= 70 -> Ink.watch
    else -> Ink.high
}

/**
 * Test-vehicle mode: the navigation chooses the route (it ignores route-option requests from
 * apps on Pleos Connect), so Steady Ride scores whatever route it is guiding. Every route the
 * user picks in the navigation for the same destination is added to the comparison.
 */
@Composable
private fun LiveRouteAnalysis(s: UiState, a: Actions, canEdit: Boolean) {
    val r = s.route
    val cur = r.analysis
    Section("현재 경로 멀미 분석", "내비가 안내하는 경로의 회전·과속방지턱을 분석해요") {
        if (cur == null) {
            T(if (r.analyzing) "경로를 분석하고 있어요…" else "내비에서 목적지를 설정하면 자동으로 분석해요", 18.sp, Ink.text2)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                T("${cur.sicknessIndex}", 56.sp, Ink.text, FontWeight.ExtraLight)
                Spacer(Modifier.width(12.dp))
                T(sicknessGrade(cur.sicknessIndex), 20.sp, gradeColor(cur.sicknessIndex), modifier = Modifier.padding(bottom = 10.dp))
            }
            T("${cur.label} · %.1f km · 약 %d분".format(cur.distanceM / 1000.0, (cur.etaS + 59) / 60), 17.sp, Ink.text)
            Spacer(Modifier.height(4.dp))
            T("회전 ${cur.turns} · 급회전 ${cur.sharpTurns} · 유턴 ${cur.uTurns} · 과속방지턱 ${cur.bumps}", 15.sp, Ink.text2)
        }
    }
    // With a single route there is nothing to trade off, so the arrival-delay limit only
    // appears once the user has tried a second route in the navigation.
    if (r.history.size == 1) {
        Section("다른 경로와 비교하기") {
            T("내비를 열어 설정(⚙)에서 경로 옵션(유료도로 우회 등)을 바꾼 뒤 '바뀐 설정으로 다시 탐색'을 누르면 새 경로도 분석해 비교해 드려요.", 16.sp, Ink.text2)
        }
    }
    if (r.history.size >= 2) {
        val plan = RoutePlanner.choose(r.history, r.toleranceMin)
        Section("분석한 경로 비교", "내비에서 다른 경로를 고르면 자동으로 분석해 여기에 추가돼요") {
            // Arrival-delay limit, inline: decides which route gets the "추천" mark.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                T("추천 기준 · 가장 빠른 경로보다", 15.sp, Ink.text3, modifier = Modifier.padding(end = 12.dp))
                if (canEdit) {
                    Pills(TOLERANCES.map { "+${it}분" }, TOLERANCES.indexOf(r.toleranceMin).coerceAtLeast(0)) { a.onRouteTolerance(TOLERANCES[it]) }
                } else {
                    T("+${r.toleranceMin}분 이내", 16.sp, Ink.text)
                }
            }
            r.history.forEach { c ->
                val current = c == cur
                val best = c == plan.chosen && r.history.size > 1
                val within = plan.withinTolerance(c)
                val delay = plan.delayMin(c)
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(shape)
                        .background(if (current) Ink.surfaceHi else Color.Transparent)
                        .border(1.dp, if (current) Ink.hairlineHi else Ink.hairline, shape)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T(c.label, 18.sp, if (within) Ink.text else Ink.text3, if (current) FontWeight.Medium else FontWeight.Normal)
                        val tags = listOfNotNull(if (current) "안내 중" else null, if (best) "추천" else null)
                        if (tags.isNotEmpty()) T(tags.joinToString(" · "), 14.sp, if (best) Ink.calm else Ink.navi)
                    }
                    T(
                        when {
                            !within -> "+${delay}분 · 범위 초과"
                            delay == 0 -> "최단 · 멀미 ${c.sicknessIndex}"
                            else -> "+${delay}분 · 멀미 ${c.sicknessIndex}"
                        },
                        16.sp, if (within) gradeColor(c.sicknessIndex) else Ink.text3,
                    )
                }
            }
            val best = plan.chosen
            Spacer(Modifier.height(10.dp))
            T(
                when {
                    best == null -> "허용 시간 안에 들어오는 경로가 없어요."
                    best == cur -> "지금 안내 중인 경로가 허용 시간 안에서 가장 편해요."
                    plan.delayMin(best) <= 0 ->
                        "${josa(best.label, "이", "가")} 가장 빠르면서 멀미 지수도 ${best.sicknessIndex}로 더 낮아요. 내비에서 이 경로를 선택해 보세요."
                    else -> "${josa(best.label, "이", "가")} ${plan.delayMin(best)}분 더 걸리지만 멀미 지수가 ${best.sicknessIndex}로 더 낮아요. 내비에서 이 경로를 선택해 보세요."
                },
                16.sp, Ink.text2,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("내비 열기", a.onNavigationScreen)
        GhostButton(if (r.analyzing) "탐색·분석 중…" else "바뀐 설정으로 다시 탐색", a.onRouteResearch)
    }
}

// ---------------------------------------------------------------- Controls

/** Monochrome option pills: the selected one is solid white. */
@Composable
private fun Pills(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(26.dp)
            Box(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(if (on) Ink.text else Ink.surface)
                    .border(1.dp, if (on) Ink.text else Ink.hairline, shape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                T(label, 16.sp, if (on) Ink.bg else Ink.text2, if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(if (enabled) Ink.text else Ink.surfaceHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 17.sp, if (enabled) Ink.bg else Ink.text2, FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.heightIn(min = 52.dp).clip(shape).border(1.dp, Ink.hairlineHi, shape).clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, 16.sp, Ink.text) }
}

// ---------------------------------------------------------------- Bottom line

@Composable
private fun BottomLine(s: UiState, log: List<String>) {
    val v = s.vehicle
    Row(verticalAlignment = Alignment.CenterVertically) {
        val parts = listOf(
            "조향 " + (v.steeringDeg?.let { "%.0f°".format(it) } ?: "—"),
            "브레이크 " + (v.brakePressed?.let { if (it) "ON" else "OFF" } ?: "—"),
            "기어 " + (v.gear?.removePrefix("GEAR_")?.take(1) ?: "—"),
            "내비 예고 ${v.maneuvers.size}",
        )
        T(parts.joinToString("     "), 15.sp, Ink.text3)
        Spacer(Modifier.weight(1f))
        val latest = v.errors.firstOrNull()?.let { "연결 · $it" } ?: log.firstOrNull()
        latest?.let { T(it, 15.sp, if (v.errors.isNotEmpty()) Ink.high.copy(alpha = 0.8f) else Ink.text3, maxLines = 1) }
    }
}

// ---------------------------------------------------------------- Line icons

private enum class Icon { WIND, PARTICLES, LIGHT, STEREO, SINE, ROUTE, BACK }

@Composable
private fun LineIcon(icon: Icon, color: Color, modifier: Modifier) {
    Canvas(modifier) { drawIcon(icon, color) }
}

private fun DrawScope.drawIcon(icon: Icon, color: Color) {
    val w = size.width
    val h = size.height
    val st = Stroke(w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val p = Path()
    when (icon) {
        Icon.WIND -> {
            p.moveTo(w * 0.08f, h * 0.36f); p.lineTo(w * 0.62f, h * 0.36f)
            p.cubicTo(w * 0.9f, h * 0.36f, w * 0.9f, h * 0.05f, w * 0.66f, h * 0.1f)
            p.moveTo(w * 0.08f, h * 0.58f); p.lineTo(w * 0.78f, h * 0.58f)
            p.cubicTo(w * 1.02f, h * 0.58f, w * 1.0f, h * 0.9f, w * 0.76f, h * 0.88f)
            p.moveTo(w * 0.2f, h * 0.78f); p.lineTo(w * 0.48f, h * 0.78f)
            drawPath(p, color, style = st)
        }
        Icon.PARTICLES -> {
            drawCircle(color, w * 0.12f, Offset(w * 0.72f, h * 0.28f))
            drawCircle(color, w * 0.09f, Offset(w * 0.46f, h * 0.55f))
            drawCircle(color, w * 0.06f, Offset(w * 0.24f, h * 0.78f))
            p.moveTo(w * 0.1f, h * 0.42f); p.lineTo(w * 0.3f, h * 0.26f)
            p.moveTo(w * 0.62f, h * 0.84f); p.lineTo(w * 0.86f, h * 0.64f)
            drawPath(p, color.copy(alpha = 0.6f), style = st)
        }
        Icon.LIGHT -> {
            p.moveTo(w * 0.08f, h * 0.72f); p.quadraticTo(w * 0.5f, h * 0.5f, w * 0.92f, h * 0.72f)
            drawPath(p, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round))
            val rays = Path()
            rays.moveTo(w * 0.5f, h * 0.12f); rays.lineTo(w * 0.5f, h * 0.32f)
            rays.moveTo(w * 0.2f, h * 0.24f); rays.lineTo(w * 0.3f, h * 0.4f)
            rays.moveTo(w * 0.8f, h * 0.24f); rays.lineTo(w * 0.7f, h * 0.4f)
            drawPath(rays, color.copy(alpha = 0.7f), style = st)
        }
        Icon.STEREO -> {
            drawCircle(color, w * 0.08f, Offset(w * 0.5f, h * 0.5f))
            for (k in 1..2) {
                val r = w * (0.2f + 0.16f * k)
                drawArc(color.copy(alpha = 1f - 0.3f * k), 140f, 80f, false, Offset(w / 2 - r, h / 2 - r), Size(2 * r, 2 * r), style = st)
                drawArc(color.copy(alpha = 1f - 0.3f * k), -40f, 80f, false, Offset(w / 2 - r, h / 2 - r), Size(2 * r, 2 * r), style = st)
            }
        }
        Icon.SINE -> {
            for (i in 0..40) {
                val x = w * (0.06f + 0.88f * i / 40f)
                val y = h * (0.5f - 0.28f * sin(2 * PI * i / 40.0 * 1.5).toFloat())
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, color, style = st)
        }
        Icon.ROUTE -> {
            p.moveTo(w * 0.2f, h * 0.86f)
            p.cubicTo(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.62f, w * 0.8f, h * 0.2f)
            drawPath(p, color, style = st)
            drawCircle(color, w * 0.09f, Offset(w * 0.2f, h * 0.86f))
            drawCircle(color, w * 0.11f, Offset(w * 0.8f, h * 0.16f), style = Stroke(w * 0.07f))
        }
        Icon.BACK -> {
            p.moveTo(w * 0.62f, h * 0.15f); p.lineTo(w * 0.28f, h * 0.5f); p.lineTo(w * 0.62f, h * 0.85f)
            drawPath(p, color, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val Season.particle: String
    get() = when (this) {
        Season.SPRING -> "꽃잎"
        Season.SUMMER -> "물방울"
        Season.AUTUMN -> "단풍잎"
        Season.WINTER -> "눈송이"
    }
