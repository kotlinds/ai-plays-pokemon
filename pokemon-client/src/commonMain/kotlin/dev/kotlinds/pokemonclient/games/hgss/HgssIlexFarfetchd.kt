package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.HerdOutcome
import dev.kotlinds.pokemonclient.state.HerdStep
import dev.kotlinds.pokemonclient.state.PuzzleHerd
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.PuzzleTwig
import dev.kotlinds.pokemonclient.world.Area

/**
 * The two lost Farfetch'd of Ilex Forest (`MAP_ILEX_FOREST`, D36R0101), with the rules of
 * files/fielddata/script/scr_seq/scr_seq_0092_D36R0101.s.
 *
 * Each bird stands on one of four spots (the corners of a rectangle: it runs 7 or 8 tiles east-west, 10 north-south).
 * Talking to it (A) makes it run to another spot depending on the side the player talks from (the player's facing);
 * the twigs (coordinate triggers active while their var is `STICKS_ACTIVE`) make it turn, and some set its "blind
 * spot" (`VAR_TEMP_x4002` / `x4003`). It is caught when talked to from one side on one spot while its blind spot is
 * set: bird 1 (object 0) on its bottom-left spot (25,62) from the north, bird 2 (object 2) on its top-right spot
 * (49,54) from the west. Twigs and blind spots are reset on entering the forest (scr_seq_D36R0101_000).
 *
 * [herds] gives each bird's state and the shortest plan found by a search over these rules (talk tiles that are
 * walls in the ROM are left out).
 */
object HgssIlexFarfetchd {

    /** `MAP_ILEX_FOREST`. */
    const val MAP = 117

    /** `STICKS_ACTIVE` (event_D36R0101.h). */
    private const val STICKS_ACTIVE = 1

    /** The four spots of a bird, in the script's order. */
    enum class Spot { TOP_LEFT, BOTTOM_LEFT, TOP_RIGHT, BOTTOM_RIGHT }

    /** A twig: its coordinate event index, its var and its 2×2 tiles. */
    private data class Twig(val trigger: Int, val variable: Int, val x: Int, val y: Int) {
        val tiles = listOf(PuzzleTile(x, y), PuzzleTile(x + 1, y), PuzzleTile(x, y + 1), PuzzleTile(x + 1, y + 1))
    }

    /** One bird: its object, spots (top-left x/y, bottom-right x/y), twigs, blind-spot var. */
    private class Bird(val objectId: Int, val left: Int, val right: Int, val top: Int, val bottom: Int, val twigs: List<Twig>, val blindVar: Int) {
        fun tile(spot: Spot) = when (spot) {
            Spot.TOP_LEFT -> PuzzleTile(left, top)
            Spot.BOTTOM_LEFT -> PuzzleTile(left, bottom)
            Spot.TOP_RIGHT -> PuzzleTile(right, top)
            Spot.BOTTOM_RIGHT -> PuzzleTile(right, bottom)
        }

        fun spotAt(x: Int, y: Int): Spot? = Spot.entries.firstOrNull { tile(it) == PuzzleTile(x, y) }
    }

    /** `FARFETCHD1_BOTTOM_RIGHT_FACING_UP` (`VAR_TEMP_x4004`). */
    private const val BIRD1_FACING_UP = 0x4004

    private val BIRD1 = Bird(
        0, left = 25, right = 32, top = 52, bottom = 62,
        // sticks1, sticks2 (coordinate events 0 and 1).
        twigs = listOf(Twig(0, 0x4099, 25, 66), Twig(1, 0x409B, 28, 63)),
        blindVar = 0x4002,
    )

    private val BIRD2 = Bird(
        2, left = 41, right = 49, top = 54, bottom = 64,
        // sticks1..4 (coordinate events 3, 4, 2, 5).
        twigs = listOf(Twig(3, 0x409A, 50, 49), Twig(4, 0x409C, 52, 53), Twig(2, 0x409D, 46, 64), Twig(5, 0x409E, 45, 53)),
        blindVar = 0x4003,
    )

    /** The state of one bird's puzzle: spot, active twigs (bit i = twig i), blind spot, and bird 1's "facing up". */
    data class State(val spot: Spot, val twigs: Int, val blind: Boolean, val facingUp: Boolean = false)

    /** What an action does: the next state, or [caught]. [to] is where the bird runs (null when it stays). */
    data class Result(val next: State, val caught: Boolean = false, val to: Spot? = null)

