package com.drivesoundtrack.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.drivesoundtrack.app.audio.Albums
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.Soundscapes
import kotlin.math.roundToInt

private val LAYER_NAMES = listOf("베이스", "드럼", "화음", "아르페지오", "선율", "반짝임")
private val STAGE_KMH = listOf(0, 18, 38, 58, 82, 105)
private const val MAX_KMH = 130f

private const val TAB_PLAY = 0
private const val TAB_ALBUMS = 1
private const val TAB_BAND = 2

private const val EDIT_TRACK = 1
private const val EDIT_BAND = 2

/**
 * The app: a side rail switches between Now playing, Albums and My band. While driving
 * ([restricted] or a live vehicle speed of 5 km/h or more) only Now playing is shown; the rail
 * locks the other two and audio keeps adapting in the background.
 */
@Composable
fun MainScreen(status: PlayerStatus, restricted: Boolean, canRequestPermission: Boolean, actions: PlayerActions) {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val canConfigure = !restricted && (status.scenarioEnabled || !status.carAvailable || status.kmh < 5)
    val scene = Soundscapes.all[status.preset]
    val accent = accentColor(scene.accent)
    var tab by rememberSaveable { mutableStateOf(TAB_PLAY) }
    var bandMode by rememberSaveable { mutableStateOf(EDIT_BAND) }
    val shown = if (canConfigure) tab else TAB_PLAY
    val editTrack = { bandMode = EDIT_TRACK; tab = TAB_BAND }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
        Row(Modifier.fillMaxSize().background(Palette.Background)) {
            NavRail(shown, canConfigure, accent) { tab = it }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, top = 22.dp, end = 30.dp, bottom = 20.dp)) {
                when (shown) {
                    TAB_PLAY -> NowPlaying(status, accent, canConfigure, restricted, canRequestPermission, landscape, actions, editTrack)
                    TAB_ALBUMS -> AlbumsPage(status, actions, editTrack)
                    else -> BandPage(status, bandMode, accent, landscape, actions) { bandMode = it }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ rail

@Composable
private fun NavRail(selected: Int, enabled: Boolean, accent: Color, onSelect: (Int) -> Unit) {
    Column(
        Modifier.width(128.dp).fillMaxHeight().background(Palette.Panel).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(46.dp)) { logo(accent) }
        Text("DRIVE\nSOUNDTRACK", fontSize = 13.sp, lineHeight = 15.sp, color = Palette.Muted, fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.em, modifier = Modifier.padding(top = 8.dp, bottom = 26.dp))
        RailItem("지금 재생", selected == TAB_PLAY, true, accent, { playIcon(it) }) { onSelect(TAB_PLAY) }
        RailItem("앨범", selected == TAB_ALBUMS, enabled, accent, { albumIcon(it) }) { onSelect(TAB_ALBUMS) }
        RailItem("악단", selected == TAB_BAND, enabled, accent, { bandIcon(it) }) { onSelect(TAB_BAND) }
        Spacer(Modifier.weight(1f))
        if (!enabled) Text("주행 중\n선택 잠금", fontSize = 16.sp, lineHeight = 20.sp, color = Palette.DemoSignal,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun RailItem(label: String, selected: Boolean, enabled: Boolean, accent: Color,
                     icon: DrawScope.(Color) -> Unit, onClick: () -> Unit) {
    val tint = if (selected) accent else Color.White
    Column(
        Modifier.padding(vertical = 6.dp).width(104.dp).height(96.dp)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(36.dp)) { icon(tint) }
        Text(label, fontSize = 17.sp, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp))
    }
}

// ------------------------------------------------------------------ now playing

@Composable
private fun NowPlaying(status: PlayerStatus, accent: Color, canConfigure: Boolean, restricted: Boolean,
                       canRequestPermission: Boolean, landscape: Boolean, actions: PlayerActions, onEditTrack: () -> Unit) {
    val scene = Soundscapes.all[status.preset]
    Column(Modifier.fillMaxSize().then(if (landscape) Modifier else Modifier.verticalScroll(rememberScrollState()))) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label(scene.piece?.let { "${Albums.names[status.album]}  ·  ${it.composer}" } ?: "${Albums.names[status.album]}  ·  오리지널 스코어",
                    18.sp, Palette.Muted, bold = true)
                Label(scene.name, 46.sp, Color.White, bold = true, modifier = Modifier.padding(top = 2.dp))
                Label(scene.character, 20.sp, Palette.Genre)
            }
            Column(horizontalAlignment = Alignment.End) {
                SignalPill(status)
                if (scene.piece != null && canConfigure) TextButton(onEditTrack, modifier = Modifier.padding(top = 6.dp)) {
                    Text("이 곡 악기 바꾸기  ›", fontSize = 18.sp, color = accent, fontWeight = FontWeight.Bold)
                }
            }
        }
        InstrumentStage(status, accent, status.playing && canConfigure, titleSpace = false,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).then(if (landscape) Modifier.weight(1f) else Modifier.height(380.dp)))
        SpeedLadder(status, accent, Modifier.fillMaxWidth().height(116.dp).padding(top = 14.dp))
        TransportBar(status, accent, actions)
        if (!status.carAvailable && !restricted) DemoControls(status, actions)
        if (canRequestPermission) PermissionPrompt(actions)
    }
}

