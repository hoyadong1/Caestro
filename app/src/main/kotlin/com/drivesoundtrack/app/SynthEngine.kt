package com.drivesoundtrack.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Procedural, original PCM soundtrack. No commercial recordings or network calls. */
class SynthEngine(model: SoundModel) {
    private val renderer = SoundRenderer(model)
    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun setPreset(preset: Int) = renderer.setPreset(preset)

    @Synchronized fun start() {
        if (running) return
        val minimum = AudioTrack.getMinBufferSize(SoundRenderer.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SoundRenderer.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = newTrack
        running = true
        newTrack.play()
        worker = Thread(::render, "soundtrack-synth").also { it.start() }
    }

    @Synchronized fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        track?.let {
            try { it.pause(); it.flush() } catch (_: IllegalStateException) { }
            it.release()
        }
        track = null
    }

    private fun render() {
        val pcm = ShortArray(1024)
        while (running) {
            renderer.render(pcm)
            val current = track ?: break
            var offset = 0
            while (offset < pcm.size && running) {
                val n = try { current.write(pcm, offset, pcm.size - offset) } catch (_: IllegalStateException) { return }
                if (n <= 0) break
                offset += n
            }
        }
    }
}
