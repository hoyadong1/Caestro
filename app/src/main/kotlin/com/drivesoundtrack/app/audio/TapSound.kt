package com.drivesoundtrack.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A short, soft wooden "tok" for button presses, played as a UI sound (it follows the system's touch-sound
 * setting and does not take audio focus from the music).
 */
class TapSound(private val context: Context) {
    private val pcm: ShortArray = synth()
    private val track: AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SoundRenderer.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (_: RuntimeException) { null }

    fun play() {
        if (Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return
        val t = track ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.playbackHeadPosition = 0
            t.play()
        } catch (_: IllegalStateException) { return }
        DebugTimeline.writeNow(pcm)
    }

    fun release() { track?.release() }

    private fun synth(): ShortArray {
        val rate = SoundRenderer.RATE
        val frames = rate * 90 / 1000
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val attack = (t / 0.002).coerceAtMost(1.0)
            val body = sin(2 * PI * 880.0 * t) * exp(-t / 0.022) + 0.35 * sin(2 * PI * 1760.0 * t) * exp(-t / 0.010)
            val v = (attack * body * 0.10 * 32767).toInt().toShort()
            out[2 * i] = v; out[2 * i + 1] = v
        }
        return out
    }
}