@Composable
private fun SignalPill(status: PlayerStatus) {
    val (text, color) = when {
        status.scenarioEnabled -> "시연 주행 중" to Palette.DemoSignal
        status.carAvailable -> "차량 속도 연결됨" to Palette.LiveSignal
        else -> "데모 속도" to Palette.DemoSignal
    }
    Row(Modifier.background(Palette.Panel, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

/**
 * The idea of the app at a glance: a 0-130 km/h road with a stop for each stage. The stops the
 * car has passed are lit and name the instrument that joined there.
 */
@Composable
private fun SpeedLadder(status: PlayerStatus, accent: Color, modifier: Modifier) {
    val text = rememberTextMeasurer()
    val names = (0..5).map { i ->
        if (status.instruments.size == 6) Instruments.all[status.instruments[i]].label.let { if (it == "없음") "—" else it }
        else LAYER_NAMES[i]
    }
    Canvas(modifier.background(Palette.Panel, RoundedCornerShape(16.dp))) {
        val left = 40.dp.toPx(); val right = size.width - 190.dp.toPx()
        val y = size.height * 0.56f
        fun xOf(kmh: Float) = left + (right - left) * (kmh / MAX_KMH).coerceIn(0f, 1f)
        fun write(value: String, x: Float, top: Float, sizeSp: Float, color: Color, bold: Boolean, center: Boolean = true) {
            val layout = text.measure(value, TextStyle(color = color, fontSize = sizeSp.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
            val tx = if (center) x - layout.size.width / 2f else x
            drawText(layout, topLeft = Offset(tx.coerceIn(4.dp.toPx(), size.width - layout.size.width - 4.dp.toPx()), top))
        }
        drawLine(Palette.PanelStroke, Offset(left, y), Offset(right, y), 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val kmh = status.kmh.toFloat()
        drawLine(accent, Offset(left, y), Offset(xOf(kmh), y), 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        for (i in 0..5) {
            val x = xOf(STAGE_KMH[i].toFloat())
            val on = status.layerMask and (1 shl i) != 0
            drawCircle(if (on) accent else Palette.Panel, 11.dp.toPx(), Offset(x, y))
            drawCircle(if (on) Color.White else Palette.Muted, 11.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
            write(names[i], x, y - 46.dp.toPx(), 16f, if (on) Color.White else Palette.Muted, on)
            write(if (i == 0) "0" else "${STAGE_KMH[i]}", x, y + 16.dp.toPx(), 15f, Palette.Muted, false)
        }
        // The car's speed: a marker on the road and the number at its end.
        drawCircle(Color.White, 7.dp.toPx(), Offset(xOf(kmh), y))
        val speed = text.measure("${kmh.roundToInt()}", TextStyle(color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold))
        val unit = text.measure("km/h", TextStyle(color = Palette.Muted, fontSize = 18.sp))
        val sx = size.width - 26.dp.toPx() - unit.size.width - 6.dp.toPx() - speed.size.width
        drawText(speed, topLeft = Offset(sx, (size.height - speed.size.height) / 2))
        drawText(unit, topLeft = Offset(size.width - 26.dp.toPx() - unit.size.width, size.height / 2 + speed.size.height / 2 - unit.size.height - 6.dp.toPx()))
    }
}

@Composable
private fun TransportBar(status: PlayerStatus, accent: Color, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("템포", 16.sp, Palette.Muted)
            Label("${status.bpm} BPM", 24.sp, Color.White, bold = true)
        }
        RoundButton(68.dp, Palette.Panel, "이전 곡", { actions.skip(-1) }) { skipIcon(Color.White, back = true) }
        Spacer(Modifier.width(22.dp))
        RoundButton(88.dp, accent, if (status.playing) "사운드 끄기" else "재생", actions::togglePlay) {
            if (status.playing) stopIcon(Palette.Background) else playIcon(Palette.Background)
        }
        Spacer(Modifier.width(22.dp))
        RoundButton(68.dp, Palette.Panel, "다음 곡", { actions.skip(1) }) { skipIcon(Color.White, back = false) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (status.scenarioAvailable || status.scenarioEnabled) Button(
                onClick = actions::toggleScenario,
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.ScenarioButton, contentColor = Color.White),
            ) { Text(if (status.scenarioEnabled) "시연 끝내기" else "시연 주행  0→130 km/h", fontSize = 17.sp) }
        }
    }
}

@Composable
private fun RoundButton(size: Dp, color: Color, description: String, onClick: () -> Unit, icon: DrawScope.() -> Unit) {
    Box(
        Modifier.size(size).background(color, CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.42f)) { icon() } }
}

// ------------------------------------------------------------------ albums

@Composable
private fun AlbumsPage(status: PlayerStatus, actions: PlayerActions, onEditTrack: () -> Unit) {
    var viewedChoice by rememberSaveable { mutableStateOf(-1) }
    val viewed = if (viewedChoice < 0) status.album else viewedChoice
    Column(Modifier.fillMaxSize()) {
        Label("앨범", 34.sp, Color.White, bold = true)
        Label("앨범의 곡은 약 3분씩 이어서 연주됩니다.", 18.sp, Palette.Muted)
        Segmented(Albums.names.mapIndexed { i, n -> if (i == status.album) "♪  $n" else n }, viewed,
            Modifier.padding(top = 16.dp, bottom = 14.dp)) { viewedChoice = it }
        val tracks = Albums.tracks(viewed, status.myAlbum.toIntArray()).toList()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (tracks.isEmpty()) Label("오리지널·클래식 드라이브 앨범에서 ＋ 를 눌러 곡을 담으세요.", 19.sp, Palette.Muted,
                modifier = Modifier.padding(vertical = 20.dp))
            for (i in tracks) {
                val scene = Soundscapes.all[i]
                val accent = accentColor(scene.accent)
                val current = i == status.preset && viewed == status.album
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).height(96.dp)
                        .background(if (current) Palette.PanelSelected else Palette.Panel, shape)
                        .border(if (current) 2.dp else 1.dp, if (current) accent else Palette.PanelStroke, shape)
                        .clickable { viewedChoice = viewed; actions.selectTrack(viewed, i) }
                        .semantics { contentDescription = "${scene.name}, ${scene.character}" }
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SoundscapeGlyph(i, accent, Modifier.size(56.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Label(scene.name, 25.sp, Color.White, bold = true)
                        Label(scene.character, 18.sp, Palette.Muted)
                    }
                    if (current) {
                        Text(if (status.playing) "재생 중" else "선택됨", fontSize = 17.sp, color = accent, fontWeight = FontWeight.Bold)
                        if (scene.piece != null) TextButton(onEditTrack) { Text("악기  ›", fontSize = 18.sp, color = accent) }
                    }
                    val inMine = i in status.myAlbum
                    TextButton({ actions.toggleMyAlbum(i) }, modifier = Modifier.width(72.dp).height(72.dp)) {
                        Text(if (viewed == Albums.MINE) "✕" else if (inMine) "✓" else "＋", fontSize = 26.sp,
                            color = if (inMine && viewed != Albums.MINE) accent else Palette.Muted)
                    }
                }
            }
        }
        Label("＋  내 앨범에 담기      ✓  담겨 있음", 17.sp, Palette.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: (Int) -> Boolean = { true },
                      onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(16.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((i, item) in items.withIndex()) {
            val on = i == selected
            Box(
                Modifier.weight(1f).height(54.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled(i), role = Role.Tab) { onSelect(i) }
                    .alpha(if (enabled(i)) 1f else 0.4f),
                contentAlignment = Alignment.Center,
            ) {
                Text(item, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) Palette.Background else Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ band

/**
 * The band: six stage slots on the left (what joins at which speed), the instruments for the
 * selected slot on the right. The selected stage plays right away, even when parked, so every
 * choice can be heard; quick presets set all six at once. "My band" plays every classical track
 * that has no instruments of its own; "this track only" changes the playing track.
 */
@Composable
private fun BandPage(status: PlayerStatus, requestedMode: Int, accent: Color, landscape: Boolean, actions: PlayerActions,
                     onMode: (Int) -> Unit) {
    val scene = Soundscapes.all[status.preset]
    val trackPossible = scene.piece != null && status.instruments.size == 6
    val mode = if (requestedMode == EDIT_TRACK && trackPossible) EDIT_TRACK else EDIT_BAND
    val band = mode == EDIT_BAND
    val chosenIds = if (band) status.band.takeIf { it.size == 6 } ?: Instruments.BAND_DEFAULT.map { Instruments.indexOf(it) }
    else status.instruments
    var stage by rememberSaveable { mutableStateOf(0) }
    // Hear the stage being chosen: the bass always plays, any other stage is brought in.
    DisposableEffect(stage) {
        actions.audition(if (stage >= 1) stage else -1)
        onDispose { actions.audition(-1) }
    }

    Column(Modifier.fillMaxSize().then(if (landscape) Modifier else Modifier.verticalScroll(rememberScrollState()))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("악단", 34.sp, Color.White, bold = true)
                Label(when {
                    band -> "모든 클래식 곡이 이 악단으로 연주됩니다. 곡마다 따로 바꾼 곡은 그대로 둡니다."
                    status.ownInstruments -> "${scene.name}만 따로 편성했습니다."
                    status.band.size == 6 -> "${scene.name}은 지금 나만의 악단을 따릅니다. 바꾸면 이 곡만 달라집니다."
                    else -> "${scene.name}은 지금 곡의 추천 편성입니다. 바꾸면 이 곡만 달라집니다."
                }, 18.sp, Palette.Muted)
            }
            // Up here, next to the band/track switch, so the six presets fit on one line.
            TextButton({ actions.resetInstruments(band) }, enabled = if (band) status.band.size == 6 else status.ownInstruments) {
                Text(if (band) "↺  곡별 추천으로" else "↺  악단 따르기", fontSize = 18.sp, color = Palette.Muted)
            }
            Spacer(Modifier.width(12.dp))
            Segmented(listOf("나만의 악단", if (trackPossible) "이 곡만" else "이 곡만 (클래식)"), if (band) 0 else 1,
                Modifier.width(420.dp), enabled = { it == 0 || trackPossible }) { onMode(if (it == 0) EDIT_BAND else EDIT_TRACK) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("빠른 편성", 18.sp, Palette.Muted, bold = true)
            Spacer(Modifier.width(14.dp))
            // Wrap instead of scrolling sideways: a scrolled-off pill looked cut under the reset button.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((name, ids) in Instruments.PRESETS) PresetPill(name, ids) {
                    actions.setInstruments(ids.map { Instruments.indexOf(it) }.toIntArray(), band)
                }
            }
        }
        val slots: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in 0..5) StageSlot(s, chosenIds.getOrElse(s) { 0 }, s == stage,
                    if (landscape) Modifier.weight(1f) else Modifier.height(88.dp)) { stage = s }
            }
        }
        val picker: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                val current = Instruments.all[chosenIds.getOrElse(stage) { 0 }]
                Row(verticalAlignment = Alignment.Bottom) {
                    Label("${stage + 1}단계  ${Instruments.stageNames[stage]}", 26.sp, Color.White, bold = true)
                    Spacer(Modifier.width(12.dp))
                    Label(if (stage == 0) "항상 연주" else "${STAGE_KMH[stage]} km/h부터 합류", 18.sp, Palette.Muted)
                }
                Row(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (status.playing) {
                        Box(Modifier.size(10.dp).background(accent, CircleShape)); Spacer(Modifier.width(8.dp))
                        Label(if (stage == 0) "베이스는 늘 들립니다. 고르면 바로 바뀝니다." else "지금 이 단계를 미리 들려주고 있어요. 고르면 바로 바뀝니다.",
                            17.sp, accent)
                    } else {
                        Label("재생을 켜면 고르는 악기를 바로 들을 수 있어요.", 17.sp, Palette.Muted, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Button(actions::togglePlay, shape = RoundedCornerShape(12.dp)) { Text("▶  재생", fontSize = 17.sp, color = Palette.Background) }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (row in Instruments.options[stage].chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (kind in row) InstrumentCard(kind, kind == chosenIds.getOrElse(stage) { 0 }, Modifier.weight(1f)) {
                            actions.setInstrument(stage, kind, band)
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp)) {
                slots(Modifier.weight(0.92f).fillMaxHeight())
                Spacer(Modifier.width(26.dp))
                picker(Modifier.weight(1.3f).fillMaxHeight())
            }
        } else {
            slots(Modifier.fillMaxWidth().padding(top = 18.dp))
            picker(Modifier.fillMaxWidth().height(640.dp).padding(top = 18.dp))
        }
    }
}

/** One stage of the band: number, when it joins, and its instrument drawn small. */
@Composable
private fun StageSlot(stage: Int, kind: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val item = Instruments.all[kind]
    val look = InstrumentLooks.of(item.id).color
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth()
            .background(if (selected) Palette.PanelSelected else Palette.Panel, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) look else Palette.PanelStroke, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "${stage + 1}단계 ${Instruments.stageNames[stage]}, ${item.label}" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(if (selected) look else Palette.PanelSelected, CircleShape), contentAlignment = Alignment.Center) {
            Text("${stage + 1}", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.Background else Color.White)
        }
        Column(Modifier.width(118.dp).padding(start = 14.dp)) {
            Label(Instruments.stageNames[stage], 18.sp, Color.White, bold = true)
            Label(if (stage == 0) "항상" else "${STAGE_KMH[stage]} km/h~", 16.sp, Palette.Muted)
        }
        InstrumentLook(item.id, animate = selected, modifier = Modifier.width(110.dp).fillMaxHeight().padding(vertical = 8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(if (item.id == "none") "쉼" else item.label, 21.sp, if (item.id == "none") Palette.Muted else Color.White, bold = true)
            Label(when { item.id == "none" -> "이 단계는 연주하지 않음"; item.real -> "실제 악기 녹음"; else -> "신시사이저" }, 15.sp, Palette.Muted)
        }
        Text("›", fontSize = 30.sp, color = if (selected) look else Palette.Muted)
    }
}

/** An instrument to pick: its drawing, name and whether it is a real recording. */
@Composable
private fun InstrumentCard(kind: Int, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val item = Instruments.all[kind]
    val look = InstrumentLooks.of(item.id).color
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.height(164.dp)
            .background(if (chosen) Palette.PanelSelected else Palette.Panel, shape)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) look else Palette.PanelStroke, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = item.label },
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            InstrumentLook(item.id, animate = chosen, modifier = Modifier.fillMaxWidth().weight(1f))
            Label(if (item.id == "none") "쉼" else item.label, 20.sp, Color.White, bold = true, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.id != "none") {
                    Box(Modifier.size(11.dp).then(if (item.real) Modifier.background(look, CircleShape) else Modifier.border(2.dp, look, CircleShape)))
                    Spacer(Modifier.width(7.dp))
                }
                Label(when { item.id == "none" -> "이 단계 쉬기"; item.real -> "실제 녹음"; else -> "신시사이저" }, 15.sp, Palette.Muted)
            }
        }
        if (chosen) Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(32.dp).background(look, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Background) }
    }
}

