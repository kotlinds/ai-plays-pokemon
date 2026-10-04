package me.nathanfallet.aiplayspokemon.emulator.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays interleaved 16-bit stereo samples through the default audio device (Java Sound).
 *
 * Writes never block: when the device buffer is full (the emulator runs slightly faster than the
 * audio clock, or is fast-forwarding) samples are dropped instead of stalling the emulation thread.
 *
 * Two sources take turns on the same output (the game, and the shadow console playing its music while the game is
 * paused, see [ShadowAudio]), so writes are synchronized; switching to a sound that doesn't continue the previous
 * one goes through a short fade ([playFadingOut] then [fadeIn]) instead of a click.
 */
class AudioPlayer(
    private val sampleRate: Double,
    /** Where the bytes go: the default audio device, or null when there is none. */
    private val line: AudioDevice? = AudioDevice.default(sampleRate),
) : AutoCloseable {

    private var bytes = ByteArray(0)

    /** Current volume factor (1 = as emitted) and its change per stereo frame while a fade runs. */
    private var gain = 1.0
    private var gainStep = 0.0

    @Volatile
    var muted = false

    @Synchronized
    fun play(samples: ShortArray, frames: Int) {
        val line = line ?: return
        if (muted) return
        val size = frames * 4 // 2 channels * 2 bytes
        if (bytes.size < size) bytes = ByteArray(size)
        for (frame in 0 until frames) {
            if (gainStep != 0.0) {
                gain = (gain + gainStep).coerceIn(0.0, 1.0)
                if (gain == 0.0 || gain == 1.0) gainStep = 0.0
            }
            for (channel in 0 until 2) {
                val i = frame * 2 + channel
                val sample = if (gain == 1.0) samples[i].toInt() else (samples[i] * gain).toInt()
                bytes[i * 2] = sample.toByte()
                bytes[i * 2 + 1] = (sample shr 8).toByte()
            }
        }
        val writable = minOf(size, line.available()) / 4 * 4
        if (writable > 0) line.write(bytes, writable)
    }

    /** Plays [samples] fading from the current volume down to silence over their length (the sound stops there). */
    @Synchronized
    fun playFadingOut(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        gainStep = -gain / frames
        play(samples, frames)
        gain = 0.0
        gainStep = 0.0
    }

    /** The next samples fade in from silence over [millis] milliseconds. */
    @Synchronized
    fun fadeIn(millis: Int = FADE_MILLIS) {
        gain = 0.0
        gainStep = 1.0 / maxOf(1.0, sampleRate * millis / 1000)
    }

    override fun close() {
        line?.close()
    }

    private companion object {
        /** Long enough to avoid a click, short enough to go unnoticed. */
        const val FADE_MILLIS = 20
    }
}

/** An output for 16-bit little-endian stereo bytes. */
interface AudioDevice : AutoCloseable {
    /** Bytes that can be written without blocking. */
    fun available(): Int

    /** Writes [length] bytes of [bytes]. */
    fun write(bytes: ByteArray, length: Int)

    companion object {
        /** The default device through Java Sound, or null (logged) when there is none. */
        fun default(sampleRate: Double): AudioDevice? = runCatching {
            val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
            val line = AudioSystem.getSourceDataLine(format).apply {
                // ~100 ms of buffer: small enough for low latency, large enough to absorb frame jitter.
                open(format, (sampleRate * 4 / 10).toInt() / 4 * 4)
                start()
            }
            JavaSoundDevice(line)
        }.onFailure { println("Audio disabled: ${it.message}") }.getOrNull()
    }
}

/** [AudioDevice] over a Java Sound line. */
private class JavaSoundDevice(private val line: SourceDataLine) : AudioDevice {
    override fun available() = line.available()
    override fun write(bytes: ByteArray, length: Int) {
        line.write(bytes, 0, length)
    }

    override fun close() {
        line.stop()
        line.close()
    }
}
