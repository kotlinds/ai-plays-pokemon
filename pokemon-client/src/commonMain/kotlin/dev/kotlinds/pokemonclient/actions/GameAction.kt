package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.MonId

/**
 * Everything an agent can ask the game to do, as a closed set of typed actions (the same set for MCP agents, our
 * own LLM loop, Jev and the pure-buttons mode). Parameters reference things by stable ids, never by a position
 * that changes between calls.
 *
 * The wire form is a JSON object with a `type` (see [ActionRegistry]); [key] is a canonical one-line form, e.g.
 * `attack(move:ember→foe_left)`.
 */
sealed interface GameAction {
    val key: String

    // region Raw controls (pure mode)

    /** Presses one button once (a self-checking tap). */
    data class Press(val button: Button) : GameAction {
        override val key get() = "press(${button.name.lowercase()})"
    }

    /** Touches the bottom screen. */
    data class Touch(val point: TouchPoint) : GameAction {
        override val key get() = "touch(${point.x},${point.y})"
    }

    /** Lets the game run: [frames] frames, or until it expects input again when null. */
    data class Wait(val frames: Int? = null) : GameAction {
        override val key get() = "wait(${frames ?: "input"})"
    }

    // endregion

    // region Screens

    /** Presses A until the messages end, stopping at any choice. */
    data object AdvanceDialogue : GameAction {
        override val key = "advance_dialogue"
    }

    /** Picks an entry of the menu on screen by its stable id (`option:yes`, `item:17`...). */
    data class Choose(val entry: String) : GameAction {
        override val key get() = "choose($entry)"
    }

    /** Types a name on the naming keyboard and confirms it (empty = keep the default name). */
    data class EnterText(val text: String) : GameAction {
        override val key get() = "enter_text(\"$text\")"
    }

    // endregion

    // region Battle

    /** Uses one of the active Pokémon's moves, on [target] in double battles. */
    data class Attack(val move: MoveRef, val target: BattlerRef? = null) : GameAction {
        override val key get() = "attack(${move.raw}${target?.let { "→${it.wire}" } ?: ""})"
    }

    /** Sends [mon] in (a voluntary switch, or the replacement after a K.O.). */
    data class Switch(val mon: MonId) : GameAction {
        override val key get() = "switch($mon)"
    }

    /** Answers "switch Pokémon?" with no when the opponent sends a new one. */
    data object KeepBattling : GameAction {
        override val key = "keep_battling"
    }

    /** Flees a wild battle. */
    data object Run : GameAction {
        override val key = "run"
    }

    /** Throws a ball; [nickname] answers the nickname question after a capture (null = no nickname). */
    data class ThrowBall(val ball: ItemRef, val nickname: String? = null) : GameAction {
        override val key get() = "throw_ball(${ball.raw})"
    }

    /** Learns the new move by forgetting [forget], or gives up learning it when null. */
    data class LearnMove(val forget: MoveRef?) : GameAction {
        override val key get() = "learn_move(${forget?.raw ?: "skip"})"
    }

    // endregion

    // region Items, party, PC, shop

    /** Uses an item (on [target] when it needs a Pokémon, and on [move] for PP restoring items). */
    data class UseItem(val item: ItemRef, val target: MonId? = null, val move: MoveRef? = null) : GameAction {
        override val key get() = "use_item(${item.raw}${target?.let { "→$it" } ?: ""})"
    }

    /** Teaches a TM / HM to [mon], forgetting [forget] if it already knows four moves. */
    data class Teach(val item: ItemRef, val mon: MonId, val forget: MoveRef? = null) : GameAction {
        override val key get() = "teach(${item.raw}→$mon)"
    }

    /** Moves [mon] to [position] (1 = lead) in the party. */
    data class ReorderParty(val mon: MonId, val position: Int) : GameAction {
        override val key get() = "reorder_party($mon→$position)"
    }

    data class GiveItem(val mon: MonId, val item: ItemRef) : GameAction {
        override val key get() = "give_item(${item.raw}→$mon)"
    }

    data class TakeItem(val mon: MonId) : GameAction {
        override val key get() = "take_item($mon)"
    }

