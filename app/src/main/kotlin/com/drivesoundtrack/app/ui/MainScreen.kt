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
import androidx.compose.ui.text.style.TextAlign
import com.drivesoundtrack.app.audio.Albums
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.Soundscapes
import kotlin.math.roundToInt

private val LAYER_NAMES = listOf("베이스", "드럼", "화음", "아르페지오", "선율", "반짝임")
private val STAGE_SPEEDS = listOf("항상", "18 km/h~", "38 km/h~", "58 km/h~", "82 km/h~", "105 km/h~")

/**
 * The parked screen. Track, album and instrument choices and the demo controls lock while driving
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
    var editing by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
      Box(Modifier.fillMaxSize()) {
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
                        Library(status, canConfigure, landscape = true, actions = actions) { editing = true }
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
                    Library(status, canConfigure, landscape = false, actions = actions) { editing = true }
                }
            }
        }
        if (editing && canConfigure && status.instruments.size == 6) InstrumentEditor(status, accent, actions) { editing = false }
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

/** Connect/disconnect the car's soundtrack (also while driving); the parked demo only on the Pleos emulator. */
@Composable
private fun ColumnScope.Transport(status: PlayerStatus, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val playing = status.playing
        Button(
            onClick = actions::togglePlay,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (playing) Palette.ScenarioButton else MaterialTheme.colorScheme.primary,
                contentColor = if (playing) Color.White else Palette.Background),
        ) { Text(if (playing) "■  사운드 연결 끊기" else "▶  차량 사운드 연결", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        // Keep this drive's music: one tap, no typing, so it is safe while driving.
        Button(
            onClick = actions::saveDrive,
            enabled = status.canSaveDrive,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.ScenarioButton, contentColor = Color.White,
                disabledContainerColor = Palette.Panel, disabledContentColor = Palette.Muted),
        ) {
            val seconds = status.recordSeconds.toInt()
            Text(when {
                status.savingDrive -> "저장 중…"
                seconds > 0 -> "●  이번 드라이브 저장  %d:%02d".format(seconds / 60, seconds % 60)
                else -> "●  지난 드라이브 저장"
            }, fontSize = 15.sp)
        }
        if (status.scenarioAvailable || status.scenarioEnabled) Button(
            onClick = actions::toggleScenario,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.ScenarioButton, contentColor = Color.White),
        ) { Text(if (status.scenarioEnabled) "■  시연 시나리오 종료" else "◷  시연 시나리오 (0→130 km/h)", fontSize = 15.sp) }
    }
    status.lastSavedDrive?.let {
        Label("저장됨 · 음악 › DriveSoundtrack › $it", 12.sp, Palette.LiveSignal, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Hero(status: PlayerStatus, accent: Color, playing: Boolean, landscape: Boolean, modifier: Modifier) {
    val scene = Soundscapes.all[status.preset]
    Box(modifier) {
        InstrumentStage(status, accent, playing, Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 22.dp, end = 26.dp)) {
            val source = scene.piece?.let { "${Albums.names[status.album]}  ·  ${it.composer}" } ?: "ORIGINAL SCORE"
            Label("NOW PLAYING  /  $source", 11.sp, Palette.Muted, bold = true, spacing = 0.12.em)
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
    s.playing -> LAYER_NAMES.indices.filter { s.layerMask and (1 shl it) != 0 }
        .map { if (s.instruments.size == 6) Instruments.all[s.instruments[it]].label else LAYER_NAMES[it] }
        .filter { it != "없음" }.joinToString("  +  ")
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

/** Albums play their tracks one after another; a classical track's instruments can be changed while parked. */
@Composable
private fun ColumnScope.Library(status: PlayerStatus, enabled: Boolean, landscape: Boolean, actions: PlayerActions, onEdit: () -> Unit) {
    var viewedChoice by rememberSaveable { mutableStateOf(-1) }
    val viewed = if (viewedChoice < 0) status.album else viewedChoice
    Label("ALBUMS", 19.sp, Color.White, bold = true, spacing = 0.08.em)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, name) in Albums.names.withIndex()) {
            val selected = i == viewed
            Box(
                Modifier.weight(1f).height(40.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Palette.Panel, RoundedCornerShape(12.dp))
                    .clickable { viewedChoice = i },
                contentAlignment = Alignment.Center,
            ) {
                Text((if (i == status.album) "♪ " else "") + name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (selected) Palette.Background else Color.White, maxLines = 1)
            }
        }
    }
    val tracks = Albums.tracks(viewed, status.myAlbum.toIntArray()).toList()
    if (tracks.isEmpty()) Label("오리지널·클래식 앨범에서 ＋를 눌러 곡을 담으세요.", 13.sp, Palette.Muted,
        modifier = Modifier.padding(vertical = 12.dp))
    for (i in tracks) {
        val scene = Soundscapes.all[i]
        val accent = accentColor(scene.accent)
        val shape = RoundedCornerShape(17.dp)
        val selected = i == status.preset && viewed == status.album
        Row(
            Modifier.fillMaxWidth().padding(bottom = 7.dp)
                .then(if (landscape) Modifier.weight(1f) else Modifier.height(72.dp))
                .alpha(if (enabled) 1f else 0.55f)
                .background(if (selected) Palette.PanelSelected else Palette.Panel, shape)
                .border(1.dp, if (selected) accent else Palette.PanelStroke, shape)
                .clickable(enabled = enabled) { viewedChoice = viewed; actions.selectTrack(viewed, i) }
                .semantics { contentDescription = "${scene.name}, ${scene.character}, 곡 선택" }
                .padding(start = 17.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(4.dp, 30.dp).background(accent))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(scene.name, if (landscape) 21.sp else 18.sp, Color.White, bold = true)
                Label(scene.character.uppercase(), 12.sp, Palette.Muted, spacing = 0.05.em)
            }
            if (selected && scene.piece != null) TextButton(onEdit, enabled = enabled) {
                Text("악기 편성", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
            } else if (landscape) SoundscapeGlyph(i, accent, Modifier.width(64.dp).fillMaxHeight())
            val inMine = i in status.myAlbum
            TextButton({ actions.toggleMyAlbum(i) }, enabled = enabled, modifier = Modifier.width(52.dp)) {
                Text(if (viewed == Albums.MINE) "✕" else if (inMine) "✓" else "＋", fontSize = 18.sp,
                    color = if (inMine && viewed != Albums.MINE) accent else Palette.Muted)
            }
        }
    }
    Label("곡은 약 3분씩 이어서 연주됩니다. ＋ 내 앨범에 담기. 주행 중에는 선택과 편성이 잠깁니다.", 12.sp, Palette.Muted,
        modifier = Modifier.padding(top = 4.dp))
}

/** Pick the instrument of each stage for the playing classical track. Changes are heard right away. */
@Composable
private fun InstrumentEditor(status: PlayerStatus, accent: Color, actions: PlayerActions, onClose: () -> Unit) {
    val scene = Soundscapes.all[status.preset]
    Box(
        Modifier.fillMaxSize().background(Color(0xDD050B12)).clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.94f).background(Palette.Panel, RoundedCornerShape(20.dp))
                .border(1.dp, accent, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("${scene.name} · 악기 편성", 22.sp, Color.White, bold = true)
                    Label("속도가 오르면 위에서부터 한 단계씩 합류합니다. 고르면 바로 들립니다.", 12.sp, Palette.Muted)
                }
                TextButton(actions::resetInstruments) { Text("기본 편성으로", fontSize = 14.sp, color = Palette.Muted) }
                Button(onClose, shape = RoundedCornerShape(14.dp)) {
                    Text("완료", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Background)
                }
            }
            for (stage in 0..5) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(150.dp)) {
                        Label("${stage + 1}단계 · ${Instruments.stageNames[stage]}", 15.sp, Color.White, bold = true)
                        Label(STAGE_SPEEDS[stage], 11.sp, Palette.Muted)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (kind in Instruments.options[stage]) {
                            val chosen = status.instruments[stage] == kind
                            Box(
                                Modifier.weight(1f).height(44.dp)
                                    .background(if (chosen) accent else Palette.PanelSelected, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (chosen) accent else Palette.PanelStroke, RoundedCornerShape(12.dp))
                                    .clickable { actions.setInstrument(stage, kind) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(Instruments.all[kind].label, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1,
                                    color = if (chosen) Palette.Background else Color.White,
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                        // Keep chips the same width on every row.
                        repeat(6 - Instruments.options[stage].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
