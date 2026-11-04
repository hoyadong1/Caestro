package com.drivesoundtrack.app

import android.app.Activity
import android.car.VehicleGear
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val PEDAL_PERMISSION = CarPedalReader.PERMISSION
        private const val SPEED_REQUEST = 42
        private const val BG = 0xff08111c.toInt()
        private const val PANEL = 0xff142331.toInt()
        private const val MUTED = 0xff9fb4c2.toInt()
    }
    private lateinit var visualizer: InstrumentStageView
    private lateinit var source: TextView
    private lateinit var song: TextView
    private lateinit var genre: TextView
    private lateinit var speedValue: TextView
    private lateinit var tempoValue: TextView
    private lateinit var layersValue: TextView
    private lateinit var phase: TextView
    private lateinit var play: Button
    private lateinit var demo: LinearLayout
    private lateinit var demoLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var permissionInfo: TextView
    private lateinit var permissionButton: Button
    private val cards = mutableListOf<LinearLayout>()
    private var restrictionsReader: DriveRestrictionsReader? = null
    private var restricted = false
    private var restrictionsKnown = false
    private var kmh = 0.0
    private var preset = 0
    private var playing = false
    private var carAvailable = false
    private var demoBrake = false
    private var demoTurn = false
    private var demoWiper = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            playing = intent.getBooleanExtra("playing", false)
            preset = intent.getIntExtra("preset", 0).coerceIn(Soundscapes.all.indices)
            carAvailable = intent.getBooleanExtra("carAvailable", false)
            kmh = intent.getDoubleExtra("kmh", 0.0)
            val bpm = intent.getDoubleExtra("bpm", Soundscapes.all[preset].baseBpm).roundToInt()
            val energy = intent.getDoubleExtra("energy", 0.0).toFloat()
            val layers = intent.getIntExtra("layers", 1)
            val bassSolo = intent.getBooleanExtra("bassSolo", false)
            val selectedGear = intent.getIntExtra("selectedGear", 0)
            val gearLabel = when (selectedGear) {
                VehicleGear.GEAR_PARK -> "P"
                VehicleGear.GEAR_REVERSE -> "R"
                VehicleGear.GEAR_NEUTRAL -> "N"
                VehicleGear.GEAR_DRIVE -> "D"
                else -> "?"
            }
            val accelerator = intent.getDoubleExtra("accelerator", 0.0)
            val brake = intent.getDoubleExtra("brake", 0.0)
            val steering = intent.getDoubleExtra("steering", 0.0)
            val turn = intent.getIntExtra("turn", 0)
            val wiper = intent.getBooleanExtra("wiper", false)
            val scene = Soundscapes.all[preset]
            source.text = if (carAvailable) "●  VEHICLE SIGNAL  ·  SELECTED $gearLabel" else "●  DEMO INPUT"
            source.setTextColor(if (carAvailable) 0xff73e5ce.toInt() else 0xffffc784.toInt())
            song.text = scene.name.uppercase()
            genre.text = scene.character.uppercase()
            speedValue.text = kmh.roundToInt().toString()
            tempoValue.text = bpm.toString()
            layersValue.text = "$layers / 4"
            val activeParts = buildList {
                if (kmh >= 15) add("속도 → 드럼 층")
                if (accelerator > 0.15) add("추진 → 베이스")
                if (brake > 0.35) add("제동 → 화음")
                if (kotlin.math.abs(steering) > 0.12) add("조향 → 선율")
                if (turn != 0) add(if (turn < 0) "좌회전 → 하행음" else "우회전 → 상행음")
                if (wiper) add("와이퍼 → 타악")
            }
            phase.text = if (bassSolo) "베이스 솔로 · ${kmh.roundToInt()} km/h" else
                if (!playing) "재생 대기" else activeParts.takeLast(2).joinToString("  ·  ")
                    .ifEmpty { "정차 → 기본 리듬" }
            play.text = if (playing) "Ⅱ   일시정지" else "▶   음악 재생"
            visualizer.accent = scene.accent or (0xff shl 24)
            visualizer.playing = playing
            visualizer.bassSolo = bassSolo
            visualizer.accelerator = accelerator
            visualizer.speed = kmh
            visualizer.brake = brake
            visualizer.steering = steering
            visualizer.turn = turn
            visualizer.wiper = wiper
            visualizer.pedalLive = intent.getBooleanExtra("pedalAvailable", false)
            visualizer.brakeLive = intent.getBooleanExtra("brakeAvailable", false)
            visualizer.steeringLive = intent.getBooleanExtra("steeringAvailable", false)
            visualizer.wiperLive = intent.getBooleanExtra("wiperAvailable", false)
            visualizer.vehicleLive = carAvailable
            visualizer.invalidate()
            cards.forEachIndexed { i, card ->
                card.background = panel(if (i == preset) 0xff203740.toInt() else PANEL, 17,
                    if (i == preset) Soundscapes.all[i].accent or (0xff shl 24) else 0xff2b3e4b.toInt())
            }
            if (!carAvailable && !slider.isPressed) slider.progress = kmh.roundToInt()
            demoLabel.text = "시연 속도   ${kmh.roundToInt()} km/h"
            renderRestrictions()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(24))
        }
        scroll.addView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DRIVE / SOUNDTRACK", 23f, Color.WHITE, true))
        brand.addView(label("ADAPTIVE DRIVING SCORE", 11f, MUTED, true).apply { letterSpacing = 0.13f })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        source = label("●  DEMO INPUT", 12f, 0xffffc784.toInt(), true)
        header.addView(source)
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val now = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(now, if (landscape) LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = dp(20) }
            else LinearLayout.LayoutParams(-1, -2))
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(library, if (landscape) LinearLayout.LayoutParams(0, -2, 0.85f)
            else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val hero = FrameLayout(this)
        visualizer = InstrumentStageView(this)
        hero.addView(visualizer, FrameLayout.LayoutParams(-1, -1))
        val heroWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(22), dp(26), 0) }
        heroWords.addView(label("NOW PLAYING  /  ORIGINAL SCORE", 11f, MUTED, true).apply { letterSpacing = 0.12f })
        song = label("PULSE", if (landscape) 43f else 36f, Color.WHITE, true)
        heroWords.addView(song, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        genre = label("ELECTRO DRIVE", 13f, 0xffb3cbd2.toInt(), true).apply { letterSpacing = 0.1f }
        heroWords.addView(genre)
        hero.addView(heroWords, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        phase = label("재생 대기", 14f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7)); background = panel(0xaa10202e.toInt(), 9)
        }
        hero.addView(phase, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(16); topMargin = dp(18)
        })
        val heroHeight = if (landscape) maxOf(dp(365), resources.displayMetrics.heightPixels - dp(300)) else dp(330)
        now.addView(hero, LinearLayout.LayoutParams(-1, heroHeight))

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metric("SPEED", "0", "km/h").also {
            speedValue = it.second; metrics.addView(it.first, LinearLayout.LayoutParams(0, dp(86), 1f).apply { rightMargin = dp(8) })
        }
        metric("TEMPO", "96", "BPM").also {
            tempoValue = it.second; metrics.addView(it.first, LinearLayout.LayoutParams(0, dp(86), 1f).apply { rightMargin = dp(8) })
        }
        metric("LAYERS", "1 / 4", "ACTIVE").also {
            layersValue = it.second; metrics.addView(it.first, LinearLayout.LayoutParams(0, dp(86), 1f))
        }
        now.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        play = Button(this).apply {
            text = "▶   음악 재생"; textSize = 19f; isAllCaps = false
            setTextColor(BG); background = panel(0xff78dfd4.toInt(), 16)
            setOnClickListener { send(if (playing) SoundtrackService.PAUSE else SoundtrackService.PLAY) }
        }
        now.addView(play, LinearLayout.LayoutParams(-1, dp(67)).apply { topMargin = dp(14) })

        demo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        demoLabel = label("시연 속도   0 km/h", 13f, MUTED)
        demo.addView(demoLabel)
        slider = SeekBar(this).apply {
            max = 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user && !carAvailable) send(SoundtrackService.DEMO_SPEED, "kmh", value.toDouble())
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        demo.addView(slider)
        demo.addView(label("시연 가속 페달", 12f, MUTED))
        demo.addView(SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user && !carAvailable) sendBand("accelerator", "value", value.toDouble())
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        val bandControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun demoButton(title: String, click: () -> Unit) = Button(this).apply {
            text = title; textSize = 12f; isAllCaps = false; setOnClickListener { click() }
        }
        bandControls.addView(demoButton("브레이크") {
            demoBrake = !demoBrake; sendBand("brake", "active", demoBrake)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bandControls.addView(demoButton("좌측 방향") {
            demoTurn = !demoTurn; sendBand("turn", "value", if (demoTurn) -1 else 0)
            sendBand("steering", "value", if (demoTurn) -150.0 else 0.0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bandControls.addView(demoButton("와이퍼") {
            demoWiper = !demoWiper; sendBand("wiper", "active", demoWiper)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        demo.addView(bandControls)
        now.addView(demo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        permissionInfo = label("차량 속도 권한을 허용하면 차량 신호에 맞춰 재생합니다.", 12f, MUTED)
        now.addView(permissionInfo)
        permissionButton = Button(this).apply {
            text = "차량 속도 연결"; isAllCaps = false
            setOnClickListener {
                val requested = mutableListOf(SPEED_PERMISSION)
                if (pedalPermissionSupported()) requested += PEDAL_PERMISSION
                requestPermissions(requested.toTypedArray(), SPEED_REQUEST)
            }
        }
        now.addView(permissionButton)

        library.addView(label("SOUNDSCAPES", 19f, Color.WHITE, true).apply { letterSpacing = 0.08f })
        library.addView(label("정차 중 음악의 분위기를 선택하세요", 12f, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3); bottomMargin = dp(10) })
        for ((i, scene) in Soundscapes.all.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(17), dp(9), dp(16), dp(9))
                background = panel(if (i == 0) 0xff203740.toInt() else PANEL, 17,
                    if (i == 0) scene.accent or (0xff shl 24) else 0xff2b3e4b.toInt())
                isClickable = true; isFocusable = true
                setOnClickListener { send(SoundtrackService.PRESET, "preset", i) }
                contentDescription = "${scene.name}, ${scene.character}, 사운드스케이프 선택"
            }
            card.addView(View(this).apply { setBackgroundColor(scene.accent or (0xff shl 24)) },
                LinearLayout.LayoutParams(dp(4), dp(39)).apply { rightMargin = dp(14) })
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(label(scene.name, if (landscape) 25f else 18f, Color.WHITE, true))
            words.addView(label(scene.character.uppercase(), if (landscape) 13f else 11f, MUTED).apply { letterSpacing = 0.09f })
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            if (landscape) card.addView(SoundscapeGlyphView(this, i, scene.accent or (0xff shl 24)),
                LinearLayout.LayoutParams(dp(125), -1))
            else card.addView(label("${scene.baseBpm.roundToInt()} BPM", 11f, MUTED))
            cards += card
            val cardHeight = if (landscape)
                maxOf(dp(64), (resources.displayMetrics.heightPixels - dp(170)) / Soundscapes.all.size)
            else dp(72)
            library.addView(card, LinearLayout.LayoutParams(-1, cardHeight).apply { bottomMargin = dp(7) })
        }
        library.addView(label("전기차의 주행 신호가 각 악기의 연주를 바꿉니다.", 12f, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        library.addView(label("주행 중에는 선택과 시연 조작이 제한됩니다.", 12f, MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setContentView(scroll)
        renderRestrictions()
        startService(Intent(this, SoundtrackService::class.java))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun panel(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun metric(caption: String, value: String, unit: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(8), 0); background = panel(PANEL, 13)
        }
        box.addView(label(caption, 10f, MUTED, true).apply { letterSpacing = 0.11f })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val number = label(value, 26f, Color.WHITE, true)
        row.addView(number)
        row.addView(label("  $unit", 10f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(5) })
        box.addView(row)
        return box to number
    }
    private fun send(action: String) {
        val intent = Intent(this, SoundtrackService::class.java).setAction(action)
        if (action == SoundtrackService.PLAY) startForegroundService(intent) else startService(intent)
    }
    private fun send(action: String, key: String, value: Int) {
        startService(Intent(this, SoundtrackService::class.java).setAction(action).putExtra(key, value))
    }
    private fun send(action: String, key: String, value: Double) {
        startService(Intent(this, SoundtrackService::class.java).setAction(action).putExtra(key, value))
    }
    private fun sendBand(instrument: String, key: String, value: Any) {
        val intent = Intent(this, SoundtrackService::class.java).setAction(SoundtrackService.DEMO_BAND)
            .putExtra("instrument", instrument)
        when (value) {
            is Boolean -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Double -> intent.putExtra(key, value)
        }
        startService(intent)
    }
    private fun pedalPermissionSupported(): Boolean = try {
        packageManager.getPermissionInfo(PEDAL_PERMISSION, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun renderRestrictions() {
        val canConfigure = !restricted && (!carAvailable || kmh < 5)
        cards.forEach { it.isEnabled = canConfigure; it.alpha = if (canConfigure) 1f else 0.55f }
        visualizer.playing = playing && canConfigure
        play.isEnabled = canConfigure
        play.alpha = if (canConfigure) 1f else 0.55f
        demo.visibility = if (carAvailable || restricted) View.GONE else View.VISIBLE
        val canRequest = restrictionsKnown && !restricted && !carAvailable &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            (checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED ||
                (pedalPermissionSupported() && checkSelfPermission(PEDAL_PERMISSION) != PackageManager.PERMISSION_GRANTED))
        permissionInfo.visibility = if (canRequest) View.VISIBLE else View.GONE
        permissionButton.visibility = if (canRequest) View.VISIBLE else View.GONE
    }
    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(SoundtrackService.STATUS), Context.RECEIVER_NOT_EXPORTED)
        send(SoundtrackService.STATUS_REQUEST)
        try {
            restrictionsReader = DriveRestrictionsReader().also { reader ->
                reader.start(this) { value ->
                    runOnUiThread { restricted = value; restrictionsKnown = true; renderRestrictions() }
                }
            }
        } catch (_: LinkageError) { }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SPEED_REQUEST && grantResults.any { it == PackageManager.PERMISSION_GRANTED })
            send(SoundtrackService.RECONNECT_SPEED)
        renderRestrictions()
    }
    override fun onStop() {
        restrictionsReader?.stop(); restrictionsReader = null; restrictionsKnown = false
        unregisterReceiver(receiver)
        super.onStop()
    }
}
