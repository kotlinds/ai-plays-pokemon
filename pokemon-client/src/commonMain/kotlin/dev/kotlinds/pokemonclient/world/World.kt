package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * The static world of a game, decoded from its ROM: areas of tiles with collision, behaviours and heights, and their
 * events (warps, signs, people, triggers). Games implement it with their own file formats (Gen 4: map headers,
 * matrices, land_data with BDHC heights, zone events); the graph and the pathfinding on top are shared.
 */
interface WorldSource {
    /**
     * The area containing zone (map) [zoneId]: all the zones sharing its coordinate space (on the Gen 4 overworld,
     * every outdoor zone of the region shares one matrix; a building is its own area). Null when unknown. Cached.
     */
    fun areaOf(zoneId: Int): Area?

    /** Number of zones (maps): ids are `0 until zoneCount` (0 when unknown). Used to find a map by name. */
    val zoneCount: Int get() = 0
}

/**
 * A rectangle of tiles in the coordinates the game reports for the player, with their properties and the events of
 * every zone inside it.
 */
class Area(
    /** Identifier of the coordinate space (the Gen 4 matrix id). */
    val id: Int,
    val name: String,
    /** World coordinates of the top-left tile of [tiles]. */
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    /** Row-major tiles (`tiles[(y - originY) * width + (x - originX)]`), null where nothing is loaded. */
    private val tiles: Array<TileInfo?>,
    val warps: List<Warp> = emptyList(),
    val signs: List<Sign> = emptyList(),
    val people: List<PersonTemplate> = emptyList(),
    val triggers: List<Trigger> = emptyList(),
    /** Zone (map) of each tile, row-major like the tiles, null where unknown. */
    private val zones: IntArray? = null,
    /** Triggers whose script warps the player elsewhere on the same zone (warp pads, trap tiles). */
    val scriptWarps: List<ScriptWarp> = emptyList(),
    /** Triggers whose script moves the player to another zone (holes to the floor below). */
    val triggerWarps: List<TriggerWarp> = emptyList(),
) {
    /** The zone (map) id at (x, y), when known. */
    fun zoneAt(x: Int, y: Int): Int? {
        if (zones == null || x < originX || y < originY || x >= originX + width || y >= originY + height) return null
        return zones[(y - originY) * width + (x - originX)].takeIf { it >= 0 }
    }

    /**
     * The bounding box of each zone's tiles (`[minX, minY, maxX, maxY]`, world coordinates), computed once: where to
     * look for a zone's tiles in a shared area.
     */
    val zoneBounds: Map<Int, IntArray> by lazy {
        val bounds = HashMap<Int, IntArray>()
        if (zones != null) for (i in zones.indices) {
            val z = zones[i].takeIf { it >= 0 } ?: continue
            val x = originX + i % width
            val y = originY + i / width
            val b = bounds.getOrPut(z) { intArrayOf(x, y, x, y) }
            if (x < b[0]) b[0] = x
            if (y < b[1]) b[1] = y
            if (x > b[2]) b[2] = x
            if (y > b[3]) b[3] = y
        }
        bounds
    }

    fun tile(x: Int, y: Int): TileInfo? {
        if (x < originX || y < originY || x >= originX + width || y >= originY + height) return null
        return tiles[(y - originY) * width + (x - originX)]
    }
}

/** One tile: collision, what it is, and the heights of the surfaces on it (several on bridges). */
data class TileInfo(
    val blocked: Boolean,
    val kind: TileKind,
    /** Heights (game units) of the walkable surfaces on this tile; empty when unknown (flat maps). */
    val heights: List<Int> = emptyList(),
)

/** What a tile is, for movement and display. Decoded per game from its tile behaviours. */
sealed interface TileKind {
    data object Floor : TileKind
    data object TallGrass : TileKind
    data object Wall : TileKind
    /** A ledge: can only be jumped in [jump] direction (the player lands 2 tiles further). */
    data class Ledge(val jump: Direction) : TileKind
    data class Water(val surfable: Boolean, val fishable: Boolean) : TileKind
    data object Waterfall : TileKind
    data object Whirlpool : TileKind
    /** Ice: stepping onto it slides the player in the same direction until a tile that isn't ice, or an obstacle. */
    data object Ice : TileKind
    data object Lava : TileKind
    data object Ladder : TileKind
    data object Counter : TileKind

