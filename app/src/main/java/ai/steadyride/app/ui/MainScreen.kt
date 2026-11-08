package ai.steadyride.app.ui

import ai.steadyride.app.SourceMode
import ai.steadyride.app.UiState
import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.CueSource
import ai.steadyride.app.core.Phase
import ai.steadyride.app.core.RiskLevel
import ai.steadyride.app.vehicle.ManeuverKind
import ai.steadyride.app.vehicle.SeatPos
import ai.steadyride.app.vehicle.Turn
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object Palette {
    val bg = Color(0xFF0E1214)
    val panel = Color(0xFF182024)
    val panelHi = Color(0xFF212B30)
    val text = Color(0xFFEFF3F2)
    val muted = Color(0xFF8E9A9A)
    val accent = Color(0xFF5FD1C0)
    val calm = Color(0xFF6CCB8A)
    val watch = Color(0xFFF2C14E)
    val high = Color(0xFFF07A64)
}

private fun RiskLevel.color() = when (this) {
    RiskLevel.CALM -> Palette.calm
    RiskLevel.WATCH -> Palette.watch
    RiskLevel.HIGH -> Palette.high
}

@Composable
fun MainScreen(
    s: UiState,
    actionLog: List<String>,
    onMode: (SourceMode) -> Unit,
    onComfort: (Boolean?) -> Unit,
    onComfortRoute: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Palette.bg) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TopBar(s, onMode)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (maxWidth > maxHeight) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CuePanel(s, Modifier.weight(1.25f).fillMaxHeight())
                        SidePanel(s, actionLog, onComfort, onComfortRoute, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CuePanel(s, Modifier.fillMaxWidth().weight(1f))
                        SidePanel(s, actionLog, onComfort, onComfortRoute, Modifier.fillMaxWidth().weight(1.1f))
                    }
                }
            }
            SignalStrip(s)
        }
    }
}

@Composable
private fun TopBar(s: UiState, onMode: (SourceMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Steady Ride", color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Chip(s.comfort.phase.label, Palette.panelHi, Palette.text)
        if (s.vehicle.drivingRestricted) Chip("주행 규제 중", Palette.panelHi, Palette.watch)
        Spacer(Modifier.weight(1f))
        Segmented(SourceMode.entries.map { it.label }, s.mode.ordinal) { onMode(SourceMode.entries[it]) }
    }
}

// ---------------- Passenger cue ----------------

@Composable
private fun CuePanel(s: UiState, modifier: Modifier) {
    val cue = if (s.vehicle.hasPassenger) s.comfort.cue else null
    val calmUi = s.vehicle.drivingRestricted // no motion while the driver may be looking

    // One short pulse when a new cue appears, only while parked/stopped (guide: no animation while driving).
    val pulse = remember { Animatable(0f) }
    val cueKey = cue?.let { "${it.source}${it.turn}${it.kind}" }
    LaunchedEffect(cueKey, calmUi) {
        if (cueKey != null && !calmUi) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(700))
        } else {
            pulse.snapTo(0f)
        }
    }

    val tint = when {
        cue == null -> Palette.muted
        cue.source == CueSource.NAVI -> Palette.accent
        else -> Palette.watch
    }

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.panel)
            .border(2.dp, tint.copy(alpha = 0.25f + 0.5f * pulse.value), RoundedCornerShape(28.dp)),
    ) {
        // Peripheral edge glow on the side of the upcoming turn (static).
        if (cue != null && cue.turn != Turn.NONE) {
            val left = cue.turn == Turn.LEFT
            Box(
                Modifier
                    .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(56.dp)
                    .background(
                        Brush.horizontalGradient(
                            if (left) listOf(tint.copy(alpha = 0.55f), Color.Transparent)
                            else listOf(Color.Transparent, tint.copy(alpha = 0.55f)),
                        ),
                    ),
            )
        }

        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("동승자 예고", color = Palette.muted, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            if (cue == null) {
                Text(
                    if (!s.vehicle.hasPassenger) "동승자가 없어요" else "당분간 큰 움직임 없음",
                    color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                )
                nextBeyondHorizon(s)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.muted, fontSize = 20.sp)
                }
            } else {
                CueGlyph(cue, tint, Modifier.size(160.dp))
                Spacer(Modifier.height(18.dp))
                Text(cue.text, color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(cue.source.label, color = tint, fontSize = 18.sp)
            }
        }
    }
}

private fun nextBeyondHorizon(s: UiState): String? {
    val m = s.vehicle.maneuvers.minByOrNull { it.distanceM } ?: return null
    val dir = when (m.turn) { Turn.LEFT -> "왼쪽 "; Turn.RIGHT -> "오른쪽 "; Turn.NONE -> "" }
    return "%.0fm 앞 %s%s".format(m.distanceM, dir, m.kind.label)
}

