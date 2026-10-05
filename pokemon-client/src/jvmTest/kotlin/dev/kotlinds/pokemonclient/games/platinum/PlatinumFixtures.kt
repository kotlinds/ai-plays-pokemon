package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.RamMemory
import org.junit.jupiter.api.Assumptions
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** Sparse RAM fixtures captured on Platinum (USA) with the bench (`fixture:pt_<name>`), in resources/platinum/. */
object PlatinumFixtures {
    fun load(name: String): Memory {
        val ram = ByteArray(4 shl 20)
        val stream = javaClass.getResourceAsStream("/platinum/$name.ram.sparse.gz") ?: error("missing fixture $name")
        DataInputStream(GZIPInputStream(stream)).use { input ->
            check(String(ByteArray(4).also { input.readFully(it) }) == "SPRM")
            while (true) {
                val addr = try { input.readInt() } catch (_: java.io.EOFException) { break }
                val len = input.readInt()
                input.readFully(ram, addr - 0x02000000, len)
            }
        }
        return RamMemory(ram)
    }
}

/**
 * The Platinum ROM of `PLATINUM_ROM` (Pokémon Platinum USA, game code CPUE), parsed once; tests needing it are skipped
 * without it (like the HGSS ROM tests with `POKEMON_ROM`).
 */
object PlatinumRom {
    private val rom: NdsRom? by lazy {
        val path = System.getenv("PLATINUM_ROM")?.takeIf { it.isNotBlank() } ?: return@lazy null
        val file = File(path).takeIf { it.isFile } ?: return@lazy null
        NdsRom.parse(file.readBytes()).takeIf { PlatinumVersion.forGameCode(it.gameCode) != null }
    }

    private val game: PlatinumGame? by lazy { rom?.let { PlatinumGame(PlatinumVersion.PLATINUM_US, it) } }

    fun requireRom(): NdsRom {
        Assumptions.assumeTrue(rom != null, "PLATINUM_ROM is not set to a Platinum (USA) ROM: test skipped")
        return rom!!
    }

    /** The game with its ROM (maps, text banks); skips the calling test without `PLATINUM_ROM`. */
    fun requireGame(): PlatinumGame {
        requireRom()
        return game!!
    }
}
