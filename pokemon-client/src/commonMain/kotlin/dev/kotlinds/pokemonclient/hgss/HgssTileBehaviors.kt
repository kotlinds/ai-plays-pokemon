package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.TileKind

/**
 * HeartGold / SoulSilver tile behaviors (low byte of a terrain attribute) and their meaning for the common
 * [TileKind]. Values are the indices of `enum TILE_BEHAVIOR` (include/constants/metatile_behavior.h); the
 * predicates and flags come from src/metatile_behavior.c.
 *
 * Observed in the ROM (all 676 land data members): ledges (0x38/0x39/0x3B) and doors carry the collision bit — the
 * game moves the player by the behavior (jump, warp) — so a [TileKind.Ledge] or [TileKind.Door] is usually
 * `blocked`; pathfinding must look at the kind, not only at the collision bit.
 */
object HgssTileBehaviors {
    const val NONE = 0x00
    const val TALL_GRASS = 0x02
    const val VERY_TALL_GRASS = 0x03
    const val HEADBUTT = 0x06
    const val CAVE_FLOOR = 0x08
    const val WATER_RIVER = 0x10
    const val WHIRLPOOL = 0x11
    const val WATERFALL = 0x13
    const val WATER_SEA = 0x15
    const val PUDDLE = 0x16
    const val SHALLOW_WATER = 0x17
    const val PUDDLE_NO_SPLASHING = 0x1D
    const val ICE = 0x20
    const val SAND = 0x21
    const val MAGMA = 0x2C
    const val REFLECTIVE = 0x2D

    /** `TILE_BEHAVIOR_46`: a walkway crossing over another one (Goldenrod Gym): walkable on both levels (NOTES 17s). */
    const val BRIDGE = 0x2E
    const val JUMP_EAST = 0x38
    const val JUMP_WEST = 0x39
    const val JUMP_NORTH = 0x3A
    const val JUMP_SOUTH = 0x3B
    const val LADDER_NORTH = 0x3C
    const val LADDER_SOUTH = 0x3D
    const val LADDER_DOWN = 0x3E
    const val SLIDE_EAST = 0x40
    const val SLIDE_WEST = 0x41
    const val SLIDE_NORTH = 0x42
    const val SLIDE_SOUTH = 0x43
    const val ROCK_CLIMB_NORTH_SOUTH = 0x4B
    const val ROCK_CLIMB_EAST_WEST = 0x4C
    const val STOP_SLIDING = 0x4D
    const val WARP_STAIRS_EAST = 0x5E
    const val WARP_STAIRS_WEST = 0x5F
    const val WARP_ENTRANCE_EAST = 0x62
    const val WARP_ENTRANCE_WEST = 0x63
    const val WARP_ENTRANCE_NORTH = 0x64
    const val WARP_ENTRANCE_SOUTH = 0x65
    const val WARP_PANEL = 0x67
    const val DOOR = 0x69
    const val ESCALATOR_FLIP_FACE = 0x6A
    const val ESCALATOR = 0x6B
    const val WARP_EAST = 0x6C
    const val WARP_WEST = 0x6D
    const val WARP_NORTH = 0x6E
    const val WARP_SOUTH = 0x6F

    /** `TILE_BEHAVIOR_128`: the game talks to the object one tile further when facing it (FieldSystem_GetFacingObject). */
    const val COUNTER = 0x80

    /** `TILE_BEHAVIOR_PC` (metatile_behavior.h). */
    const val PC = 0x83
    const val MUD = 0xA4
    const val SNOW = 0xA8