/** A ready-made ensemble: its name and the colors of its six instruments. */
@Composable
private fun PresetPill(name: String, ids: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.height(52.dp).background(Palette.Panel, RoundedCornerShape(26.dp))
            .border(1.dp, Palette.PanelStroke, RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        for (id in ids) if (id != "none") {
            Box(Modifier.padding(end = 4.dp).size(10.dp).background(InstrumentLooks.of(id).color, CircleShape))
        }
    }
}

// ------------------------------------------------------------------ demo and permission

@Composable
private fun ColumnScope.DemoControls(status: PlayerStatus, actions: PlayerActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var pedal by remember { mutableFloatStateOf(0f) }
    var brake by rememberSaveable { mutableStateOf(false) }
    var turn by rememberSaveable { mutableStateOf(false) }
    var wiper by rememberSaveable { mutableStateOf(false) }
    TextButton({ open = !open }) { Text(if (open) "시연 조작 닫기  ▴" else "시연 조작 열기  ▾", fontSize = 17.sp, color = Palette.Muted) }
    if (!open) return
    Label("시연 속도   ${status.kmh.roundToInt()} km/h", 17.sp, Palette.Muted)
    Slider(
        value = dragging ?: status.kmh.toFloat().coerceIn(0f, 120f),
        onValueChange = { dragging = it; actions.demoSpeed(it.roundToInt().toDouble()) },
        onValueChangeFinished = { dragging = null },
        valueRange = 0f..120f,
    )
    Label("시연 가속 페달", 17.sp, Palette.Muted)
    Slider(value = pedal, onValueChange = { pedal = it; actions.demoAccelerator(it.roundToInt().toDouble()) }, valueRange = 0f..100f)
    Row(Modifier.fillMaxWidth()) {
        DemoToggle("브레이크", brake, Modifier.weight(1f)) { brake = !brake; actions.demoBrake(brake) }
        DemoToggle("좌측 방향", turn, Modifier.weight(1f)) { turn = !turn; actions.demoLeftTurn(turn) }
        DemoToggle("와이퍼", wiper, Modifier.weight(1f)) { wiper = !wiper; actions.demoWiper(wiper) }
    }
}