/** Arrow for turns, bump arc for speed bumps, bar for braking, with a countdown ring. */
@Composable
private fun CueGlyph(cue: Cue, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.075f
        val frac = if (cue.secondsAhead > 0f) (cue.secondsAhead / 4f).coerceIn(0f, 1f) else 0f
        drawCircle(tint.copy(alpha = 0.15f), radius = w / 2 - stroke, style = Stroke(stroke))
        if (frac > 0f) {
            drawArc(
                tint, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                topLeft = Offset(stroke, stroke), size = Size(w - 2 * stroke, w - 2 * stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val c = w / 2
        val path = Path()
        when {
            cue.kind == ManeuverKind.SPEED_BUMP || cue.kind == ManeuverKind.DOWNHILL -> {
                path.moveTo(c - w * 0.24f, c + w * 0.1f)
                path.quadraticTo(c, c - w * 0.22f, c + w * 0.24f, c + w * 0.1f)
            }
            cue.turn == Turn.NONE -> {
                path.moveTo(c - w * 0.2f, c); path.lineTo(c + w * 0.2f, c)
            }
            else -> {
                val d = if (cue.turn == Turn.RIGHT) 1f else -1f
                path.moveTo(c - d * w * 0.14f, c + w * 0.2f)
                path.lineTo(c - d * w * 0.14f, c - w * 0.04f)
                path.lineTo(c + d * w * 0.16f, c - w * 0.04f)
                path.moveTo(c + d * w * 0.06f, c - w * 0.14f)
                path.lineTo(c + d * w * 0.16f, c - w * 0.04f)
                path.lineTo(c + d * w * 0.06f, c + w * 0.06f)
            }
        }
        drawPath(path, Palette.text, style = Stroke(stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------- Side panel ----------------

@Composable
private fun SidePanel(
    s: UiState,
    actionLog: List<String>,
    onComfort: (Boolean?) -> Unit,
    onComfortRoute: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RiskCard(s)
        ComfortCard(s, onComfort, onComfortRoute)
        if (s.comfort.phase == Phase.DRIVING) {
            LogCard("차량 개입 기록", actionLog)
        } else {
            DriverReport(s)
        }
    }
}

@Composable
private fun RiskCard(s: UiState) {
    val c = s.comfort
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("동승자 멀미 위험", color = Palette.muted, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${c.riskInt}", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(" / 100", color = Palette.muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Chip(c.level.label, c.level.color().copy(alpha = 0.18f), c.level.color())
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Palette.panelHi)) {
            Box(Modifier.fillMaxWidth(c.risk / 100f).fillMaxHeight().clip(CircleShape).background(c.level.color()))
        }
        Spacer(Modifier.height(8.dp))
        Text("횡 %.2f G · 종 %.2f G".format(c.lateralG, c.longitudinalG), color = Palette.muted, fontSize = 15.sp)
    }
}

@Composable
private fun ComfortCard(s: UiState, onComfort: (Boolean?) -> Unit, onComfortRoute: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("멀미 완화 모드", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(if (s.comfortModeOn) "작동 중" else "대기", if (s.comfortModeOn) Palette.accent.copy(alpha = 0.2f) else Palette.panelHi, if (s.comfortModeOn) Palette.accent else Palette.muted)
        }
        Spacer(Modifier.height(12.dp))
        val idx = when (s.manualComfort) { null -> 0; true -> 1; false -> 2 }
        Segmented(listOf("자동", "켜기", "끄기"), idx) { onComfort(listOf(null, true, false)[it]) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SeatPos.entries.forEach { p ->
                val on = s.vehicle.occupied[p] == true
                Chip(p.label, if (on) Palette.panelHi else Color.Transparent, if (on) Palette.text else Palette.muted.copy(alpha = 0.6f))
            }
        }
        if (!s.vehicle.drivingRestricted || s.mode == SourceMode.DEMO) {
            Spacer(Modifier.height(12.dp))
            Text(
                "편안한 경로로 재탐색", color = Palette.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onComfortRoute() }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DriverReport(s: UiState) {
    Card {
        Text("운전자 리포트 · 정차 중에만 표시", color = Palette.muted, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text("급조향·급가감속 ${s.comfort.harshEvents}회", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        s.comfort.lastHarshEvent?.let {
            Text("최근: $it", color = Palette.muted, fontSize = 16.sp)
        }
        Text(
            if (s.comfort.harshEvents == 0) "동승자에게 편안한 주행이었어요." else "코너 진입 전 미리 감속하면 뒷좌석이 편해져요.",
            color = Palette.text, fontSize = 17.sp,
        )
    }
}

@Composable
private fun LogCard(title: String, lines: List<String>) {
    Card {
        Text(title, color = Palette.muted, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        if (lines.isEmpty()) Text("아직 개입 없음", color = Palette.muted.copy(alpha = 0.7f), fontSize = 16.sp)
        lines.take(4).forEach { Text(it, color = Palette.text, fontSize = 15.sp) }
    }
}

@Composable
private fun SignalStrip(s: UiState) {
    val v = s.vehicle
    val parts = listOf(
        "차속 " + (v.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"),
        "조향 " + (v.steeringDeg?.let { "%.0f°".format(it) } ?: "—"),
        "브레이크 " + (v.brakePressed?.let { if (it) "밟음" else "뗌" } ?: "—"),
        "기어 " + (v.gear?.removePrefix("GEAR_") ?: "—"),
        "내비 예고 ${v.maneuvers.size}건",
    )
    Column {
        Text(parts.joinToString("   ·   "), color = Palette.muted, fontSize = 15.sp)
        if (v.errors.isNotEmpty()) {
            Text("연결 문제: " + v.errors.first(), color = Palette.high.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
        }
    }
}

// ---------------- Small building blocks ----------------

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.panel).padding(20.dp),
    ) { content() }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.panelHi).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                color = if (on) Palette.bg else Palette.text,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Palette.accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .alpha(1f),
            )
        }
    }
}
