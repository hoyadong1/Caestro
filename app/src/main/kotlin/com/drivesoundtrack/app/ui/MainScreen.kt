package com.drivesoundtrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.drivesoundtrack.app.audio.Soundscapes
import kotlin.math.roundToInt

private val LAYER_NAMES = listOf("베이스", "드럼", "화음", "아르페지오", "선율", "반짝임")

/**
 * The parked screen. Soundscape choice and demo controls lock while driving
 * ([restricted] or a live vehicle speed of 5 km/h or more); audio keeps adapting in the background.
 */
@Composable
fun MainScreen(status: PlayerStatus, restricted: Boolean, canRequestPermission: Boolean, actions: PlayerActions) {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val screenHeight = config.screenHeightDp.dp
    val canConfigure = !restricted && (status.scenarioEnabled || !status.carAvailable || status.kmh < 5)
    val scene = Soundscapes.all[status.preset]
    val accent = accentColor(scene.accent)

    MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
        // Landscape (the car display) fits on one screen without scrolling: the stage and the
        // soundscape cards share the height that is left. Portrait falls back to a scrolling page.
        Column(
            Modifier.fillMaxSize().background(Palette.Background)
                .then(if (landscape) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 20.dp)
        ) {
            Header(status)
            Transport(status, actions)
            if (landscape) {
                Row(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp)) {
                    Column(Modifier.weight(1.15f).fillMaxHeight().padding(end = 20.dp)) {
                        Hero(status, accent, playing = status.playing && canConfigure, landscape = true,
                            modifier = Modifier.fillMaxWidth().weight(1f))
                        Metrics(status)
                        if (!status.carAvailable && !restricted) DemoControls(status, actions)
                        if (canRequestPermission) PermissionPrompt(actions)
                    }
                    Column(Modifier.weight(0.85f).fillMaxHeight()) {
                        Library(status.preset, canConfigure, landscape = true, actions = actions)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Hero(status, accent, playing = status.playing && canConfigure, landscape = false,
                        modifier = Modifier.fillMaxWidth().height(330.dp))
                    Metrics(status)
                    if (!status.carAvailable && !restricted) DemoControls(status, actions)
                    if (canRequestPermission) PermissionPrompt(actions)
                }
                Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
                    Library(status.preset, canConfigure, landscape = false, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun Label(
    text: String, size: TextUnit, color: Color, bold: Boolean = false,
    spacing: TextUnit = TextUnit.Unspecified, modifier: Modifier = Modifier,
) = Text(text, modifier, color = color, fontSize = size, letterSpacing = spacing,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable
private fun Header(status: PlayerStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("DRIVE / SOUNDTRACK", 23.sp, Color.White, bold = true)
            Label("ADAPTIVE DRIVING SCORE", 11.sp, Palette.Muted, bold = true, spacing = 0.13.em)
        }
        val source = when {
            status.scenarioEnabled -> "●  시연 주행"
            status.carAvailable -> "●  차량 속도 신호"
            else -> "●  데모 속도 입력"
        }
        Label(source, 12.sp, if (status.carAvailable) Palette.LiveSignal else Palette.DemoSignal, bold = true)
    }
}

/** Play/pause is always available (also while driving); the parked scenario only on the Pleos emulator. */
@Composable
private fun Transport(status: PlayerStatus, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val playing = status.playing
        Button(
            onClick = actions::togglePlay,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (playing) Palette.ScenarioButton else MaterialTheme.colorScheme.primary,
                contentColor = if (playing) Color.White else Palette.Background),
        ) { Text(if (playing) "❚❚  일시정지" else "▶  재생", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (status.scenarioAvailable || status.scenarioEnabled) Button(
            onClick = actions::toggleScenario,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.ScenarioButton, contentColor = Color.White),
        ) { Text(if (status.scenarioEnabled) "■  시연 시나리오 종료" else "◷  시연 시나리오 (0→130 km/h)", fontSize = 15.sp) }
    }
}

@Composable
private fun Hero(status: PlayerStatus, accent: Color, playing: Boolean, landscape: Boolean, modifier: Modifier) {
    val scene = Soundscapes.all[status.preset]
    Box(modifier) {
        InstrumentStage(status, accent, playing, Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 22.dp, end = 26.dp)) {
            Label("NOW PLAYING  /  ORIGINAL SCORE", 11.sp, Palette.Muted, bold = true, spacing = 0.12.em)
            Label(scene.name.uppercase(), if (landscape) 43.sp else 36.sp, Color.White, bold = true,
                modifier = Modifier.padding(top = 7.dp))
            Label(scene.character.uppercase(), 13.sp, Palette.Genre, bold = true, spacing = 0.1.em)
        }
        Label(phaseText(status), 14.sp, Color.White, bold = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 16.dp)
                .background(Palette.Chip, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

private fun phaseText(s: PlayerStatus): String = when {
    s.bassSolo -> "베이스 솔로 · ${s.kmh.roundToInt()} km/h"
    s.playing -> LAYER_NAMES.filterIndexed { i, _ -> s.layerMask and (1 shl i) != 0 }.joinToString("  +  ")
    else -> "재생 대기"
}

@Composable
private fun Metrics(status: PlayerStatus) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("SPEED", status.kmh.roundToInt().toString(), "km/h", Modifier.weight(1f))
        Metric("TEMPO", status.bpm.toString(), "BPM", Modifier.weight(1f))
        Metric("LAYERS", "${status.layers} / 6", "ACTIVE", Modifier.weight(1f))
    }
}

@Composable
private fun Metric(caption: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.height(86.dp).background(Palette.Panel, RoundedCornerShape(13.dp))
        .padding(start = 14.dp, top = 10.dp, end = 8.dp)) {
        Label(caption, 10.sp, Palette.Muted, bold = true, spacing = 0.11.em)
        Row(verticalAlignment = Alignment.Bottom) {
            Label(value, 26.sp, Color.White, bold = true)
            Label("  $unit", 10.sp, Palette.Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun DemoControls(status: PlayerStatus, actions: PlayerActions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var pedal by remember { mutableFloatStateOf(0f) }
    var brake by rememberSaveable { mutableStateOf(false) }
    var turn by rememberSaveable { mutableStateOf(false) }
    var wiper by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Label("시연 속도   ${status.kmh.roundToInt()} km/h", 13.sp, Palette.Muted)
        Slider(
            value = dragging ?: status.kmh.toFloat().coerceIn(0f, 120f),
            onValueChange = { dragging = it; actions.demoSpeed(it.roundToInt().toDouble()) },
            onValueChangeFinished = { dragging = null },
            valueRange = 0f..120f,
        )
        Label("시연 가속 페달", 12.sp, Palette.Muted)
        Slider(value = pedal, onValueChange = { pedal = it; actions.demoAccelerator(it.roundToInt().toDouble()) },
            valueRange = 0f..100f)
        Row(Modifier.fillMaxWidth()) {
            DemoToggle("브레이크", brake, Modifier.weight(1f)) { brake = !brake; actions.demoBrake(brake) }
            DemoToggle("좌측 방향", turn, Modifier.weight(1f)) { turn = !turn; actions.demoLeftTurn(turn) }
            DemoToggle("와이퍼", wiper, Modifier.weight(1f)) { wiper = !wiper; actions.demoWiper(wiper) }
        }
    }
}

@Composable
private fun DemoToggle(title: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.height(48.dp)) {
        Text(title, fontSize = 12.sp, color = if (active) MaterialTheme.colorScheme.primary else Color.White,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun PermissionPrompt(actions: PlayerActions) {
    Label("차량 속도 권한을 허용하면 차량 신호에 맞춰 재생합니다.", 12.sp, Palette.Muted)
    Button(actions::requestVehiclePermissions) { Text("차량 속도 연결") }
}

@Composable
private fun ColumnScope.Library(selected: Int, enabled: Boolean, landscape: Boolean, actions: PlayerActions) {
    Label("SOUNDSCAPES", 19.sp, Color.White, bold = true, spacing = 0.08.em)
    Label("정차 중 음악의 분위기를 선택하세요", 12.sp, Palette.Muted, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
    // In landscape the five cards split the remaining height evenly, so the list always fits.
    for ((i, scene) in Soundscapes.all.withIndex()) {
        val accent = accentColor(scene.accent)
        val shape = RoundedCornerShape(17.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 7.dp)
                .then(if (landscape) Modifier.weight(1f) else Modifier.height(72.dp))
                .alpha(if (enabled) 1f else 0.55f)
                .background(if (i == selected) Palette.PanelSelected else Palette.Panel, shape)
                .border(1.dp, if (i == selected) accent else Palette.PanelStroke, shape)
                .clickable(enabled = enabled) { actions.selectPreset(i) }
                .semantics { contentDescription = "${scene.name}, ${scene.character}, 사운드스케이프 선택" }
                .padding(start = 17.dp, top = 6.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(4.dp, 34.dp).background(accent))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(scene.name, if (landscape) 23.sp else 18.sp, Color.White, bold = true)
                Label(scene.character.uppercase(), if (landscape) 12.sp else 11.sp, Palette.Muted, spacing = 0.09.em)
            }
            if (landscape) SoundscapeGlyph(i, accent, Modifier.width(96.dp).fillMaxHeight())
            else Label("${scene.baseBpm.roundToInt()} BPM", 11.sp, Palette.Muted)
        }
    }
    Label("주행 중에는 모드 선택과 시연 조작이 잠깁니다.", 12.sp, Palette.Muted, modifier = Modifier.padding(top = 4.dp))
}
