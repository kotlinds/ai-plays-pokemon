package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.MovingPlatforms
import dev.kotlinds.pokemonclient.world.PlatformPose
import dev.kotlinds.pokemonclient.world.PlatformRide
import dev.kotlinds.pokemonclient.world.TileKind

/**
 * The Blackthorn Gym's three platforms on the lava (`MAP_BLACKTHORN_GYM`, T30GYM0101), with the game's rules.
 *
 * State: `Gymmick.blackthorn {u16 x[3]; u16 z[3]; u8 rot[3]}` (include/gymmick.h), set on entering the gym by
 * `BlackthornGymInit` → `InitBlackthornGym` (src/gymmick_init.c: (13,75) r0, (9,58) r1, (14,32) r0). Platforms 0 and
 * 2 are 5×7 tiles, platform 1 is 4×8 (the shape tables built by `ov04_02255140`, asm/overlay_04.s). The pose is the
 * pivot tile and a number of quarter turns; every offset below is for rotation 0 (the long side north-south, the
 * pivot near the north end) and turns clockwise with the platform.
 *
 * - The player stands on 18 tiles of a platform (3 wide, 6 long: `ov04_022550D4`, the walk check that lets the
 *   player on the magma there).
 * - Three trigger tiles (`ov04_02255708`, checked after each step by field_control.c): the pivot rotates the
 *   platform a quarter turn clockwise; the tile east of the pivot (after rotation) slides it its width forward, the
 *   tile west of it its width backward. The player rides along.
 * - A move happens only if every tile the platform sweeps is lava not covered by another platform (the ring around
 *   it and the corner sweep for a turn, the leading edge for each tile of a slide); otherwise nothing moves.
 *
 * The geometry was checked against the run's own solver, which crossed the gym live (NOTES "Ébènelle, solveur").
 */
class HgssBlackthornGym(override val poses: List<PlatformPose>, private val area: Area) : MovingPlatforms {

    /** One platform shape (offsets at rotation 0). */
    private class Shape(
        /** Footprint: x and y offset ranges. */
        val xs: IntRange,
        val ys: IntRange,
        /** Tiles slid per move (the platform's width). */
        val width: Int,
        val east: List<Pair<Int, Int>>,
        val west: List<Pair<Int, Int>>,
        val south: List<Pair<Int, Int>>,
        val north: List<Pair<Int, Int>>,
        /** Tiles swept by the corners during a quarter turn. */
        val sweep: List<Pair<Int, Int>>,
    ) {
        val footprint = xs.flatMap { x -> ys.map { y -> x to y } }
    }

    override fun walkTiles(poses: List<PlatformPose>): Set<Pair<Int, Int>> =
        poses.flatMap { place(it, WALK) }.toSet()

    override fun ride(poses: List<PlatformPose>, x: Int, y: Int): PlatformRide? {
        for ((i, pose) in poses.withIndex()) {
            val (pivot, forward, backward) = place(pose, TRIGGERS)
            val kind = when (x to y) {
                pivot -> Move.ROTATE
                forward -> Move.FORWARD
                backward -> Move.BACKWARD
                else -> continue
            }
            val moved = move(poses, i, kind) ?: return PlatformRide(poses, x, y, moved = false)
            val dx = moved[i].x - pose.x
            val dy = moved[i].y - pose.y
            return PlatformRide(moved, x + dx, y + dy, moved = true)
        }
        return null
    }

    /** What a trigger tile does. */
    enum class Move { ROTATE, FORWARD, BACKWARD }

    /** The trigger tiles of [pose]: pivot (rotate), forward, backward. */
    fun triggers(pose: PlatformPose): Triple<Pair<Int, Int>, Pair<Int, Int>, Pair<Int, Int>> =
        place(pose, TRIGGERS).let { Triple(it[0], it[1], it[2]) }

    /** The tiles of platform [index] at [pose] (its footprint over the lava). */
    fun footprint(index: Int, pose: PlatformPose): List<Pair<Int, Int>> = place(pose, SHAPES[TYPES[index]].footprint)

    /** The direction (dx, dy) the forward trigger slides the platform at [pose]. */
    fun forward(pose: PlatformPose): Pair<Int, Int> = rotate(pose.rotation, 1, 0)

    /** Tiles slid per move by platform [index]. */
    fun width(index: Int): Int = SHAPES[TYPES[index]].width

