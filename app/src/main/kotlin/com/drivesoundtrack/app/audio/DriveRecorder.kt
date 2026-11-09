package com.drivesoundtrack.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records the music of the current drive to AAC (.m4a) while it plays, so the driver can keep it.
 * The audio thread only copies PCM into a queue; encoding happens on the recorder's own thread.
 * One session lasts from play to pause and is capped at [MAX_SECONDS]; the finished file is
 * [lastDrive] until the next session ends.
 */
class DriveRecorder(private val directory: File) {
    companion object {
        const val MAX_SECONDS = 30 * 60
        private const val BIT_RATE = 160_000
        private val END = ShortArray(0)
    }

    private var queue = LinkedBlockingQueue<ShortArray>(512)
    private var thread: Thread? = null
    @Volatile private var framesQueued = 0L
    @Volatile var active = false; private set
    /** Seconds recorded in the current session. */
    val seconds: Double get() = framesQueued.toDouble() / SoundRenderer.RATE
    /** The last finished session, if it is long enough to keep. */
    @Volatile var lastDrive: File? = null; private set
    @Volatile var lastDriveSeconds = 0.0; private set

    @Synchronized fun begin() {
        if (active) return
        directory.mkdirs()
        val target = File(directory, "drive-recording.m4a")
        queue = LinkedBlockingQueue(512)
        framesQueued = 0
        val input = queue
        thread = Thread({ encode(input, target) }, "drive-recorder").also { it.start() }
        active = true
    }

    /** Called from the audio thread with the PCM that was just played. Never blocks. */
    fun offer(pcm: ShortArray, offset: Int, count: Int) {
        if (!active || framesQueued >= MAX_SECONDS.toLong() * SoundRenderer.RATE) return
        if (queue.offer(pcm.copyOfRange(offset, offset + count))) framesQueued += count / SoundRenderer.CHANNELS
    }

    /** Finishes the session; returns the kept file or null when it was too short. */
    @Synchronized fun end(): File? {
        if (!active) return lastDrive
        active = false
        val recorded = seconds
        queue.put(END)
        thread?.join(5000)
        thread = null
        val finished = File(directory, "drive-recording.m4a")
        if (recorded >= 3.0 && finished.length() > 0) {
            val kept = File(directory, "last-drive.m4a")
            kept.delete()
            if (finished.renameTo(kept)) { lastDrive = kept; lastDriveSeconds = recorded }
        }
        return lastDrive
    }

    private fun encode(input: LinkedBlockingQueue<ShortArray>, target: File) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SoundRenderer.RATE, SoundRenderer.CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            target.delete()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var framesIn = 0L
            var pending: ShortArray? = null
            var pendingOffset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    if (pending == null) { pending = input.poll(20, TimeUnit.MILLISECONDS); pendingOffset = 0 }
                    val chunk = pending
                    if (chunk != null) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val timeUs = framesIn * 1_000_000L / SoundRenderer.RATE
                            if (chunk === END) {
                                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true; pending = null
                            } else {
                                val samples = minOf(chunk.size - pendingOffset, buffer.capacity() / 2)
                                for (i in 0 until samples) {
                                    val s = chunk[pendingOffset + i].toInt()
                                    buffer.put(s.toByte()); buffer.put((s shr 8).toByte())
                                }
                                codec.queueInputBuffer(index, 0, samples * 2, timeUs, 0)
                                framesIn += samples / SoundRenderer.CHANNELS
                                pendingOffset += samples
                                if (pendingOffset >= chunk.size) pending = null
                            }
                        }
                    }
                }
                // Drain whatever the encoder has ready.
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                        }
                        else -> break
                    }
                }
            }
            if (track >= 0) muxer.stop()
        } catch (ex: Exception) {
            Log.w("DriveSoundtrack", "Drive recording failed", ex)
            target.delete()
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            try { muxer?.release() } catch (_: Exception) { }
        }
    }
}