    data class Deposit(val mon: MonId) : GameAction {
        override val key get() = "deposit($mon)"
    }

    data class Withdraw(val mon: MonId) : GameAction {
        override val key get() = "withdraw($mon)"
    }

    /** Releases a Pokémon for good: refused unless [confirm] is true. */
    data class Release(val mon: MonId, val confirm: Boolean) : GameAction {
        override val key get() = "release($mon)"
    }

    data class Buy(val item: ItemRef, val quantity: Int) : GameAction {
        override val key get() = "buy(${item.raw}x$quantity)"
    }

    data class Sell(val item: ItemRef, val quantity: Int) : GameAction {
        override val key get() = "sell(${item.raw}x$quantity)"
    }

    /** Uses a key item from the bag (bicycle, Itemfinder...). */
    data class UseKeyItem(val item: ItemRef) : GameAction {
        override val key get() = "use_key_item(${item.raw})"
    }

    /** Registers a key item for quick use: the first registered item is used with Y, without opening the bag. */
    data class RegisterItem(val item: ItemRef) : GameAction {
        override val key get() = "register_item(${item.raw})"
    }

    /** One cast of a fishing rod: the server presses A on the exact frame of the bite. */
    data class Fish(val rod: ItemRef) : GameAction {
        override val key get() = "fish(${rod.raw})"
    }

    /** Flies to a town already visited. */
    data class Fly(val destination: String) : GameAction {
        override val key get() = "fly($destination)"
    }

    /** Saves the game. */
    data object SaveGame : GameAction {
        override val key = "save_game"
    }

    /** Heals the party at a Pokémon Center (talks to the nurse). */
    data object Heal : GameAction {
        override val key = "heal"
    }

    // endregion

    // region World (phase 3)

    /** Walks to a tile (absolute map coordinates) or next to a target, with the movement options. */
    data class GoTo(val x: Int?, val y: Int?, val target: String?, val options: MoveOptions = MoveOptions()) : GameAction {
        override val key get() = "go_to(${target ?: "$x,$y"})"
    }

    /** Walks as far as possible in [direction]. */
    data class Explore(val direction: Direction, val options: MoveOptions = MoveOptions()) : GameAction {
        override val key get() = "explore(${direction.name.lowercase()})"
    }

    /** Walks next to [target] (a person, sign, object or item) and interacts with it (A). */
    data class Interact(val target: String) : GameAction {
        override val key get() = "interact($target)"
    }

    /** Walks in nearby known tall grass until the first wild encounter. */
    data object FindEncounter : GameAction {
        override val key = "find_encounter"
    }

    // endregion

    /** Writes a note the agent will get back with the state (survives context compaction). */
    data class Note(val text: String) : GameAction {
        override val key get() = "note"
    }
}

/** Movement options of [GameAction.GoTo] / [GameAction.Explore]. */
data class MoveOptions(
    val avoidTallGrass: Boolean = false,
    val avoidTrainers: Boolean = false,
    val acceptOneWay: Boolean = false,
    val run: Boolean = false,
    val bike: Boolean = false,
)

/** A move named by the agent: `move:<id>` or its name (case and spaces ignored). */
data class MoveRef(val raw: String)

/** An item named by the agent: `item:<id>` or its name. */
data class ItemRef(val raw: String)

/** Matches a reference against an id + name: `<prefix>:<id>`, the bare id, or the name (case, spaces, punctuation ignored). */
internal fun matchesRef(raw: String, prefix: String, id: Int, name: String): Boolean {
    val value = raw.removePrefix("$prefix:")
    return value.toIntOrNull() == id || normalize(value) == normalize(name)
}

/** Lowercase letters and digits only, accents removed ("Poké Ball" = "poke ball" = "POKE-BALL"). */
private fun normalize(value: String) = value.lowercase().map { ACCENTS[it] ?: it }.filter { it.isLetterOrDigit() }.joinToString("")

private val ACCENTS = mapOf(
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'î' to 'i', 'ï' to 'i',
    'ô' to 'o', 'ö' to 'o', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
)
