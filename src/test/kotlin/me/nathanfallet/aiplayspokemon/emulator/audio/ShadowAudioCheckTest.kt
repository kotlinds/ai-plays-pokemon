package me.nathanfallet.aiplayspokemon.emulator.audio

import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import me.nathanfallet.aiplayspokemon.emulator.FramePacer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * End to end check of music during pauses through the app's own classes ([ShadowAudio] and [AudioPlayer] as
 * [me.nathanfallet.aiplayspokemon.emulator.ConsoleHost] drives them, real time): 5 s of play, a 5 s pause (the
 * console thread only calls [ShadowAudio.onIdle]), then 10 s after the resume. Nothing is played: the audio output is
 * captured and written as a WAV.
 *
 * Needs a ROM and cores, so it only runs when `PAUSE_MUSIC_DATA` (a data directory with `cores/`), `POKEMON_ROM` and
 * `PAUSE_MUSIC_STATE` (a save state of `EMULATOR_CORE`, default DeSmuME, with music playing) are set; `PAUSE_MUSIC_OUT`
 * receives `pause_music_<core>.wav`.
 */
class ShadowAudioCheckTest {

    /** Records everything written, as a real device would play it (never full). */
    private class CaptureDevice : AudioDevice {
        val bytes = ByteArrayOutputStream()
        override fun available() = Int.MAX_VALUE
        @Synchronized override fun write(bytes: ByteArray, length: Int) = this.bytes.write(bytes, 0, length)
        override fun close() = Unit
    }

    @Test
    fun musicGoesOnDuringAPauseAndTheGameResumesInSync() {
        val data = System.getenv("PAUSE_MUSIC_DATA")?.let(Path::of) ?: return
        val rom = Path.of(System.getenv("POKEMON_ROM") ?: return)
        val stateFile = Path.of(System.getenv("PAUSE_MUSIC_STATE") ?: return)
        val spec = LibretroCoreSpec.forRom(rom, System.getenv("EMULATOR_CORE"))
        val device = CaptureDevice()
        lateinit var audio: AudioPlayer
        val main = LibretroConsole(spec, rom, data, onVideo = {}, onAudio = { s, n -> audio.play(s, n) })
        audio = AudioPlayer(main.sampleRate, device)
        val shadowAudio = ShadowAudio(spec, rom, data, audio, main.fps)
        try {
            check(main.loadState(Files.readAllBytes(stateFile)))
            main.step(1)
            Thread.sleep(1_000) // the shadow warms up in the background, like at app start
            val pacer = FramePacer(main.fps)
            fun play(seconds: Double) {
                pacer.reset()
                repeat((seconds * main.fps).roundToInt()) {
                    shadowAudio.beforeMainFrame(main)
                    main.step(1)
                    pacer.frameDone(fastForward = false)
                }
            }
            play(5.0)
            val ramAtPause = ram(main)
            val pauseEnd = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < pauseEnd) {
                shadowAudio.onIdle(main)
                Thread.sleep(5)
            }
            shadowAudio.beforeMainFrame(main)
            println("resume: ${shadowAudio.lastResult}")
            assertContentEquals(ramAtPause, ram(main), "the main RAM is the paused one")
            play(10.0)
            System.getenv("PAUSE_MUSIC_OUT")?.let { out ->
                writeWav(Path.of(out).resolve("pause_music_${spec.name.lowercase()}.wav"), device.bytes.toByteArray(), main.sampleRate.roundToInt())
            }
            assertIs<ResyncResult.Resynced>(shadowAudio.lastResult)
        } finally {
            shadowAudio.close()
            main.close()
        }
    }

    private fun ram(console: LibretroConsole) =
        ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

    private fun writeWav(file: Path, pcm: ByteArray, rate: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 4); putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size)
        }.array()
        Files.write(file, header + pcm)
        println("wrote $file")
    }
}