@Composable
private fun DemoToggle(title: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.height(52.dp)) {
        Text(title, fontSize = 17.sp, color = if (active) MaterialTheme.colorScheme.primary else Color.White,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun PermissionPrompt(actions: PlayerActions) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("차량 속도 권한을 허용하면 차량 신호에 맞춰 재생합니다.", 17.sp, Palette.Muted, modifier = Modifier.weight(1f))
        Button(actions::requestVehiclePermissions) { Text("차량 속도 연결", fontSize = 17.sp) }
    }
}

@Composable
private fun Label(
    text: String, size: TextUnit, color: Color, bold: Boolean = false,
    spacing: TextUnit = TextUnit.Unspecified, modifier: Modifier = Modifier,
) = Text(text, modifier, color = color, fontSize = size, letterSpacing = spacing,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)

// ------------------------------------------------------------------ icons

private fun DrawScope.logo(accent: Color) {
    drawCircle(accent, size.minDimension / 2)
    val w = size.width
    for (i in 0..3) {
        val h = size.height * floatArrayOf(0.30f, 0.55f, 0.42f, 0.22f)[i]
        drawRoundRect(Palette.Background, Offset(w * (0.24f + i * 0.15f), (size.height - h) / 2), Size(w * 0.08f, h), CornerRadius(w * 0.04f))
    }
}

