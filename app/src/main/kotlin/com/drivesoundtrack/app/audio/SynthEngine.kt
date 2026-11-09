package com.drivesoundtrack.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Procedural, original PCM soundtrack. No commercial recordings or network calls. */
class SynthEngine(model: SoundModel) {
    private val renderer = SoundRenderer(model)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var worker: Thread? = null
    @Volatile private var captureFile: File? = null
    /** Keeps the music of the current drive; fed with exactly what was played. */
    @Volatile var recorder: DriveRecorder? = null

    fun setPreset(preset: Int) = renderer.setPreset(preset)
    fun setBassSolo(enabled: Boolean) = renderer.setBassSolo(enabled)
    fun setDebugSpeedJourney(enabled: Boolean) = renderer.setDebugSpeedJourney(enabled)
    fun armCapture(file: File) { captureFile = file }

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
        var capture: PcmCapture? = null
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
            capture = captureFile?.let { PcmCapture(it) }
            captureFile = null
            val pcm = ShortArray(1024 * SoundRenderer.CHANNELS)
            while (isCurrent(value)) {
                renderer.render(pcm)
                var offset = 0
                while (offset < pcm.size && isCurrent(value)) {
                    val n = current.write(pcm, offset, pcm.size - offset)
                    if (n <= 0) throw IllegalStateException("AudioTrack write failed: $n")
                    capture?.write(pcm, offset, n)
                    recorder?.offer(pcm, offset, n)
                    offset += n
                }
            }
        } catch (ex: Exception) {
            if (isCurrent(value)) mainHandler.post { if (generation == value) onFailure(RuntimeException(ex)) }
        } finally {
            capture?.close()
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

/** Debug capture of the exact PCM accepted by AudioTrack, before the device output mixer. */
private class PcmCapture(private val file: File) {
    private val output = FileOutputStream(file)
    private val buffer = ByteArray(4096)
    private var bytesWritten = 0L

    init { output.write(ByteArray(44)) }

    fun write(samples: ShortArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            val sample = samples[offset + i].toInt()
            buffer[i * 2] = sample.toByte()
            buffer[i * 2 + 1] = (sample ushr 8).toByte()
        }
        output.write(buffer, 0, count * 2)
        bytesWritten += count * 2
    }

    fun close() {
        output.close()
        RandomAccessFile(file, "rw").use { wav ->
            fun short(value: Int) { wav.write(value); wav.write(value ushr 8) }
            fun int(value: Long) { repeat(4) { wav.write((value ushr (it * 8)).toInt()) } }
            wav.writeBytes("RIFF")
            int(36 + bytesWritten)
            wav.writeBytes("WAVEfmt ")
            int(16)
            short(1)
            short(SoundRenderer.CHANNELS)
            int(SoundRenderer.RATE.toLong())
            int((SoundRenderer.RATE * SoundRenderer.CHANNELS * 2).toLong())
            short(SoundRenderer.CHANNELS * 2)
            short(16)
            wav.writeBytes("data")
            int(bytesWritten)
        }
    }
}
