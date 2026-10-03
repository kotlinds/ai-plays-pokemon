package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.Direction

/**
 * Everything the client knows about the game at one frame, decoded from RAM into common typed models.
 *
 * Games fill it from their own memory layout; agents, actions and views only use this.
 */
data class GameState(
    /** Frame number of the console when this state was read. */
    val frame: Long,
    /** What the game waits for right now. */
    val screen: Screen,
    val player: PlayerInfo?,
    /** The party in its saved order. In battle, values (HP, status) come from the battle's copy. */
    val party: List<PartyMon>,
    val bag: List<BagPocket>?,
    val battle: BattleState?,
    val field: FieldState?,
    /** Readings that were rejected or uncertain (a Pokémon whose checksum failed...). */
    val warnings: List<ReadWarning> = emptyList(),
    /**
     * Key items registered for quick use, in button order: the first one is used with Y in the field (no bag),
     * null for a free slot.
     */
    val registeredItems: List<ItemId?> = emptyList(),
    /**
     * Where the story stands (from the game's flags and variables): the next goal and what blocks the way. A
     * walkthrough-level knowledge: shown to agents only at [dev.kotlinds.pokemonclient.data.KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH].
     */
    val story: StoryState? = null,
)

/** The story's progress, read from the game's flags and variables (never from what is displayed). */
data class StoryState(
    /** The next step of the main story, or null when the table doesn't know (or the story is over). */
    val goal: StoryStep?,
    /** People and triggers of the current map that block a way, with why. */
    val blockers: List<Blocker> = emptyList(),
)

/** One step of a game's story table. */
data class StoryStep(
    /** Stable id of the step, e.g. `johto:badge_8` (language-independent). */
    val id: String,
    /** What to do, written for the agent (our walkthrough text, not the game's). */
    val description: String,
)

/** Something on the current map that blocks a way, and the condition that lifts it when it's known. */
data class Blocker(
    /** The person or trigger, as targets use it: `person:N`, `trigger:N`. */
    val target: String,
    /** Why it blocks and how to get past (our walkthrough text). */
    val reason: String,
)

/** The player's trainer card. */
data class PlayerInfo(
    val name: String,
    val money: Long,
    val badges: List<String>,
    val trainerId: Long,
)

/** One bag pocket. */
data class BagPocket(val name: String, val items: List<BagItem>)

/** One item stack. */
data class BagItem(val item: Named<ItemId>, val quantity: Int)

/** Where the player is in the world. */
data class FieldState(
    val mapId: Int,
    val mapName: String,
    val x: Int,
    val y: Int,
    /** Height of the player (map units), to tell bridges and upper floors apart. */
    val height: Int,
    val facing: Direction?,
    val movement: MovementMode,
    /** True while the player is between two tiles (walking, sliding...). */
    val moving: Boolean,
    /** People and objects on the map right now (live positions, not the map's initial ones). */
    val objects: List<FieldObject> = emptyList(),
    /** The puzzle of this map (switches, shutters, teleports) and its live state, when the game knows one here. */
    val puzzle: PuzzleState? = null,
)

/** A person or object standing on the map. */
data class FieldObject(
    /** Stable, language-independent id: `person:<event id>` (the map's local id of the object). */
    val id: String,
    /** What a player would call it (display only). */
    val label: String,
    val kind: FieldObjectKind,
    val x: Int,
    val y: Int,
    val facing: Direction?,
    /** The service this person offers, when it's one recipes rely on (heal, shop). */
    val role: PersonRole? = null,
    /** For an [FieldObjectKind.OBSTACLE]: which one (it tells the field move that clears it). */
    val obstacle: ObstacleKind? = null,
)

/** Obstacles placed on the map as objects, told by their sprite. */
enum class ObstacleKind {
    /** A small tree: Cut removes it. */
    CUT_TREE,

    /** A cracked rock: Rock Smash breaks it. */
    SMASH_ROCK,

    /** A boulder: Strength pushes it one tile. */
    BOULDER,
}

/** People actions look for, told by their sprite (never by what they say). */
enum class PersonRole {
    /** Heals the party (Pokémon Center). */
    NURSE,

    /** Sells items (Poké Mart counter). */
    CLERK,
}

/** Kinds of [FieldObject]. */
enum class FieldObjectKind {
    PERSON,

    /** The Pokémon walking behind the player: it moves out of the way, never an obstacle. */
    FOLLOWER,

    /** A Poké Ball lying on the ground: an item to pick up. */
    ITEM_BALL,

    /** A Cut tree, a Rock Smash rock, a Strength boulder... */
    OBSTACLE,
}

/** How the player moves. */
enum class MovementMode { WALK, RUN, BIKE, SURF }

/** A reading the decoder didn't trust. */
data class ReadWarning(val kind: Kind, val detail: String) {
    enum class Kind { POKEMON_CHECKSUM, INCONSISTENT_VALUE, UNREADABLE_POINTER, OTHER }
}
