package me.nathanfallet.aiplayspokemon.game

import dev.kotlinds.NdsRom
import me.nathanfallet.aiplayspokemon.game.hgss.HgssGame
import me.nathanfallet.aiplayspokemon.game.hgss.HgssVersion
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * Registry of supported ROMs, keyed by the NDS game code.
 *
 * Several ROMs can share one reader: HeartGold and SoulSilver are the same program built with a
 * different flag, and regions mostly differ by addresses. Only register a code once its addresses
 * have been checked (a wrong address table silently feeds garbage to the model).
 */
object PokemonGames {

    private val games: Map<String, () -> PokemonGame> = buildMap {
        // HeartGold / SoulSilver: one reader, one address table per ROM (see HgssVersion.ALL).
        HgssVersion.ALL.forEach { version -> put(version.gameCode) { HgssGame(version) } }
    }

    /** Returns the reader for [rom], or null when the game isn't supported by the agent. */
    fun detect(rom: Path): PokemonGame? = ndsGameCode(rom)?.let { games[it] }?.invoke()

    /**
     * The 4-letter game code of an NDS ROM header (read with kotlinds), e.g. "IPKE":
     * I = DS game, PK = Pokémon HeartGold, E = USA.
     */
    fun ndsGameCode(rom: Path): String? = runCatching { NdsRom.parse(rom.readBytes()).gameCode }.getOrNull()
}
