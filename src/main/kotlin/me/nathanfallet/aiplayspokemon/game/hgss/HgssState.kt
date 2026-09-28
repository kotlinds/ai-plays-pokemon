package me.nathanfallet.aiplayspokemon.game.hgss

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
    val player: PlayerInfo? = null,
    val location: LocationInfo? = null,
    val dialogue: DialogueInfo? = null,
    val startMenu: StartMenuInfo? = null,
    val party: List<PartyMon> = emptyList(),
    val battle: BattleInfo? = null,
    val surroundings: Surroundings? = null,
    val bag: List<BagPocket>? = null,
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
    val messageBoxOpen: Boolean,
    /** printing / waiting_button / yes_no / multichoice / waiting_movement / waiting_app / running / pause */
    val waitingFor: String,
    val scriptId: Int? = null,
)

@Serializable
data class StartMenuInfo(
    /** Menu entries top to bottom (POKEDEX, POKEMON, BAG, POKEGEAR, TRAINER_CARD, SAVE, OPTIONS...). */
    val items: List<String>,
    /** Cursor index into [items] (FieldSystem.unkD3, medium confidence). */
    val cursor: Int? = null,
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
)

@Serializable
data class Battler(
    val battlerId: Int,
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
    val player: List<Battler>,
    val opponents: List<Battler>,
    val trainers: List<TrainerInfo> = emptyList(),
    /** Battle menu on the touch screen (MAIN, FIGHT, TARGET, YES_NO, ...; medium confidence). */
    val menu: String? = null,
    val menuCursor: List<Int>? = null,
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
    /** npc, follower, item_ball, other */
    val kind: String,
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
)

@Serializable
data class TriggerInfo(
    val x: Int,
    val z: Int,
    val width: Int,
    val height: Int,
    val scriptId: Int,
    /** True if the trigger's var condition currently matches (it will fire when stepped on). */
    val active: Boolean?,
)

@Serializable
data class LocalGrid(
    /** Global coordinates of the top-left cell. */
    val originX: Int,
    val originZ: Int,
    val width: Int,
    val height: Int,
    /** One string per row (north first), one char per tile. */
    val rows: List<String>,
    val legend: Map<String, String>,
)

@Serializable
data class Surroundings(
    val matrixWidth: Int? = null,
    val matrixHeight: Int? = null,
    val grid: LocalGrid? = null,
    val objects: List<MapObjectInfo> = emptyList(),
    val warps: List<WarpInfo> = emptyList(),
    val bgEvents: List<BgEventInfo> = emptyList(),
    val triggers: List<TriggerInfo> = emptyList(),
)

@Serializable
data class BagItem(val id: Int, val name: String, val quantity: Int)

@Serializable
data class BagPocket(val pocket: String, val items: List<BagItem>)
