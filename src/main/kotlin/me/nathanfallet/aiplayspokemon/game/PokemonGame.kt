package me.nathanfallet.aiplayspokemon.game

/**
 * A Pokémon game we know how to "see" through RAM.
 *
 * Note: this interface, [Memory] and the per-game readers (see `hgss/`) are meant to be extracted later
 * into a standalone library (a common interface to read Pokémon games' RAM: position, dialogue, team,
 * battle... with one implementation per game), next to the kotlinds libraries. They stay local
 * until this project is further along.
 *
 * The AI doesn't look at the screen: it reads text/JSON. Each supported game therefore provides a
 * reader turning raw RAM into an [Observation] (what's going on + a JSON description for the AI).
 * Supporting another game (SoulSilver, Platinum, Emerald...) means implementing this interface.
 */
interface PokemonGame {
    /** Human-readable name, e.g. "Pokémon HeartGold (USA)". */
    val name: String

    /** Reads the current situation from a snapshot of the console's main RAM. */
    fun observe(memory: Memory): Observation
}
