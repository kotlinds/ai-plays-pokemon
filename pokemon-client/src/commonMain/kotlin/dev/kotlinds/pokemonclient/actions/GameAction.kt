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

    /**
     * Uses an item (on [target] when it needs a Pokémon, and on [move] for PP restoring items like Ether), in the field
     * or in battle. [batch] lists more uses done right after in the same bag session (field only: in battle one item
     * takes the turn).
     */
    data class UseItem(val item: ItemRef, val target: MonId? = null, val move: MoveRef? = null, val batch: List<ItemUse> = emptyList()) : GameAction {
        /** Every use, this one first. */
        val uses: List<ItemUse> get() = listOf(ItemUse(item, target, move)) + batch

        override val key get() = "use_item(" + uses.joinToString(", ") { it.key } + ")"
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

    /**
     * Buys [purchases] in one visit to the counter (in order), from the overworld, the clerk's menu or the shop list.
     * Empty: nothing to buy, the answer lists what the shop sells.
     */
    data class Buy(val purchases: List<Purchase>) : GameAction {
        constructor(item: ItemRef, quantity: Int) : this(listOf(Purchase(item, quantity)))

        override val key get() = "buy(${purchases.joinToString(",") { "${it.item.raw}x${it.quantity}" }})"
    }

    /** Sets the number shown on a quantity screen (shop, bag...), one checked press at a time; [confirm] presses A after. */
    data class SetQuantity(val value: Int, val confirm: Boolean = false) : GameAction {
        override val key get() = "set_quantity($value)"
    }

    /**
     * Several PC storage operations in one session at the PC (it isn't switched off in between), in order.
     */
    data class Pc(val operations: List<PcOperation>) : GameAction {
        override val key get() = "pc(${operations.joinToString(",") { it.key }})"
    }

    /** Sets the game's OPTIONS (null = leave as is), then leaves the options screen saving them. */
    data class SetOptions(
        val textSpeed: dev.kotlinds.pokemonclient.state.TextSpeed? = null,
        val battleScene: Boolean? = null,
        val battleStyle: dev.kotlinds.pokemonclient.state.BattleStyle? = null,
    ) : GameAction {
        override val key get() = "set_options(" + listOfNotNull(
            textSpeed?.let { "text_speed=${it.name.lowercase()}" },
            battleScene?.let { "battle_scene=${if (it) "on" else "off"}" },
            battleStyle?.let { "battle_style=${it.name.lowercase()}" },
        ).joinToString(",") + ")"
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

    /** Soft reset (L + R + START + SELECT), then CONTINUE: back to the last save, losing what came after. */
    data object SoftReset : GameAction {
        override val key = "soft_reset"
    }

    /** Heals the party at a Pokémon Center (talks to the nurse). */
    data object Heal : GameAction {
        override val key = "heal"
    }

    // endregion

    // region World (phase 3)

    /**
     * Walks to a tile (absolute map coordinates, on [map] when given: another floor or map) or to a target: an
     * object of this map, `exit:<direction>` (the map's edge towards a neighbouring map), a map's name, or
     * `frontier`; through warps, holes and map edges when needed, with the movement options.
     */
    data class GoTo(val x: Int?, val y: Int?, val target: String?, val options: MoveOptions = MoveOptions(), val map: String? = null) : GameAction {
        override val key get() = "go_to(${target ?: "$x,$y"}${map?.let { " on $it" } ?: ""})"
    }

    /** Walks next to [target] (a person, sign, object or item) and interacts with it (A). */
    data class Interact(val target: String) : GameAction {
        override val key get() = "interact($target)"
    }

    /** Walks in nearby known tall grass until the first wild encounter. */
    data object FindEncounter : GameAction {
        override val key = "find_encounter"
    }

    /**
     * Walks [tiles] tiles straight in [direction], turning first when the player faces elsewhere (a plain press of
     * the D-pad then would only turn). Stops early when the way is blocked or something happens.
     */
    data class Step(val direction: Direction, val tiles: Int = 1, val options: MoveOptions = MoveOptions()) : GameAction {
        override val key get() = "step(${direction.name.lowercase()},$tiles)"
    }

    // endregion

    /** Writes a note the agent will get back with the state (survives context compaction). */
    data class Note(val text: String) : GameAction {
        override val key get() = "note"
    }
}

/** One line of a [GameAction.Buy]: an item and how many (1-99). */
data class Purchase(val item: ItemRef, val quantity: Int)

/** One operation of a [GameAction.Pc] session. */
sealed interface PcOperation {
    val key: String

    /** Party → the first box with room, or box [box] (0-based) when given. */
    data class Deposit(val mon: MonId, val box: Int? = null) : PcOperation {
        override val key get() = "deposit($mon${box?.let { "→box$it" } ?: ""})"
    }

    /** A box → the party. */
    data class Withdraw(val mon: MonId) : PcOperation {
        override val key get() = "withdraw($mon)"
    }

    /** A stored Pokémon → box [box] (0-based), with the PC's MOVE POKéMON mode. */
    data class Move(val mon: MonId, val box: Int) : PcOperation {
        override val key get() = "move($mon→box$box)"
    }

    /** Party Pokémon [partyMon] ↔ stored Pokémon [boxMon]: [partyMon] goes to the box, [boxMon] joins the party. */
    data class Swap(val partyMon: MonId, val boxMon: MonId) : PcOperation {
        override val key get() = "swap($partyMon↔$boxMon)"
    }
}

/** Movement options of [GameAction.GoTo]. */
data class MoveOptions(
    val avoidTallGrass: Boolean = false,
    val avoidTrainers: Boolean = false,
    val acceptOneWay: Boolean = false,
    val run: Boolean = false,
    val bike: Boolean = false,
)

/** One item use of [GameAction.UseItem]: the item, the Pokémon it is used on, the move for a PP restoring item. */
data class ItemUse(val item: ItemRef, val target: MonId? = null, val move: MoveRef? = null) {
    val key: String get() = item.raw + (target?.let { "→$it" } ?: "") + (move?.let { "/${it.raw}" } ?: "")
}

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
