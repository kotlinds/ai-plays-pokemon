package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.NdsRom
import org.junit.jupiter.api.Assumptions
import java.io.File

/**
 * The user's HeartGold (USA) ROM for the world decoder tests. ROMs are not committed: the tests that need one read
 * the path from the `POKEMON_ROM` environment variable and are skipped (with a message) when it is not set.
 */
object HgssWorldRom {
    /** The parsed ROM and its version, shared by every ROM test (parsed once). */
    private val rom: Pair<NdsRom, HgssVersion>? by lazy {
        val path = System.getenv("POKEMON_ROM")?.takeIf { it.isNotBlank() } ?: return@lazy null
        val file = File(path).takeIf { it.isFile } ?: return@lazy null
        val rom = NdsRom.parse(file.readBytes())
        val version = HgssVersion.forGameCode(rom.gameCode) ?: return@lazy null
        rom to version
    }

    private val world: HgssWorldSource? by lazy { rom?.let { (r, v) -> HgssWorldSource(r, v) } }

    private val data: HgssGameData? by lazy { rom?.let { (r, v) -> HgssGameData(r, v) } }

    /** The game data on the ROM of `POKEMON_ROM`; skips the calling test when there is none. */
    fun requireData(): HgssGameData {
        val d = data
        Assumptions.assumeTrue(d != null, "POKEMON_ROM is not set to a HeartGold (USA) ROM: game data test skipped")
        return d!!
    }

    /** The decoder on the ROM of `POKEMON_ROM`; skips the calling test when there is none. */
    fun require(): HgssWorldSource {
        val w = world
        Assumptions.assumeTrue(w != null, "POKEMON_ROM is not set to a HeartGold (USA) ROM: world decoder test skipped")
        return w!!
    }
}