    /** The poses after triggering [kind] on platform [i], or null when it is blocked. */
    private fun move(poses: List<PlatformPose>, i: Int, kind: Move): List<PlatformPose>? {
        val pose = poses[i]
        val shape = SHAPES[TYPES[i]]
        val others = poses.indices.filter { it != i }.flatMap { footprint(it, poses[it]) }.toSet()
        fun clear(tiles: List<Pair<Int, Int>>) = tiles.all { (x, y) -> area.tile(x, y)?.kind == TileKind.Lava && (x to y) !in others }
        val next = when (kind) {
            Move.ROTATE -> {
                val ring = shape.south + shape.north + shape.west + shape.east.take(shape.width - 1)
                if (!clear(place(pose, ring)) || !clear(place(pose, shape.sweep))) return null
                pose.copy(rotation = (pose.rotation + 1) % 4)
            }
            Move.FORWARD, Move.BACKWARD -> {
                val (fx, fy) = forward(pose)
                val sign = if (kind == Move.FORWARD) 1 else -1
                val edge = if (kind == Move.FORWARD) shape.east else shape.west
                var at = pose
                repeat(shape.width) {
                    if (!clear(place(at, edge))) return null
                    at = at.copy(x = at.x + sign * fx, y = at.y + sign * fy)
                }
                at
            }
        }
        return poses.mapIndexed { j, p -> if (j == i) next else p }
    }

    private fun place(pose: PlatformPose, offsets: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
        offsets.map { (dx, dy) -> rotate(pose.rotation, dx, dy).let { (rx, ry) -> pose.x + rx to pose.y + ry } }

    companion object {
        /** `MAP_BLACKTHORN_GYM` (T30GYM0101). */
        const val MAP = 141

        /** `GYMMICK_BLACKTHORN` (include/gymmick.h). */
        const val GYMMICK_TYPE = 6

        /** Shape of each platform: 0 and 2 are 5×7, 1 is 4×8. */
        private val TYPES = listOf(0, 1, 0)

        /** The 18 tiles the player can stand on (3 × 6). */
        private val WALK = (-1..1).flatMap { x -> (-1..4).map { y -> x to y } }

        /** Pivot (rotate), forward and backward trigger tiles. */
        private val TRIGGERS = listOf(0 to 0, 1 to 0, -1 to 0)

        private fun column(x: Int, ys: IntRange) = ys.map { x to it }
        private fun row(xs: IntRange, y: Int) = xs.map { it to y }

        private val SHAPES = listOf(
            Shape(
                xs = -2..2, ys = -2..4, width = 5,
                east = column(3, -2..4), west = column(-3, -2..4), south = row(-2..2, 5), north = row(-2..2, -3),
                sweep = listOf(
                    -4 to 4, -4 to 3, -4 to 2, -4 to 1, -4 to 0, -4 to -1, -4 to -2,
                    -5 to 2, -5 to 1, -5 to 0, -5 to -1, -5 to -2, 3 to 2,
                ),
            ),
            Shape(
                xs = -2..1, ys = -2..5, width = 4,
                east = column(2, -2..5), west = column(-3, -2..5), south = row(-2..1, 6), north = row(-2..1, -3),
                sweep = listOf(
                    -4 to 5, -4 to 4, -4 to 3, -4 to 2, -4 to 1, -4 to 0, -4 to -1, -4 to -2,
                    -5 to 4, -5 to 3, -5 to 2, -5 to 1, -5 to 0, -5 to -1, -5 to -2,
                    -6 to 2, -6 to 1, -6 to 0, -6 to -1, -6 to -2, 3 to -1, 3 to 0, 3 to 1,
                ),
            ),
        )

        /** ([dx], [dy]) turned [rotation] quarter turns clockwise (x east, y south). */
        fun rotate(rotation: Int, dx: Int, dy: Int): Pair<Int, Int> {
            var x = dx
            var y = dy
            repeat(((rotation % 4) + 4) % 4) { val t = x; x = -y; y = t }
            return x to y
        }

        /**
         * The poses in the save's `Gymmick` slot ([gymmick]: u32 type, then the union), or null when it isn't the
         * Blackthorn Gym's.
         */
        fun poses(gymmick: ByteArray): List<PlatformPose>? {
            if (gymmick.size < 4 + 15) return null
            val type = (gymmick[0].toInt() and 0xFF) or ((gymmick[1].toInt() and 0xFF) shl 8)
            if (type != GYMMICK_TYPE) return null
            fun u16(o: Int) = (gymmick[o].toInt() and 0xFF) or ((gymmick[o + 1].toInt() and 0xFF) shl 8)
            return (0 until 3).map { PlatformPose(u16(4 + 2 * it), u16(10 + 2 * it), gymmick[16 + it].toInt() and 3) }
        }
    }
}