    /** `TILE_BEHAVIOR_FLAG_SURFABLE` set in `sMetatileBehaviorFlags` (src/metatile_behavior.c). */
    private val SURFABLE = setOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x19, 0x2A, 0x50, 0x51, 0x52, 0x53, 0x73, 0x78, 0x7C)

    /** `TILE_BEHAVIOR_FLAG_ENCOUNTER` set without the surfable flag: walking encounters (grass-like floors). */
    private val LAND_ENCOUNTER = setOf(0x02, 0x03, 0x05, 0x08, 0x0B, 0x25, 0x72, 0x77, 0x7B, 0xA6, 0xA7)

    /** Plain floors with a cosmetic behavior (splashes, reflections, footprints, crossing walkways). */
    private val COSMETIC_FLOOR = setOf(PUDDLE, SHALLOW_WATER, PUDDLE_NO_SPLASHING, REFLECTIVE, BRIDGE, MUD, SNOW)

    private val WARPS = setOf(
        WARP_STAIRS_EAST, WARP_STAIRS_WEST, WARP_ENTRANCE_EAST, WARP_ENTRANCE_WEST, WARP_ENTRANCE_NORTH,
        WARP_ENTRANCE_SOUTH, WARP_PANEL, DOOR, ESCALATOR_FLIP_FACE, ESCALATOR, WARP_EAST, WARP_WEST, WARP_NORTH, WARP_SOUTH,
    )

    /** Surfable water (MetatileBehavior_IsSurfableWater); a fishing rod works when facing it (field_use_item.c:475). */
    fun isSurfable(behavior: Int): Boolean = behavior in SURFABLE

    /**
     * The common [TileKind] of a tile from its [behavior] and collision bit. Unrecognized behaviors stay
     * [TileKind.Unknown] (raw) unless they are a plain wall; notably, with no common kind yet: the one-way edges
     * 0x30-0x33/0x4A of raised walkways (Goldenrod Gym), the Cycling Road slope
     * 0x71 (Route 17) and 0x70 (Routes 47/48/17/26/27, Olivine; with 0x71-0x73 in the fishing checks, likely
     * bridges over water).
     */
    fun kind(behavior: Int, blocked: Boolean): TileKind = when (behavior) {
        TALL_GRASS, VERY_TALL_GRASS -> TileKind.TallGrass
        CAVE_FLOOR -> TileKind.Cave
        WHIRLPOOL -> TileKind.Whirlpool
        WATERFALL -> TileKind.Waterfall
        ICE -> TileKind.Ice
        SAND -> TileKind.Sand
        MAGMA -> TileKind.Lava
        JUMP_EAST -> TileKind.Ledge(Direction.EAST)
        JUMP_WEST -> TileKind.Ledge(Direction.WEST)
        JUMP_NORTH -> TileKind.Ledge(Direction.NORTH)
        JUMP_SOUTH -> TileKind.Ledge(Direction.SOUTH)
        LADDER_NORTH, LADDER_SOUTH, LADDER_DOWN -> TileKind.Ladder
        SLIDE_EAST -> TileKind.Spinner(Direction.EAST)
        SLIDE_WEST -> TileKind.Spinner(Direction.WEST)
        SLIDE_NORTH -> TileKind.Spinner(Direction.NORTH)
        SLIDE_SOUTH -> TileKind.Spinner(Direction.SOUTH)
        // The spinner push (overlay 1, ov01_021F31CC) ends on this tile.
        STOP_SLIDING -> if (blocked) TileKind.Wall else TileKind.SpinnerStop
        ROCK_CLIMB_NORTH_SOUTH, ROCK_CLIMB_EAST_WEST -> TileKind.RockClimb
        COUNTER -> TileKind.Counter
        PC -> TileKind.Pc
        in WARPS -> TileKind.Door
        in SURFABLE -> TileKind.Water(surfable = true, fishable = true)
        in LAND_ENCOUNTER -> TileKind.TallGrass
        in COSMETIC_FLOOR -> if (blocked) TileKind.Wall else TileKind.Floor
        NONE -> if (blocked) TileKind.Wall else TileKind.Floor
        // Headbutt trees, PCs, TVs, bookshelves, shop shelves... are examinable walls.
        else -> if (blocked && behavior in EXAMINABLE_WALLS) TileKind.Wall else TileKind.Unknown(behavior)
    }

    /** Headbutt trees and the furniture examined with A (HgssLabels.examinableBehavior). */
    private val EXAMINABLE_WALLS = setOf(HEADBUTT, 0x83, 0x85, 0x86, 0xE0, 0xE1, 0xE2, 0xE4, 0xE5, 0xEA, 0xEB, 0xEC)

    /**
     * The direction to press on a warp tile to use it (exit mats, side stairs, ladders), or null when stepping on it
     * is enough (doors, warp panels, escalators).
     */
    fun warpDirection(behavior: Int): Direction? = when (behavior) {
        WARP_ENTRANCE_EAST, WARP_EAST, WARP_STAIRS_EAST -> Direction.EAST
        WARP_ENTRANCE_WEST, WARP_WEST, WARP_STAIRS_WEST -> Direction.WEST
        WARP_ENTRANCE_NORTH, WARP_NORTH, LADDER_NORTH -> Direction.NORTH
        WARP_ENTRANCE_SOUTH, WARP_SOUTH, LADDER_SOUTH -> Direction.SOUTH
        else -> null
    }
}
