package com.drivesoundtrack.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Procedural, original PCM soundtrack. No commercial recordings or network calls. */
class SynthEngine(model: SoundModel) {
    private val renderer = SoundRenderer(model)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var worker: Thread? = null

    fun setPreset(preset: Int) = renderer.setPreset(preset)

    @Synchronized fun start(onFailure: (RuntimeException) -> Unit) {
        if (running) return
        running = true
        val currentGeneration = ++generation
        worker = Thread({ render(currentGeneration, onFailure) }, "soundtrack-synth").also { it.start() }
    }

    @Synchronized fun stop() {
        running = false
        generation++
        worker?.interrupt()
        worker = null
    }

    private fun isCurrent(value: Long): Boolean = running && generation == value

    private fun render(value: Long, onFailure: (RuntimeException) -> Unit) {
        var current: AudioTrack? = null
        try {
            // The emulator's audio policy service can block these calls during startup.
            val minimum = AudioTrack.getMinBufferSize(SoundRenderer.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (!isCurrent(value)) return
            current = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SoundRenderer.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minimum, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
            if (!isCurrent(value)) return
            current.play()
            val pcm = ShortArray(1024 * SoundRenderer.CHANNELS)
            while (isCurrent(value)) {
                renderer.render(pcm)
                var offset = 0
                while (offset < pcm.size && isCurrent(value)) {
                    val n = current.write(pcm, offset, pcm.size - offset)
                    if (n <= 0) throw IllegalStateException("AudioTrack write failed: $n")
                    offset += n
                }
            }
        } catch (ex: RuntimeException) {
            if (isCurrent(value)) mainHandler.post { if (generation == value) onFailure(ex) }
        } finally {
            current?.let {
                try { it.pause(); it.flush() } catch (_: IllegalStateException) { }
                try { it.release() } catch (_: RuntimeException) { }
            }
            synchronized(this) {
                if (generation == value) { running = false; worker = null }
            }
        }
    }
}
