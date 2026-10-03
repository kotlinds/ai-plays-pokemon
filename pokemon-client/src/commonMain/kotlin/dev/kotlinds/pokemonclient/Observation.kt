package dev.kotlinds.pokemonclient

import kotlinx.serialization.json.JsonObject

/**
 * What the agent knows about the game at one instant, produced by a [PokemonGame] reader.
 *
 * - [state] is the description of the screen sent to the AI as is (what a player would see).
 * - Everything else is structured data used by our own code: the UI summary, the agent's memory
 *   (which compares [facts] before/after each press), the timing of presses ([awaitingInput]),
 *   and in assisted mode the pathfinding over [map].
 */
data class Observation(
    val mode: GameMode,
    val location: Location?,
    /** One line for the UI, e.g. "Overworld · New Bark Town (12, 8)". */
    val summary: String,
    val state: JsonObject,
    /**
     * Small facts that identify what is on screen, compared before/after each press to tell the AI what
     * its press did ("facing west -> north", "dialogue advanced", "menu cursor 0 -> 1"...).
     * Keys are free-form but stable, e.g. "facing", "dialogue", "dialogue.page", "waiting_for", "screen",
     * "menu.cursor", "battle.menu", "party.hp". Values are short human-readable strings.
     */
    val facts: Map<String, String> = emptyMap(),
    /**
     * True when the game is waiting for the player (free to walk and not moving, a message waiting for a
     * button, a menu or battle prompt open...); false during animations, transitions, text printing.
     * The agent waits for it before deciding.
     */
    val awaitingInput: Boolean = true,
    /** Text of the message box on screen, if any (remembered by the agent). */
    val dialogue: String? = null,
    /** The tiles around the player, for the explored-map memory and assisted-mode pathfinding. */
    val map: LocalMap? = null,
    /**
     * Options of the menu currently waiting for a choice (yes/no, multichoice, start menu, battle menu...),
     * top to bottom, with the highlighted one; null when no menu is open. Used by assisted mode.
     */
    val menu: MenuState? = null,
    /** Story progress read from the game (e.g. "Got the Pokégear", "Got a starter"), for milestones. */
    val progress: List<String> = emptyList(),
    /** What the story expects next, derived from game flags (optional assist), e.g. "Go to Prof. Elm's lab". */
    val storyGoal: String? = null,
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

enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

    companion object {
        fun parse(value: String?): Direction? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Where the player stands: map + tile coordinates (x grows east, y grows south). */
data class Location(
    val mapId: Int,
    val mapName: String,
    val x: Int,
    val y: Int,
    val facing: Direction? = null,
)

/**
 * A rectangle of tiles around the player, in map coordinates.
 * `tiles[row][column]` is the tile at (originX + column, originY + row).
 */
data class LocalMap(
    val originX: Int,
    val originY: Int,
    val tiles: List<List<Tile>>,
    val pointsOfInterest: List<PointOfInterest> = emptyList(),
) {
    val height: Int get() = tiles.size
    val width: Int get() = tiles.firstOrNull()?.size ?: 0

    fun tileAt(x: Int, y: Int): Tile = tiles.getOrNull(y - originY)?.getOrNull(x - originX) ?: Tile.UNKNOWN
}

/** What a tile is, for movement purposes. */
enum class Tile(val walkable: Boolean) {
    WALKABLE(true),
    TALL_GRASS(true),
    /** Steps onto it change the map (door, stairs, ladder, exit mat). */
    WARP(true),
    /** Blocked: wall, furniture, tree, sign, counter... */
    BLOCKED(false),
    /** A person or object standing there (blocks movement, can be talked to / examined). */
    OCCUPIED(false),
    WATER(false),
    /** Ledges can only be jumped in their direction. */
    LEDGE_SOUTH(false), LEDGE_NORTH(false), LEDGE_WEST(false), LEDGE_EAST(false),
    /** Outside the map / not loaded / not part of the room. */
    UNKNOWN(false),
}

/** Something worth going to: an exit, a person, an object to examine, an item. */
data class PointOfInterest(
    val kind: Kind,
    val x: Int,
    val y: Int,
    /** Human-readable, e.g. "stairs to New Bark Player House 1F", "Mom", "PC", "TV", "item ball". */
    val label: String,
    /**
     * For exits: the direction to press while standing on the warp tile when stepping on it isn't
     * enough (e.g. exit mats at the edge of a room: stand on it and press south). Null when walking
     * onto the tile is enough.
     */
    val exitDirection: Direction? = null,
) {
    enum class Kind { EXIT, PERSON, OBJECT, ITEM }
}

/** A menu waiting for a choice. */
data class MenuState(
    /** e.g. "yes/no", "multichoice", "start menu", "battle: main", "battle: fight". */
    val kind: String,
    /** Labels, in index order; [EMPTY_SLOT] marks a placeholder that can't be chosen (keeps grid indexing). */
    val options: List<String>,
    /** Index of the highlighted option in [options], when known. */
    val cursor: Int?,
    /** How the options are laid out, which tells which D-pad button moves the cursor. */
    val layout: Layout = Layout.VERTICAL,
    /**
     * Exact (row, column) of each option on screen, for irregular menus (e.g. the battle menu: FIGHT on
     * top, BAG / RUN / POKéMON below). When null, positions follow [layout].
     */
    val positions: List<Position>? = null,
) {
    enum class Layout {
        VERTICAL,
        HORIZONTAL,

        /** Two columns, row by row (index 0 top-left, 1 top-right, 2 second row left...), any number of rows. */
        TWO_COLUMNS,
    }

    data class Position(val row: Int, val column: Int)

    /** Where option [index] is on screen. */
    fun position(index: Int): Position = positions?.getOrNull(index) ?: when (layout) {
        Layout.VERTICAL -> Position(index, 0)
        Layout.HORIZONTAL -> Position(0, index)
        Layout.TWO_COLUMNS -> Position(index / 2, index % 2)
    }

    companion object {
        const val EMPTY_SLOT = "-"
    }
}
