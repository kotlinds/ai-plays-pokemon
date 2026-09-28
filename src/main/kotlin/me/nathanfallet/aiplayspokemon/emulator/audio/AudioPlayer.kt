package me.nathanfallet.aiplayspokemon.emulator.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays interleaved 16-bit stereo samples through the default audio device (Java Sound).
 *
 * Writes never block: when the device buffer is full (the emulator runs slightly faster than the
 * audio clock, or is fast-forwarding) samples are dropped instead of stalling the emulation thread.
 */
class AudioPlayer(sampleRate: Double) : AutoCloseable {

    private val line: SourceDataLine? = runCatching {
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        AudioSystem.getSourceDataLine(format).apply {
            // ~100 ms of buffer: small enough for low latency, large enough to absorb frame jitter.
            open(format, (sampleRate * 4 / 10).toInt() / 4 * 4)
            start()
        }
    }.onFailure { println("Audio disabled: ${it.message}") }.getOrNull()

    private var bytes = ByteArray(0)

    var muted = false

    fun play(samples: ShortArray, frames: Int) {
        val line = line ?: return
        if (muted) return
        val size = frames * 4 // 2 channels * 2 bytes
        if (bytes.size < size) bytes = ByteArray(size)
        for (i in 0 until frames * 2) {
            val sample = samples[i].toInt()
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        val writable = minOf(size, line.available()) / 4 * 4
        if (writable > 0) line.write(bytes, 0, writable)
    }

    override fun close() {
        line?.run {
            stop()
            close()
        }
    }
}
