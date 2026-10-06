package me.nathanfallet.aiplayspokemon.emulator.audio

import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import dev.kotlinds.pokemonclient.libretro.sound.Wav
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End to end check of music during pauses with rapid pause / resume cycles, as an MCP agent chaining calls makes
 * them (pauses of 0.2-2 s separated by 0.1-1 s of play), through the app's [ConsoleHost] itself, in real time. The
 * sound goes to a [RealtimeDevice] that plays it like a sound card would (a ~100 ms buffer drained by the clock,
 * silence when it runs dry), and what it played is written as a WAV, with the outcome of every resume and the
 * gaps the device heard.
 *
 * Needs a ROM and cores, so it only runs when `PAUSE_MUSIC_DATA` (a data directory with `cores/`; the test writes
 * `states/` there), `POKEMON_ROM` and `PAUSE_MUSIC_RAPID_STATE` (a save state of `EMULATOR_CORE`, default DeSmuME,
 * with music playing) are set. Optional: `PAUSE_MUSIC_CYCLES` (default 20), `PAUSE_MUSIC_SEED` (default 1),
 * `PAUSE_MUSIC_OUT` (receives `<PAUSE_MUSIC_NAME, default rapid>.wav`).
 */
class RapidPausesCheckTest {

    /**
     * A sound card: a buffer of [capacity] bytes drained at the sample rate by the wall clock. What it plays is
     * recorded ([played]); when the buffer runs dry it plays silence (an underrun, recorded in [gaps]).
     */
    class RealtimeDevice(private val sampleRate: Double) : AudioDevice {
        private val capacity = (sampleRate * 4 / 10).toInt() / 4 * 4

        /** Samples per second, rounded. */
        val rate: Int get() = sampleRate.roundToInt()
        private val queue = ArrayDeque<Byte>()
        val played = ByteArrayOutputStream()
        private var startedAt = 0L
        private var accountedFrames = 0L
        private var silentFrames = 0L

        /** Underruns after the first write: (start in seconds, length in ms). */
        val gaps = mutableListOf<Pair<Double, Double>>()

        @Synchronized
        private fun advance() {
            if (startedAt == 0L) return
            val due = ((System.nanoTime() - startedAt) * sampleRate / 1e9).toLong() - accountedFrames
            repeat(due.toInt()) {
                if (queue.size >= 4) {
                    repeat(4) { played.write(queue.removeFirst().toInt()) }
                    if (silentFrames > 0) {
                        gaps += (accountedFrames - silentFrames) / sampleRate to silentFrames * 1000 / sampleRate
                        silentFrames = 0
                    }
                } else {
                    repeat(4) { played.write(0) }
                    silentFrames++
                }
                accountedFrames++
            }
        }

        @Synchronized
        override fun available(): Int {
            advance()
            return capacity - queue.size
        }

        @Synchronized
        override fun write(bytes: ByteArray, length: Int) {
            if (startedAt == 0L) startedAt = System.nanoTime()
            advance()
            for (i in 0 until length) queue.addLast(bytes[i])
        }

        /** Seconds since the first write (the device's timeline, that of [gaps]). */
        @Synchronized
        fun now(): Double = if (startedAt == 0L) 0.0 else (System.nanoTime() - startedAt) / 1e9

        /** Plays what is left in the buffer. */
        @Synchronized
        fun finish() {
            advance()
            while (queue.isNotEmpty()) played.write(queue.removeFirst().toInt())
        }

        override fun close() = Unit
    }

    @Test
    fun rapidPausesThroughTheConsoleHost() {
        val data = System.getenv("PAUSE_MUSIC_DATA")?.let(Path::of) ?: return
        val rom = Path.of(System.getenv("POKEMON_ROM") ?: return)
        val stateFile = Path.of(System.getenv("PAUSE_MUSIC_RAPID_STATE") ?: return)
        val cycles = System.getenv("PAUSE_MUSIC_CYCLES")?.toInt() ?: 20
        val random = Random(System.getenv("PAUSE_MUSIC_SEED")?.toInt() ?: 1)
        val spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), System.getenv("EMULATOR_CORE"))
        Files.createDirectories(data.resolve("states"))
        Files.copy(stateFile, data.resolve("states").resolve("${rom.fileName}.state7"), StandardCopyOption.REPLACE_EXISTING)

        lateinit var device: RealtimeDevice
        val host = ConsoleHost(spec, rom, data) { rate -> RealtimeDevice(rate).also { device = it } }
        val results = Collections.synchronizedList(mutableListOf<ResyncResult>())
        host.musicDuringPausesListener = { results += it }
        val marks = mutableListOf<String>()
        fun mark(what: String) {
            marks += "%.2f %s".format(device.now(), what)
        }
        try {
            check(runBlocking { host.loadState(7) })
            host.resume()
            Thread.sleep(3_000)
            repeat(cycles) {
                mark("pause")
                host.pause()
                Thread.sleep(random.nextLong(200, 2_000))
                mark("resume")
                host.resume()
                Thread.sleep(random.nextLong(100, 1_000))
            }
            Thread.sleep(2_000)
        } finally {
            host.close()
        }
        device.finish()
        val outcomes = results.groupingBy { result ->
            when (result) {
                is ResyncResult.Resynced -> "resynced"
                is ResyncResult.Refused -> result.reason::class.simpleName!!
                is ResyncResult.Aborted -> "aborted"
            }
        }.eachCount()
        val gaps = device.gaps.filter { it.second >= 5.0 }
        println("rapid pauses: $cycles cycles, ${results.size} resumes with a shadow playing, outcomes $outcomes")
        println("underruns >= 5 ms: ${gaps.size}, total ${"%.0f".format(gaps.sumOf { it.second })} ms: " +
            gaps.joinToString { "%.2fs %.0fms".format(it.first, it.second) })
        println("timeline: " + marks.joinToString(" | "))
        System.getenv("PAUSE_MUSIC_OUT")?.let { out ->
            val name = System.getenv("PAUSE_MUSIC_NAME") ?: "rapid"
            Files.write(Path.of(out).resolve("$name.wav"), Wav.encode(device.played.toByteArray(), device.rate))
        }
        assertTrue(results.none { it is ResyncResult.Aborted })
    }
}
