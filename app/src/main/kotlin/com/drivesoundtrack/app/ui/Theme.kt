package com.drivesoundtrack.app.ui

import androidx.compose.ui.graphics.Color

internal object Palette {
    val Background = Color(0xff08111c)
    val Panel = Color(0xff142331)
    val PanelSelected = Color(0xff203740)
    val PanelStroke = Color(0xff2b3e4b)
    val Muted = Color(0xff9fb4c2)
    val Genre = Color(0xffb3cbd2)
    val ScenarioButton = Color(0xff29465b)
    val Chip = Color(0xaa10202e)
    val LiveSignal = Color(0xff66c9ff)
    val DemoSignal = Color(0xffffc784)
}

/** Soundscape accents are stored as 0xRRGGBB. */
internal fun accentColor(rgb: Int) = Color(rgb or (0xff shl 24))
