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
    /** The PC boxes (from the save data, readable anywhere), null when unreadable. */
    val storage: PcStorage? = null,
    /** The game's OPTIONS (text speed, battle scene, battle style), null when unreadable. */
    val options: GameOptions? = null,
    /** What the start menu offers yet (the story unlocks it bit by bit), null when unknown. */
    val startMenu: Set<StartMenuFeature>? = null,
)

/** Start menu entries the story unlocks. */
enum class StartMenuFeature {
    /** The menu itself opens (X) once the bag is given; until then nothing opens. */
    BAG,
    TRAINER_CARD,
    SAVE,
    OPTIONS,
}

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
    /** The mechanism, when it is a known one (a password door, a Pokémon to battle). */
    val cause: BlockerCause? = null,
)

/** The player's trainer card. */
data class PlayerInfo(
    val name: String,
    val money: Long,
    val badges: List<String>,
    val trainerId: Long,
    /** Time played, as the game counts it (the "Continue" screen and the trainer card show it). */
    val playTime: PlayTime? = null,
    /**
     * The badges owned, by the game's badge id (language-independent: rules check these, never [badges]' names).
     * HGSS: 0..7 Johto (Zephyr .. Rising), 8..15 Kanto (Boulder .. Earth). Empty when unknown.
     */
    val badgeIds: Set<Int> = emptySet(),
    /** The cards the Pokégear has gained (Map, Radio, Expansion), null when unknown. */
    val pokegearCards: Set<PokegearCard>? = null,
    /** Items Mom bought that wait at a Poké Mart's delivery man. */
    val momParcels: List<MomParcel> = emptyList(),
)

/** A play time counter. */
data class PlayTime(val hours: Int, val minutes: Int, val seconds: Int) {
    /** "24:59" as the game shows it (hours:minutes). */
    override fun toString() = "$hours:${minutes.toString().padStart(2, '0')}"
}

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
    /**
     * Map things already taken, by target id (`hidden_item:N` whose flag is set): still in the ROM's events, but
     * gone from the game.
     */
    val pickedUp: Set<String> = emptySet(),
    /** A trainer saw the player: it walks up to them and a battle follows (until the battle starts). */
    val trainerEncounter: Boolean = false,
    /** Whether the map lets the player fly away (outdoors), null when unknown. */
    val flyAllowed: Boolean? = null,
    /** Whether the map has a PC (Pokémon storage), null when unknown. */
    val hasPc: Boolean? = null,
    /**
     * Tiles of the map's coordinate triggers active right now (their variable has the awaited value): stepping on one
     * runs a script (a scene, a hole, a twig...). Filled when the game knows the map's events.
     */
    val activeTriggers: Set<Pair<Int, Int>> = emptySet(),
    /** Whether the bicycle can be ridden on this map, null when unknown. */
    val bikeAllowed: Boolean? = null,
    /**
     * Invisible things of the map that answer A ([FieldExaminable]), present now (gone once their event is done).
     * Their tiles block like walls. Walkthrough knowledge unless they have a cue: the views decide what to show.
     */
    val examinables: List<FieldExaminable> = emptyList(),
    /** The radio programme whose music plays over the field (the Pokégear radio left on), null when none. */
    val radioMusic: RadioStation? = null,
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
    /** For a trainer: who it is, whether it's beaten, how far it sees. */
    val trainer: FieldTrainer? = null,
    /** For a shop clerk: what the shop sells, when known before talking. */
    val catalog: List<ShopItem>? = null,
)

/** Obstacles placed on the map as objects, told by their sprite. */
enum class ObstacleKind {
    /** A small tree: Cut removes it. */
    CUT_TREE,

    /** A cracked rock: Rock Smash breaks it. */
    SMASH_ROCK,

    /** A boulder: Strength pushes it one tile. */
    BOULDER,

    /**
     * An ice block (the Mahogany Gym): sliding on the ice into it pushes it, until it stops against something; two
     * blocks meeting freeze together (both then face north and can't be pushed).
     */
    ICE_BLOCK,
}

/** People actions look for, told by their sprite (never by what they say). */
enum class PersonRole {
    /** Heals the party (Pokémon Center). */
    NURSE,

    /** Sells items (Poké Mart counter). */
    CLERK,

    /** A door or gate placed as an object (League doors, the Radio Tower and Rocket HQ doors): it opens after an event. */
    GATE,

    /** A shutter of a map puzzle (Goldenrod Tunnel): switches open and close it ([FieldState.puzzle]). */
    SHUTTER,

    /** Stands in a passage until a story event or a talk moves them ([GameState.story] blockers say what lifts it). */
    BLOCKER,
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