    /** Talking to bird [bird] (1 or 2) facing [facing]. */
    fun talk(bird: Int, state: State, facing: Direction): Result = if (bird == 1) talk1(state, facing) else talk2(state, facing)

    /** Stepping on twig [twig] (index in the bird's twig list) of bird [bird]; null when it isn't active. */
    fun step(bird: Int, state: State, twig: Int): Result? {
        if (state.twigs shr twig and 1 == 0) return null
        return Result(if (bird == 1) step1(state, twig) else step2(state, twig))
    }

    private fun bits(vararg on: Int) = on.fold(0) { acc, i -> acc or (1 shl i) }

    private fun talk1(s: State, f: Direction): Result = when (s.spot) {
        Spot.TOP_LEFT -> when (f) {
            Direction.NORTH -> run(s.copy(blind = false), Spot.TOP_RIGHT)
            Direction.WEST -> run(s.copy(blind = false, twigs = bits(0, 1)), Spot.BOTTOM_LEFT)
            else -> Result(s.copy(blind = false))
        }
        Spot.BOTTOM_LEFT -> when {
            f == Direction.SOUTH && s.blind -> Result(s.copy(twigs = 0), caught = true)
            f == Direction.SOUTH -> run(s.copy(twigs = 0), Spot.BOTTOM_RIGHT)
            else -> run(s.copy(twigs = 0), Spot.TOP_LEFT)
        }
        Spot.TOP_RIGHT -> when (f) {
            Direction.EAST -> run(s.copy(blind = false, twigs = bits(0, 1), facingUp = true), Spot.BOTTOM_RIGHT)
            Direction.NORTH -> run(s.copy(blind = false, twigs = 0), Spot.TOP_LEFT)
            else -> Result(s.copy(blind = false))
        }
        Spot.BOTTOM_RIGHT -> when (f) {
            Direction.EAST -> run(s.copy(blind = false, facingUp = false, twigs = 0), Spot.TOP_RIGHT)
            Direction.SOUTH -> run(s.copy(blind = false, facingUp = false, twigs = bits(0)), Spot.BOTTOM_LEFT)
            else -> Result(s.copy(blind = false, facingUp = false))
        }
    }

    private fun step1(s: State, twig: Int): State = when {
        s.facingUp -> s.copy(twigs = 0)
        twig == 0 -> s.copy(blind = true, twigs = bits(1))
        else -> s.copy(blind = false, twigs = bits(0))
    }

    private fun talk2(s: State, f: Direction): Result = when (s.spot) {
        Spot.TOP_LEFT -> when (f) {
            Direction.NORTH -> run(s.copy(blind = false, twigs = bits(0, 1)), Spot.TOP_RIGHT)
            Direction.WEST -> run(s.copy(blind = false, twigs = bits(2)), Spot.BOTTOM_LEFT)
            else -> Result(s.copy(blind = false))
        }
        Spot.BOTTOM_LEFT -> when (f) {
            Direction.SOUTH -> run(s.copy(blind = false, twigs = 0), Spot.BOTTOM_RIGHT)
            Direction.WEST -> run(s.copy(blind = false, twigs = bits(3)), Spot.TOP_LEFT)
            else -> Result(s.copy(blind = false))
        }
        Spot.TOP_RIGHT -> when {
            f == Direction.EAST && s.blind -> Result(s.copy(twigs = 0), caught = true)
            f == Direction.EAST -> run(s.copy(twigs = bits(2)), Spot.BOTTOM_RIGHT)
            else -> run(s.copy(twigs = 0), Spot.TOP_LEFT)
        }
        Spot.BOTTOM_RIGHT -> when (f) {
            Direction.EAST -> run(s.copy(blind = false, twigs = bits(0, 1, 3)), Spot.TOP_RIGHT)
            Direction.SOUTH -> run(s.copy(blind = false, twigs = 0), Spot.BOTTOM_LEFT)
            else -> Result(s.copy(blind = false))
        }
    }

