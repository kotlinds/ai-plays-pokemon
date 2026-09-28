package me.nathanfallet.aiplayspokemon.game

import kotlinx.serialization.json.JsonObject

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

/**
 * What the agent knows about the game at one instant.
 *
 * [state] is the game-specific description sent to the model as is (what a player would see on
 * screen). [mode], [location] and [dialogue] are coarse facts used by our own code: the UI summary
 * and the agent's memory of what happened.
 */
data class Observation(
    val mode: GameMode,
    val location: Location?,
    /** One line for the UI, e.g. "Overworld · New Bark Town (12, 8)". */
    val summary: String,
    val state: JsonObject,
    /** Text of the message box on screen, if any (remembered by the agent). */
    val dialogue: String? = null,
)

/** The broad situation the player is in. */
enum class GameMode {
    /** Title screen, intro, or anything before the player is in the world. */
    INTRO,

    /** Walking around, free to move. */
    OVERWORLD,

    /** A message box or script is running (talking to someone, cutscene...). */
    DIALOGUE,

    /** A menu is open (start menu, bag, party...). */
    MENU,

    /** In a battle. */
    BATTLE,

    /** The reader couldn't tell. */
    UNKNOWN,
}

/** Where the player stands: map + tile coordinates. */
data class Location(val mapId: Int, val mapName: String, val x: Int, val y: Int)
