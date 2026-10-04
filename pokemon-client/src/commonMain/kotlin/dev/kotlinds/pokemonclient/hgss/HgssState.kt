package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.StoryFacts
import kotlinx.serialization.Serializable

/** High-level game mode, derived from the main app manager, the field sub-application and the field task stack. */
@Serializable
enum class GameMode {
    /** Nothing running yet / between top-level apps (soft reset, loading a save, fade between apps). */
    LOADING,
    INTRO_MOVIE,
    TITLE_SCREEN,

    /** "Continue / New game / Mystery gift..." menu (overlay 74). */
    MAIN_MENU,

    /** Professor Oak's speech + name selection before a new game starts (overlay 53). */
    NEW_GAME_INTRO,

    /** Overworld, player has control (no field task, map ready). */
    OVERWORLD,

    /** Overworld with a field task running that is not a script / menu (warp, door animation, encounter start...). */
    FIELD_BUSY,

    /** A field script is running without a visible message box (cutscene, NPC walking...). */
    SCRIPT,

    /** A field script is showing a message box (dialogue / sign / item pickup text). */
    DIALOGUE,

    /** Start menu (X) opened on the overworld. */
    START_MENU,

    /** A battle (battle overlay 12 running). */
    BATTLE,

    /** Another full-screen application launched from the field (party menu, bag, Pokégear, PC box...). */
    APP,
    UNKNOWN,
}

