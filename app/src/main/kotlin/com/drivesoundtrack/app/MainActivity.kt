package com.drivesoundtrack.app

import android.app.Activity
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
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val SPEED_PERMISSION_REQUEST = 42
    }
    private val names = arrayOf("Pulse", "Night", "Coast")
    private lateinit var status: TextView
    private lateinit var playButton: Button
    private lateinit var speed: SeekBar
    private lateinit var demoLabel: TextView
    private lateinit var speedPermissionInfo: TextView
    private lateinit var speedPermissionButton: Button
    private val presetButtons = arrayOfNulls<Button>(3)
    private var restrictionsReader: DriveRestrictionsReader? = null
    private var restricted = false
    private var restrictionsKnown = false
    private var currentKmh = 0.0
    private var preset = 0
    private var playing = false
    private var carAvailable = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            playing = intent.getBooleanExtra("playing", false)
            preset = intent.getIntExtra("preset", 0).coerceIn(0, names.lastIndex)
            carAvailable = intent.getBooleanExtra("carAvailable", false)
            val kmh = intent.getDoubleExtra("kmh", 0.0)
            currentKmh = kmh
            val bpm = intent.getDoubleExtra("bpm", 92.0).roundToInt()
            status.text = "${names[preset]}   ·   ${kmh.roundToInt()} km/h   ·   $bpm BPM" + if (carAvailable) "\n차량 연결" else "\n시연 모드"
            playButton.text = if (playing) "일시정지" else "재생"
            for (i in presetButtons.indices) {
                presetButtons[i]?.apply {
                    background = shape(if (i == preset) Color.rgb(50, 183, 179) else Color.rgb(29, 39, 54), 16f)
                    setTextColor(if (i == preset) Color.rgb(8, 19, 28) else Color.WHITE)
                }
            }
            renderRestrictions()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(9, 16, 27)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 26, 24, 26)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(text("DRIVE SOUNDTRACK", 34f, Color.WHITE).apply { setTypeface(null, Typeface.BOLD) })
        root.addView(text("주행이 만드는 일렉트로닉 사운드", 18f, Color.rgb(169, 190, 207)))
        status = text("Pulse  ·  시연 모드", 25f, Color.rgb(120, 234, 224)).apply {
            setPadding(28, 28, 28, 28)
            background = shape(Color.rgb(22, 39, 53), 20f)
        }
        val gap = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 }
        root.addView(status, gap)
        speedPermissionInfo = text("차량 속도 권한은 위치 정보로 표시됩니다. GPS 위치는 수집하지 않습니다.", 14f, Color.rgb(169, 190, 207)).apply {
            setPadding(8, 12, 8, 4)
        }
        root.addView(speedPermissionInfo)
        speedPermissionButton = Button(this).apply {
            text = "차량 속도 연결"
            isAllCaps = false
            setOnClickListener { requestPermissions(arrayOf(CAR_SPEED_PERMISSION), SPEED_PERMISSION_REQUEST) }
        }
        root.addView(speedPermissionButton)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in names.indices) {
            val button = Button(this).apply {
                text = names[i]
                textSize = 18f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = shape(if (i == 0) Color.rgb(50, 183, 179) else Color.rgb(29, 39, 54), 16f)
                setOnClickListener { send(SoundtrackService.PRESET, "preset", i) }
            }
            presetButtons[i] = button
            presets.addView(button, LinearLayout.LayoutParams(0, 76, 1f).apply { setMargins(4, 0, 4, 0) })
        }
        root.addView(presets, gap)
        playButton = Button(this).apply {
            text = "재생"
            textSize = 23f
            isAllCaps = false
            setTextColor(Color.rgb(8, 19, 28))
            background = shape(Color.rgb(113, 233, 220), 18f)
            minHeight = 94
            setOnClickListener { send(if (playing) SoundtrackService.PAUSE else SoundtrackService.PLAY) }
        }
        root.addView(playButton, gap)
        demoLabel = text("시연 속도", 14f, Color.rgb(170, 189, 209))
        root.addView(demoLabel, gap)
        speed = SeekBar(this).apply {
            max = 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user && !carAvailable) send(SoundtrackService.DEMO_SPEED, "kmh", value.toDouble())
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        root.addView(speed)
        root.addView(text("주행 중에는 프리셋 변경과 시연 조작이 제한됩니다.", 15f, Color.rgb(132, 153, 170)), gap)
        val displayWidth = resources.displayMetrics.widthPixels
        val width = min(displayWidth - 40, (1100 * resources.displayMetrics.density).toInt())
        outer.addView(root, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        renderRestrictions()
        setContentView(outer)
        startService(Intent(this, SoundtrackService::class.java))
    }

    private fun text(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun shape(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
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

    private fun renderRestrictions() {
        val canConfigure = !restricted && (!carAvailable || currentKmh < 5)
        presetButtons.forEach { it?.isEnabled = canConfigure }
        val demoVisibility = if (carAvailable || restricted) View.GONE else View.VISIBLE
        speed.visibility = demoVisibility
        demoLabel.visibility = demoVisibility
        val canRequestSpeed = restrictionsKnown && !restricted && !carAvailable &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            checkSelfPermission(CAR_SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED
        speedPermissionInfo.visibility = if (canRequestSpeed) View.VISIBLE else View.GONE
        speedPermissionButton.visibility = if (canRequestSpeed) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(SoundtrackService.STATUS), Context.RECEIVER_NOT_EXPORTED)
        send(SoundtrackService.STATUS_REQUEST)
        try {
            restrictionsReader = DriveRestrictionsReader().also { reader ->
                reader.start(this) { value ->
                    runOnUiThread {
                        restricted = value
                        restrictionsKnown = true
                        renderRestrictions()
                    }
                }
            }
        } catch (_: LinkageError) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SPEED_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            send(SoundtrackService.RECONNECT_SPEED)
        }
        renderRestrictions()
    }

    override fun onStop() {
        restrictionsReader?.stop()
        restrictionsReader = null
        restrictionsKnown = false
        unregisterReceiver(receiver)
        super.onStop()
    }
}
