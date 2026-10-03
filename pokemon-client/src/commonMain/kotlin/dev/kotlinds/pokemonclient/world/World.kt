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
) {
    /** The zone (map) id at (x, y), when known. */
    fun zoneAt(x: Int, y: Int): Int? {
        if (zones == null || x < originX || y < originY || x >= originX + width || y >= originY + height) return null
        return zones[(y - originY) * width + (x - originX)].takeIf { it >= 0 }
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

/** A sign / examinable object read with A. */
data class Sign(val zone: Int, val id: Int, val x: Int, val y: Int, val script: Int)

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
)

/** A coordinate trigger: stepping on it runs [script] while variable [variable] equals [value]. */
data class Trigger(val zone: Int, val id: Int, val x: Int, val y: Int, val width: Int, val height: Int, val script: Int, val variable: Int, val value: Int)

/**
 * A coordinate trigger ([Trigger] number [trigger] of [zone]) whose script warps the player to ([x], [y]) on the same
 * zone: a warp pad or a trap tile that the map's warp list doesn't show. Active while the trigger is.
 */
data class ScriptWarp(val zone: Int, val trigger: Int, val x: Int, val y: Int, val facing: Direction?)