@Serializable
data class HgssState(
    /** gSystem.vblankCounter, monotonic frame counter. */
    val frame: Long,
    val mode: GameMode,
    /** Extra info for the mode: application name, "yes_no", "waiting_a_button", overlay ids... */
    val modeDetail: String? = null,
    /** True when the player can walk (FieldSystem_IsPlayerMovementAllowed && no sub-application). */
    val playerControllable: Boolean = false,
    /** True when the game waits for the player (see [HgssReader] awaitingInput rules). */
    val awaitingInput: Boolean = false,
    /** A palette fade / screen wipe / brightness transition is running. */
    val fading: Boolean = false,
    /** A map scene script is about to start (its "on frame" condition matches). */
    val scenePending: Boolean = false,
    val player: PlayerInfo? = null,
    val location: LocationInfo? = null,
    val dialogue: DialogueInfo? = null,
    val startMenu: StartMenuInfo? = null,
    /** A script menu waiting for a choice (yes/no, multichoice), on either screen. */
    val menu: MenuInfo? = null,
    /** The full-screen application in front of the map (bag, Pokégear, starter selection, mailbox...). */
    val app: AppInfo? = null,
    /** Story flags and vars (see [HgssProgress]). */
    val story: StoryInfo? = null,
    val party: List<PartyMon> = emptyList(),
    val battle: BattleInfo? = null,
    val surroundings: Surroundings? = null,
    val bag: List<BagPocket>? = null,
    /** Bag.registeredItems: [Y item, second touch-button item], 0 when free. */
    val registeredItems: List<Int> = emptyList(),
    /** Anything that looked inconsistent while reading (the rest of the snapshot is still usable). */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class PlayerInfo(
    val name: String,
    val gender: String,
    val trainerId: Long,
    val money: Long,
    val coins: Int,
    /** Names of the badges owned (Zephyr, Hive, ... then Kanto Boulder, Cascade, ...). */
    val badges: List<String>,
    val badgeCount: Int,
    /** Play time (PLAYERDATA.igt, include/igt.h): hours, minutes, seconds. */
    val playTime: Triple<Int, Int, Int>? = null,
    /** The badges owned by id: bit n of the Johto byte is n, bit n of the Kanto byte is 8 + n. */
    val badgeIds: Set<Int> = emptySet(),
)

@Serializable
data class LocationInfo(
    val mapId: Int,
    /** From the MAP_ constant, e.g. "New Bark Player House 2F". */
    val mapName: String,
    /** In-game location name (map header mapsec), e.g. "New Bark Town". */
    val locationName: String? = null,
    /** Global tile coordinates (the same space as warp/NPC coordinates). x grows east, z grows south. */
    val x: Int,
    val z: Int,
    /** Height of the player map object (bridges/multi-level maps). */
    val height: Int,
    val facing: String,
    val moving: Boolean,
    /** WALKING, CYCLING, SURFING, ... */
    val avatarState: String,
    val hasRunningShoes: Boolean? = null,
    /** Tile behavior under the player and the tile in front of the player. */
    val standingOn: String? = null,
    val facingTile: String? = null,
    val facingTileBlocked: Boolean? = null,
)

@Serializable
data class DialogueInfo(
    /** Full expanded text of the current message (all pages; lines separated by \n). */
    val text: String? = null,
    /** What the message box shows now: the (up to 2) lines of the current page printed so far. */
    val visibleText: String? = null,
    /** The text printer is still printing (or scrolling) this message. */
    val printing: Boolean = false,
    val messageBoxOpen: Boolean,
    /** printing / waiting_button / yes_no / multichoice / waiting_movement / waiting_app / running / pause */
    val waitingFor: String,
    val scriptId: Int? = null,
    /** Trainer id of the trainer who saw the player (its approach / intro script runs), null otherwise. */
    val engagedTrainer: Int? = null,
)

@Serializable
data class MenuInfo(
    /** yes_no, multichoice */
    val kind: String,
    val options: List<String>,
    val cursor: Int? = null,
    /** Columns of the grid; options are listed row by row (index = row * columns + column). */
    val columns: Int = 1,
    /** "touch" (bottom screen, also works with the D-pad) or "top". */
    val screen: String = "touch",
    /** True while the menu accepts input (not opening / closing). */
    val waiting: Boolean = true,
)

@Serializable
data class AppInfo(
    /** party_menu, bag, pokegear, choose_starter, mailbox... */
    val name: String,
    /** Human-readable title, e.g. "Starter selection". */
    val title: String,
    /** Entries the player chooses from, when they could be read (in D-pad order). */
    val entries: List<String> = emptyList(),
    val cursor: Int? = null,
    /** "vertical", "horizontal" or "grid2" (2 columns, row by row). */
    val layout: String = "vertical",
    /** What the screen says / asks now and how to answer, when known. */
    val prompt: String? = null,
    /** The app is in its input state (not opening, closing or animating). */
    val waiting: Boolean = false,
)

@Serializable
data class StoryInfo(
    /** Flags of interest that are set (ids, see [HgssProgress] and [HgssStoryTable.flagIds]). */
    val flags: Set<Int> = emptySet(),
    /** Vars of interest (id -> value). */
    val vars: Map<Int, Int> = emptyMap(),
    val hasRunningShoes: Boolean = false,
    val hasPokedex: Boolean = false,
    /** Badges owned, by index: 0..7 Johto (Zephyr...Rising), 8..15 Kanto (Boulder...Earth). */
    val badges: Set<Int> = emptySet(),
) : StoryFacts {
    override fun flag(id: Int) = id in flags
    override fun variable(id: Int) = vars[id] ?: 0
    override fun hasBadge(index: Int) = index in badges
}

/**
 * The start menu (X): icons on the touch screen in a fixed grid of 2 columns x 4 rows (src/start_menu.c
 * sActionToIconIndex: left column POKéDEX, POKéMON, BAG, POKéGEAR; right column TRAINER CARD, SAVE, OPTIONS).
 * The D-pad skips the icons that are not unlocked yet.
 */
@Serializable
data class StartMenuInfo(
    /** Visible icons row by row (left, right), "-" for an empty slot; rows without any icon are dropped. */
    val items: List<String>,
    /** Index into [items] of the highlighted icon. */
    val cursor: Int? = null,
    /** StartMenuTaskData.state == HANDLE_INPUT (3). */
    val waiting: Boolean = true,
    /** Visible icons per column, top to bottom (for the AI). */
    val leftColumn: List<String> = emptyList(),
    val rightColumn: List<String> = emptyList(),
    /** Language-independent id of each item (null for empty slots), aligned with [items]. */
    val ids: List<String?> = emptyList(),
)

@Serializable
data class MoveInfo(
    val id: Int,
    val name: String,
    val pp: Int,
    val maxPp: Int,
    val type: String? = null,
    val category: String? = null,
    val power: Int? = null,
    val accuracy: Int? = null,
)

@Serializable
data class PartyMon(
    val slot: Int,
    /** Personality value (PID) and full original trainer id: together the Pokémon's stable identity. */
    val personality: Long = 0,
    val otId: Long = 0,
    val heldItemId: Int = 0,
    /** Raw status word (STATUS_* bits). */
    val statusRaw: Long = 0,
    val species: Int,
    val speciesName: String,
    val nickname: String? = null,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    /** "OK", "SLEEP(n)", "POISON", "TOXIC", "BURN", "FREEZE", "PARALYSIS"; "FAINTED" when hp == 0. */
    val status: String,
    val types: List<String> = emptyList(),
    val heldItem: String? = null,
    val ability: String? = null,
    val isEgg: Boolean = false,
    val moves: List<MoveInfo> = emptyList(),
    val stats: Map<String, Int> = emptyMap(),
    val exp: Long = 0,
    val friendship: Int = 0,
    /** False if the checksum did not match after decryption (data may be garbage / being modified). */
    val checksumOk: Boolean = true,
    /** Why this reading can't be trusted ([HgssMonCheck]); empty for a good reading. */
    @kotlinx.serialization.Transient
    val problems: List<HgssMonCheck.Problem> = emptyList(),
) {
    /**
     * False when the values can't be real: the structure was read while the game was rewriting it (capture,
     * switch, map change...) or is corrupted. Such a reading must not be shown as is (keep the last good one).
     */
    val plausible: Boolean
        get() = checksumOk && problems.isEmpty() && species in 1..MAX_SPECIES && (isEgg || (level in 1..100 && maxHp > 0 && hp in 0..maxHp))

    companion object {
        /** Highest species id of Gen 4 games (Arceus). */
        const val MAX_SPECIES = 493
    }
}

@Serializable
data class Battler(
    val battlerId: Int,
    val personality: Long = 0,
    val otId: Long = 0,
    /** Raw status words: major status, status2 (volatile), move effect flags, and the counters word of unk88. */
    val statusRaw: Long = 0,
    val status2: Long = 0,
    val moveEffects: Long = 0,
    val counters: Long = 0,
    val side: String,
    val partySlot: Int? = null,
    val species: Int,
    val speciesName: String,
    val nickname: String? = null,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: String,
    val types: List<String>,
    val ability: String? = null,
    val heldItem: String? = null,
    val moves: List<MoveInfo> = emptyList(),
    /** Stat stages -6..+6: atk, def, speed, spAtk, spDef, accuracy, evasion. */
    val statStages: Map<String, Int> = emptyMap(),
    /** Ability id (`BattleMon.ability`), 0 = none. */
    val abilityId: Int = 0,
    /** Held item id (`BattleMon.item`), 0 = none. */
    val heldItemId: Int = 0,
    /** The flags word after the ability (`BattleMon.sendOutFlag...pressureFlag`): which switch-in abilities were announced. */
    val announceFlags: Long = 0,
)

@Serializable
data class TrainerInfo(
    val battlerId: Int,
    val trainerId: Int,
    val trainerClass: String,
    val name: String,
)

@Serializable
data class BattleInfo(
    val isWild: Boolean,
    val battleTypeFlags: List<String>,
    val isDoubles: Boolean,
    val turn: Int? = null,
    /** Party order in this battle (row of the player): slot shown first = the active Pokémon. */
    val partyOrder: List<Int> = emptyList(),
    val player: List<Battler>,
    val opponents: List<Battler>,
    val trainers: List<TrainerInfo> = emptyList(),
    /** Battle menu on the touch screen (MAIN, FIGHT, TARGET, YES_NO, ...; medium confidence). */
    val menu: String? = null,
    /** [y, x] of the D-pad cursor, null while it is hidden (touch mode: the first key press only shows it). */
    val menuCursor: List<Int>? = null,
    /** The battle waits for the player's choice (command, move, target, yes/no). */
    val awaitingInput: Boolean = false,
    /** Last battle message put in the message buffer (may be stale once printed). */
    val message: String? = null,
    val safariBalls: Int? = null,
)

@Serializable
data class MapObjectInfo(
    val id: Int,
    val sprite: String,
    val x: Int,
    val z: Int,
    /** Relative to the player (dx > 0: east, dz > 0: south). */
    val dx: Int,
    val dz: Int,
    val height: Int,
    val facing: String,
    val movement: Int,
    val type: Int,
    val scriptId: Int,
    val hidden: Boolean,
    /** npc, follower, item_ball, obstacle (Cut tree, rock...) */
    val kind: String,
    /** What a player would call it: "Mom", "woman", "item ball", "Marill (Pokémon)"... */
    val label: String = kind,
    /** Zone the object belongs to (MapObject.mapId): objects of the zone just left can still be around. */
    val mapId: Int = -1,
    /** Event flag that hides this object once set (MapObject.flagId), 0 when none: its event isn't done yet. */
    val eventFlag: Int = 0,
    /** First object parameter (`param[0]`): the sight range in tiles of a trainer (type 1), 0 otherwise. */
    val param0: Int = 0,
)

@Serializable
data class WarpInfo(
    val index: Int,
    val x: Int,
    val z: Int,
    val dx: Int,
    val dz: Int,
    val destMapId: Int,
    val destMapName: String,
    val destLocationName: String? = null,
    val destWarpId: Int,
    /** door, stairs, exit mat, entrance, ladder, escalator, warp panel, exit (from the tile behavior). */
    val kind: String = "exit",
    /**
     * Direction to press while standing on the warp tile (stairs, exit mats, ladders); null when walking onto the
     * tile (or into the door) is enough (src/field/field_control.c FieldSystem_CheckMapTransition).
     */
    val pressDirection: String? = null,
)

@Serializable
data class BgEventInfo(
    val x: Int,
    val z: Int,
    val dx: Int,
    val dz: Int,
    /** normal, sign, hidden_item */
    val type: String,
    val scriptId: Int,
    /** "sign", "PC", "TV", "bookshelf"... or "something to examine". */
    val label: String = type,
    /** Tile has the collision bit (objects on walls/furniture) */
    val blocked: Boolean = true,
)

@Serializable
data class TriggerInfo(
    /** Index of the trigger in the zone's coordinate events (the id targets use: `trigger:<index>`). */
    val index: Int = 0,
    val x: Int,
    val z: Int,
    val width: Int,
    val height: Int,
    val scriptId: Int,
    /** True if the trigger's var condition currently matches (it will fire when stepped on). */
    val active: Boolean?,
    /** The trigger fires while script variable [variable] equals [value]. */
    val variable: Int = 0,
    val value: Int = 0,
)

/**
 * Terrain around the player (without people/exits), cropped to what is real: tiles outside the matrix, not loaded,
 * or (indoors) outside the room walls are '-'. One char per tile:
 * '.' walkable, '"' tall grass, '~' water, '#' blocked, '_' '=' '{' '}' ledges (jump south / north / west / east),
 * '-' nothing (outside the map / room).
 */
@Serializable
data class LocalGrid(
    /** Global coordinates of the top-left cell. */
    val originX: Int,
    val originZ: Int,
    val width: Int,
    val height: Int,
    /** One string per row (north first), one char per tile. */
    val rows: List<String>,
) {
    fun at(x: Int, z: Int): Char = rows.getOrNull(z - originZ)?.getOrNull(x - originX) ?: '-'
}

/** A map next to the current one in the same matrix (walk past the edge to get there). */
@Serializable
data class NeighborArea(
    /** north, south, west, east */
    val direction: String,
    val mapId: Int,
    val name: String,
    /** First global coordinate (x for west/east, z for north/south) that belongs to it. */
    val boundary: Int,
)

@Serializable
data class Surroundings(
    val matrixWidth: Int? = null,
    val matrixHeight: Int? = null,
    /** Map header type: CITY_TOWN, ROUTE, INTERIOR, CAVE... */
    val mapType: String? = null,
    val grid: LocalGrid? = null,
    val objects: List<MapObjectInfo> = emptyList(),
    val warps: List<WarpInfo> = emptyList(),
    val bgEvents: List<BgEventInfo> = emptyList(),
    val triggers: List<TriggerInfo> = emptyList(),
    val neighbors: List<NeighborArea> = emptyList(),
)

@Serializable
data class BagItem(val id: Int, val name: String, val quantity: Int)

@Serializable
data class BagPocket(val pocket: String, val items: List<BagItem>)
