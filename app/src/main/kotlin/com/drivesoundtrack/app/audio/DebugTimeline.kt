package com.drivesoundtrack.app.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Debug builds only (for showcase videos): everything the app plays (music, instrument previews, tap sounds)
 * on one wall-clock timeline from [start] to [finish], so pauses and taps between playbacks are kept in place.
 * Music continues on its own audio clock from the moment the engine starts; one-shot sounds land at the time
 * they are played.
 */
object DebugTimeline {
    private const val CH = SoundRenderer.CHANNELS
    private const val CHUNK = SoundRenderer.RATE * CH * 30
    private var file: File? = null
    private var startNanos = 0L
    private var pcm = ShortArray(0)
    private var length = 0
    private var stream = -1
    private var streamOwner = 0L

    @Synchronized fun start(target: File) {
        file = target; startNanos = System.nanoTime(); pcm = ShortArray(CHUNK); length = 0; stream = -1
    }

    private fun now(): Int = ((System.nanoTime() - startNanos) * SoundRenderer.RATE / 1_000_000_000L).toInt() * CH

    /** An engine run begins; its samples follow from now. */
    @Synchronized fun streamStarted(owner: Long) {
        if (file == null) return
        stream = now(); streamOwner = owner
    }

    @Synchronized fun streamStopped(owner: Long) { if (owner == streamOwner) stream = -1 }

    @Synchronized fun writeStream(owner: Long, samples: ShortArray, offset: Int, count: Int) {
        if (file == null || stream < 0 || owner != streamOwner) return
        mix(stream, samples, offset, count); stream += count
    }

    /** A one-shot sound played now. */
    @Synchronized fun writeNow(samples: ShortArray) { if (file != null) mix(now(), samples, 0, samples.size) }

    private fun mix(at: Int, samples: ShortArray, offset: Int, count: Int) {
        if (at + count > pcm.size) pcm = pcm.copyOf(at + count + CHUNK)
        for (i in 0 until count) pcm[at + i] = (pcm[at + i] + samples[offset + i]).coerceIn(-32768, 32767).toShort()
        length = maxOf(length, at + count)
    }

    /** Writes the WAV (16-bit stereo) up to now and ends the capture. */
    @Synchronized fun finish() {
        val target = file ?: return
        file = null
        val total = maxOf(length, now())
        if (total > pcm.size) pcm = pcm.copyOf(total)
        BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
            fun int(v: Int) { repeat(4) { out.write(v ushr (it * 8)) } }
            fun short(v: Int) { out.write(v); out.write(v ushr 8) }
            out.write("RIFF".toByteArray()); int(36 + total * 2); out.write("WAVEfmt ".toByteArray())
            int(16); short(1); short(CH); int(SoundRenderer.RATE); int(SoundRenderer.RATE * CH * 2); short(CH * 2); short(16)
            out.write("data".toByteArray()); int(total * 2)
            for (i in 0 until total) short(pcm[i].toInt())
        }
        pcm = ShortArray(0)
    }
}
