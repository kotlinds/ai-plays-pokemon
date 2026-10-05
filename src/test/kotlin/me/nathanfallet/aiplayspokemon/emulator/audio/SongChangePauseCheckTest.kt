package me.nathanfallet.aiplayspokemon.emulator.audio

import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.ResyncRefusal
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End to end check of a pause asked while the game changes its song (the user's Route 22 case: just walked from
 * Viridian City onto Route 22, the game fading Viridian's music out to start Route 22's), through the app's
 * [ConsoleHost] in real time: the game runs a few frames, pauses for 4 s, resumes and plays 3 s, with
 * [ShadowAudio.waitForSongChange] on (the pause starts once Route 22's music plays, and is resynced) or, with
 * `PAUSE_MUSIC_SONG_CHANGE_WAIT=off`, off (the pause starts at once: the shadow's game starts Route 22's music, the
 * resume is refused and the music jumps back to Viridian's fade). One per run: a core can't be started twice in a
 * process. What the sound card played is written as a WAV.
 *
 * Needs a ROM and cores, so it only runs when `PAUSE_MUSIC_DATA` (a data directory with `cores/`; the test writes
 * `states/` there), `POKEMON_ROM` and `PAUSE_MUSIC_SONG_CHANGE_STATE` (a DeSmuME save state of HeartGold US taken just
 * after stepping onto Route 22 from Viridian City, the song change pending) are set; `PAUSE_MUSIC_OUT` receives
 * `song_change_wait_off.wav` / `song_change_wait_on.wav`.
 */
class SongChangePauseCheckTest {

    @Test
    fun aPauseDuringASongChangeStartsOnTheNewSongAndIsResynced() {
        val data = System.getenv("PAUSE_MUSIC_DATA")?.let(Path::of) ?: return
        val rom = Path.of(System.getenv("POKEMON_ROM") ?: return)
        val stateFile = Path.of(System.getenv("PAUSE_MUSIC_SONG_CHANGE_STATE") ?: return)
        val spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), System.getenv("EMULATOR_CORE"))
        Files.createDirectories(data.resolve("states"))
        Files.copy(stateFile, data.resolve("states").resolve("${rom.fileName}.state7"), StandardCopyOption.REPLACE_EXISTING)

        fun pauseOnce(wait: Boolean): ResyncResult {
            lateinit var device: RapidPausesCheckTest.RealtimeDevice
            val host = ConsoleHost(spec, rom, data) { rate -> RapidPausesCheckTest.RealtimeDevice(rate).also { device = it } }
            host.setWaitForSongChange(wait)
            val results = Collections.synchronizedList(mutableListOf<ResyncResult>())
            host.musicDuringPausesListener = { results += it }
            try {
                check(runBlocking { host.loadState(7) })
                host.resume()
                Thread.sleep(100) // a few frames: the pause comes right after the step into the route, as after an action
                host.pause()
                Thread.sleep(4_000)
                host.resume()
                Thread.sleep(3_000)
            } finally {
                host.close()
            }
            device.finish()
            System.getenv("PAUSE_MUSIC_OUT")?.let { out ->
                writeWav(Path.of(out).resolve("song_change_wait_${if (wait) "on" else "off"}.wav"), device.played.toByteArray(), device.rate)
            }
            println("wait for the song change $wait: ${results.joinToString()}")
            return results.single()
        }

        if (System.getenv("PAUSE_MUSIC_SONG_CHANGE_WAIT") == "off") {
            assertEquals(ResyncResult.Refused(ResyncRefusal.SongChanged), pauseOnce(wait = false))
        } else {
            assertIs<ResyncResult.Resynced>(pauseOnce(wait = true))
        }
    }

    private fun writeWav(file: Path, pcm: ByteArray, rate: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 4); putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size)
        }.array()
        Files.write(file, header + pcm)
        println("wrote $file")
    }
}