    /** A PC (Pokémon storage, item storage): examined with A, never walked on. */
    data object Pc : TileKind
    data object Door : TileKind
    /**
     * A spinning arrow tile (Rocket Hideout): stepping on it pushes the player in [push] direction, tile after tile,
     * over any floor, turning on the next arrows, until a [SpinnerStop] tile or an obstacle.
     */
    data class Spinner(val push: Direction) : TileKind

    /** The tile that ends a [Spinner] push (plain floor otherwise). */
    data object SpinnerStop : TileKind

    /** A rocky wall climbed with Rock Climb. */
    data object RockClimb : TileKind
    data object Sand : TileKind
    data object Cave : TileKind

    /**
     * A bridge tile. The player gets on a bridge by stepping on a [start] tile and stays on it while walking bridge
     * tiles; a bridge [overWater] is walkable floor for a player on the bridge and water (surfed under) otherwise.
     */
    data class Bridge(val overWater: Boolean = false, val start: Boolean = false) : TileKind

    /** Floor with a railing (or a raised edge) on [blockedSides]: the player can't cross those sides of the tile. */
    data class Railing(val blockedSides: Set<Direction>) : TileKind
    /** A behaviour the decoder doesn't know (kept raw for diagnostics). */
    data class Unknown(val behavior: Int) : TileKind
}

/**
 * The field moves that open a way on the map: what a route needs when the map alone has none
 * ([RouteFailure.NeedsFieldMove]).
 */
enum class FieldMoveKind {
    SURF,
    CUT,
    ROCK_SMASH,

    /** Pushes boulders. */
    STRENGTH,
    WHIRLPOOL,
    WATERFALL,
    ROCK_CLIMB,
}

/** A warp: stepping on (x, y) (and, for edge mats, pressing [exitDirection]) leads to [targetZone]. */
data class Warp(val zone: Int, val id: Int, val x: Int, val y: Int, val targetZone: Int, val targetWarp: Int, val exitDirection: Direction? = null)

/** A sign / examinable object read with A, or an item hidden on the ground ([kind]). */
data class Sign(
    val zone: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val script: Int,
    val kind: SignKind = SignKind.SIGN,
    /** For a [SignKind.HIDDEN_ITEM]: the event flag set once it's picked up. */
    val flag: Int? = null,
)

/** What a [Sign] (a background event) is. */
enum class SignKind {
    /** Read with A (signs, notices, furniture with a script). */
    SIGN,

    /** An invisible item found with A (or the Dowsing Machine); gone once its flag is set. */
    HIDDEN_ITEM,
}

/** A person or object placed by the map's events (its live position may differ: read the RAM). */
data class PersonTemplate(
    val zone: Int,
    val id: Int,
    val sprite: Int,
    val x: Int,
    val y: Int,
    val facing: Direction?,
    /** Sight range for trainers (0 = not a trainer). */
    val sightRange: Int,
    val script: Int,
    /** Flag hiding this person once set (e.g. after an event), or 0. */
    val hiddenByFlag: Int,
    /** For an obstacle object (Cut tree, Rock Smash rock, Strength boulder): the field move that clears it. */
    val obstacle: FieldMoveKind? = null,
)

/** A coordinate trigger: stepping on it runs [script] while variable [variable] equals [value]. */
data class Trigger(val zone: Int, val id: Int, val x: Int, val y: Int, val width: Int, val height: Int, val script: Int, val variable: Int, val value: Int)

/**
 * A coordinate trigger ([Trigger] number [trigger] of [zone]) whose script warps the player to ([x], [y]) on the same
 * zone: a warp pad or a trap tile that the map's warp list doesn't show. Active while the trigger is.
 */
data class ScriptWarp(val zone: Int, val trigger: Int, val x: Int, val y: Int, val facing: Direction?)

/**
 * A coordinate trigger ([Trigger] number [trigger] of [zone]) at ([x], [y]) whose script moves the player to
 * ([toX], [toY]) on another zone [targetZone]: a hole the player falls through to the floor below (Victory Road,
 * Ice Path). One way; active while the trigger is.
 */
data class TriggerWarp(val zone: Int, val trigger: Int, val x: Int, val y: Int, val targetZone: Int, val toX: Int, val toY: Int)
