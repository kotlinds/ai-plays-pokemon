package dev.kotlinds.pokemonclient

import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * A Pokémon game we know how to "see" through RAM and play through the console port.
 *
 * Part of the `pokemon-client` library (dev.kotlinds.pokemonclient): a common interface to read Pokémon
 * games (position, dialogue, team, battle...) and drive them, with one implementation per game.
 *
 * The AI doesn't look at the screen: it reads text/JSON. Each supported game therefore provides a
 * reader turning raw RAM into an [Observation] (what's going on + a JSON description for the AI).
 * Supporting another game (SoulSilver, Platinum, Emerald...) means implementing this interface.
 */
interface PokemonGame {
    /** Human-readable name, e.g. "Pokémon HeartGold (USA)". */
    val name: String

    /**
     * Reads the current situation from a snapshot of the console's main RAM (older agent-facing view). By default it
     * is derived from [state] ([Observation.of]): a game only overrides it to give more (its legacy text map...).
     */
    fun observe(memory: Memory): Observation = Observation.of(state(memory))

    /** Reads the current situation into the common typed model. */
    fun state(memory: Memory): GameState

    /** The static maps of the game (tiles, warps, people...) read from the ROM, or null when not available. */
    val world: dev.kotlinds.pokemonclient.world.WorldSource? get() = null

    /** The game's data (species, moves, items, types...) read from the ROM, or null when not available. */
    val data: dev.kotlinds.pokemonclient.data.GameData? get() = null

    /** The name of map (zone) [id], for display, or null when unknown. */
    fun zoneName(id: Int): String? = null

    /**
     * The value of the game's script variable [id] (what map triggers and events check), or null when this game
     * can't tell. Read only.
     */
    fun scriptVariable(memory: Memory, id: Int): Int? = null

    /** Reads which buttons the game has registered, so presses can check themselves (see [ActionScope.tap]). */
    val inputProbe: InputProbe

    /**
     * What field move [move] needs outside battle in this game (the move, the badge), or null when the game doesn't
     * have it. Routes use the field moves whose rule the party meets (Surf, Waterfall, Cut...).
     */
    fun fieldMoveRule(move: dev.kotlinds.pokemonclient.world.FieldMoveKind): dev.kotlinds.pokemonclient.world.FieldMoveRule? = null

    /**
     * Where to touch the bottom screen in the field to use the registered item of [slot] (0 = the first one, also on
     * Y), or null when this game has no such button.
     */
    fun registeredItemTouch(slot: Int): dev.kotlinds.pokemonclient.console.TouchPoint? = null

    /** The item id of the bicycle (a key item ridden from the field), or null when this game has none. */
    val bicycleItem: Int? get() = null
}