private fun DrawScope.playIcon(c: Color) {
    val p = Path().apply { moveTo(size.width * 0.22f, size.height * 0.12f); lineTo(size.width * 0.90f, size.height * 0.5f)
        lineTo(size.width * 0.22f, size.height * 0.88f); close() }
    drawPath(p, c)
}

private fun DrawScope.stopIcon(c: Color) =
    drawRoundRect(c, Offset(size.width * 0.15f, size.height * 0.15f), Size(size.width * 0.7f, size.height * 0.7f), CornerRadius(size.width * 0.08f))

private fun DrawScope.skipIcon(c: Color, back: Boolean) {
    val w = size.width; val h = size.height
    fun x(v: Float) = if (back) w - v * w else v * w
    val p = Path().apply { moveTo(x(0.12f), h * 0.15f); lineTo(x(0.72f), h * 0.5f); lineTo(x(0.12f), h * 0.85f); close() }
    drawPath(p, c)
    drawRect(c, Offset(if (back) x(0.90f) else x(0.76f), h * 0.15f), Size(w * 0.14f, h * 0.70f))
}

private fun DrawScope.albumIcon(c: Color) {
    val w = size.width
    drawRoundRect(c, Offset(w * 0.22f, w * 0.06f), Size(w * 0.72f, w * 0.72f), CornerRadius(w * 0.08f), style = Stroke(w * 0.07f))
    drawRoundRect(c, Offset(w * 0.06f, w * 0.22f), Size(w * 0.72f, w * 0.72f), CornerRadius(w * 0.08f))
    drawCircle(Palette.Panel, w * 0.16f, Offset(w * 0.42f, w * 0.58f))
}

private fun DrawScope.bandIcon(c: Color) {
    val w = size.width
    val heights = floatArrayOf(0.5f, 0.85f, 0.65f, 0.95f, 0.4f)
    for (i in heights.indices) {
        val h = w * heights[i]
        drawRoundRect(c, Offset(w * (0.04f + i * 0.2f), w - h), Size(w * 0.13f, h), CornerRadius(w * 0.06f))
    }
}
