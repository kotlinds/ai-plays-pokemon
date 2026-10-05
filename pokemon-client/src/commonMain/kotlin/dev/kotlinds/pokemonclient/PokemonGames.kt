package dev.kotlinds.pokemonclient

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.games.hgss.HgssGame
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.platinum.PlatinumGame
import dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion

/**
 * Registry of supported ROMs, keyed by the NDS game code.
 *
 * Several ROMs can share one reader: HeartGold and SoulSilver are the same program built with a
 * different flag, and regions mostly differ by addresses. Only register a code once its addresses
 * have been checked (a wrong address table silently feeds garbage to the model).
 */
object PokemonGames {

    private val games: Map<String, (NdsRom) -> PokemonGame> = buildMap {
        // HeartGold / SoulSilver: one reader, one address table per ROM (see HgssVersion.ALL).
        HgssVersion.ALL.forEach { version -> put(version.gameCode) { rom -> HgssGame(version, rom) } }
        // Platinum: early support (intro, position, current map), see PlatinumGame.
        PlatinumVersion.ALL.forEach { version -> put(version.gameCode) { rom -> PlatinumGame(version, rom) } }
    }

    /** The game of [rom] (its maps and data read from it), or null when it isn't supported. */
    fun detect(rom: NdsRom): PokemonGame? = games[rom.gameCode]?.invoke(rom)

    /** The game of the ROM file contents [bytes], or null when unreadable or not supported. */
    fun detect(bytes: ByteArray): PokemonGame? = runCatching { NdsRom.parse(bytes) }.getOrNull()?.let(::detect)

    /**
     * The 4-letter game code of an NDS ROM (read with kotlinds), e.g. "IPKE": I = DS game, PK = Pokémon HeartGold,
     * E = USA. Null when unreadable.
     */
    fun ndsGameCode(bytes: ByteArray): String? = runCatching { NdsRom.parse(bytes).gameCode }.getOrNull()
}