    private fun step2(s: State, twig: Int): State {
        val blind = twig == 1
        val t = s.twigs
        val twigs = when (twig) {
            0 -> if (s.spot == Spot.TOP_RIGHT) bits(1, 3) else t
            1 -> if (s.spot == Spot.TOP_RIGHT) bits(0, 3) else t
            2 -> if (s.spot == Spot.BOTTOM_LEFT || s.spot == Spot.BOTTOM_RIGHT) t and bits(2).inv() else t
            else -> when (s.spot) {
                Spot.TOP_LEFT -> t and bits(3).inv()
                Spot.TOP_RIGHT -> (t or bits(0, 1)) and bits(3).inv()
                else -> t
            }
        }
        return s.copy(blind = blind, twigs = twigs)
    }

    private fun run(next: State, to: Spot) = Result(next.copy(spot = to), to = to)

    /**
     * The shortest plan from [state] to catching bird [bird] (1 or 2): talk from any side whose tile isn't a wall
     * ([walkable]), step on active twigs. Empty when none is found within a few steps.
     */
    fun plan(bird: Int, state: State, walkable: (Int, Int) -> Boolean = { _, _ -> true }): List<HerdStep> {
        val b = if (bird == 1) BIRD1 else BIRD2
        val previous = HashMap<State, Pair<State, HerdStep>>()
        val queue = ArrayDeque(listOf(state))
        val seen = hashSetOf(state)
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            for (f in Direction.entries) {
                val at = b.tile(s.spot)
                val from = PuzzleTile(at.x - f.dx, at.y - f.dy)
                if (!walkable(from.x, from.y)) continue
                val r = talk(bird, s, f)
                if (r.caught) return path(previous, state, s) + HerdStep.TalkFrom(from, f, HerdOutcome.Caught)
                if (r.to == null || !seen.add(r.next)) continue
                previous[r.next] = s to HerdStep.TalkFrom(from, f, HerdOutcome.Flees(b.tile(r.to)))
                queue.add(r.next)
            }
            for ((i, twig) in b.twigs.withIndex()) {
                val r = step(bird, s, i) ?: continue
                if (!seen.add(r.next)) continue
                val tile = twig.tiles.firstOrNull { walkable(it.x, it.y) } ?: continue
                previous[r.next] = s to HerdStep.StepOnTwig("trigger:${twig.trigger}", tile)
                queue.add(r.next)
            }
        }
        return emptyList()
    }

    private fun path(previous: Map<State, Pair<State, HerdStep>>, start: State, end: State): List<HerdStep> {
        val steps = ArrayDeque<HerdStep>()
        var at = end
        while (at != start) {
            val (from, step) = previous.getValue(at)
            steps.addFirst(step)
            at = from
        }
        return steps.toList()
    }

    /** One map object's position (local id, x, y). */
    data class ObjectAt(val id: Int, val x: Int, val y: Int, val facing: Direction? = null)

    /** The birds on the map now ([objects]: the map's live objects), with their twigs and plan. */
    fun herds(reads: HgssPuzzles.Reads, objects: List<ObjectAt>, area: Area?): List<PuzzleHerd> =
        listOf(1 to BIRD1, 2 to BIRD2).mapNotNull { (n, b) ->
            val o = objects.firstOrNull { it.id == b.objectId } ?: return@mapNotNull null
            val active = b.twigs.mapIndexed { i, t -> if (reads.variable(t.variable) == STICKS_ACTIVE) 1 shl i else 0 }.sum()
            val blind = (reads.variable(b.blindVar) ?: 0) != 0
            val facingUp = n == 1 && (reads.variable(BIRD1_FACING_UP) ?: 0) != 0
            val spot = b.spotAt(o.x, o.y)
            val walkable: (Int, Int) -> Boolean = { x, y -> area?.tile(x, y)?.let { !it.blocked } ?: true }
            PuzzleHerd(
                id = "person:${b.objectId}",
                at = PuzzleTile(o.x, o.y),
                blindSpot = blind,
                twigs = b.twigs.mapIndexed { i, t -> PuzzleTwig("trigger:${t.trigger}", t.tiles, active shr i and 1 == 1) },
                plan = spot?.let { plan(n, State(it, active, blind, facingUp), walkable) }.orEmpty(),
                facing = o.facing,
            )
        }

    const val RULE =
        "A lost Farfetch'd runs away when you talk to it (A) from the wrong side, to another corner of its area, and " +
            "turns towards the noise when you step on an active twig. Talked to from behind while it looks away " +
            "(blind spot), it is caught. Follow `plan`: go_to each tile (avoid the other twigs on the way), face the " +
            "given direction and press A, or just walk onto the twig tile."
}
