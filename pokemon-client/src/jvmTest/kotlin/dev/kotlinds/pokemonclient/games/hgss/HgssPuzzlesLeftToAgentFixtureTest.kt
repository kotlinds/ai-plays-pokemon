package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.actions.PuzzleSolving
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.NeedsMechanism
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.PuzzleMechanism
import dev.kotlinds.pokemonclient.world.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Movement puzzles left to the agent ([dev.kotlinds.pokemonclient.actions.ActionSettings.solvePuzzles] off) on real
 * saves (bench, DeSmuME, 8-badge save; skipped without `POKEMON_ROM`): walking alone finds no way, and the mechanism to
 * operate is named. Live, `go_to` failed the same way with `PUZZLE_LEFT_TO_AGENT` on each (and succeeded with the
 * option on):
 * - `mp_violet_gym`: Violet Gym entrance (15,28), the lift down: Falkner is only reached with the lift (15,20);
 * - `mp_mahogany_ice`: Mahogany Gym room 1 entrance (4,19): the way to the next room needs the ice block at 4,12 pushed;
 * - `pz_blackthorn_entrance`: Blackthorn Gym entrance, the platforms at rest: Clair needs a platform moved.
 * And `push` (Ice Path B1F, `pz_icepath_b1f`): boulder person:2 at 18,12 is planned into its hole at 18,7 alone.
 */
class HgssPuzzlesLeftToAgentFixtureTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    private fun field(name: String) = assertNotNull(game.state(HgssFixtures.load(name)).field)

    /** The live overlay as walks build it (people, obstacles, ice blocks, holes, lifts), without the RAM's triggers. */
    private fun overlay(field: FieldState) = Overlay(
        objects = field.objects.map { o ->
            LiveObject(
                o.x, o.y, o.facing,
                clearedBy = if (o.obstacle == ObstacleKind.BOULDER) FieldMoveKind.STRENGTH else null,
                iceBlock = o.obstacle == ObstacleKind.ICE_BLOCK && o.facing == Direction.SOUTH,
                fallsInto = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == o.id && !it.fallen }?.let { it.hole.x to it.hole.y },
            )
        },
        teleports = MovePlans.puzzleTeleports(field.puzzle),
        surfaces = MovePlans.puzzleSurfaces(field.puzzle),
    )

    private fun start(field: FieldState, overlay: Overlay, area: dev.kotlinds.pokemonclient.world.Area) =
        Node(field.x, field.y, Pathfinder(area, overlay).levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))

    @Test
    fun `Violet Gym - Falkner is behind the lift`() {
        val field = field("mp_violet_gym")
        val area = assertNotNull(HgssWorldRom.require().areaOf(field.mapId))
        val solving = overlay(field)
        val start = start(field, solving, area)
        val isGoal = { n: Node -> n.x == 15 && n.y == 5 }
        assertIs<Pathfinder.Result.Found>(Pathfinder(area, solving).route(start, RouteOptions(), isGoal = isGoal))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, PuzzleSolving.walkOnly(solving, field)).route(start, RouteOptions(), isGoal = isGoal))
        val needs = assertNotNull(PuzzleSolving.diagnose(area, field, solving, start, RouteOptions(), emptySet(), isGoal))
        assertEquals(PuzzleMechanism.LIFT, needs.mechanism)
        assertEquals(15 to 20, needs.x to needs.y)
    }

    @Test
    fun `Mahogany Gym - the way on needs an ice block pushed`() {
        val field = field("mp_mahogany_ice")
        val area = assertNotNull(HgssWorldRom.require().areaOf(field.mapId))
        val solving = overlay(field)
        val start = start(field, solving, area)
        val stairs = setOf(3 to 2)
        val needs = assertNotNull(PuzzleSolving.diagnose(area, field, solving, start, RouteOptions(), stairs) { it.x == 3 && it.y == 2 })
        assertEquals(NeedsMechanism(PuzzleMechanism.ICE_BLOCK, 4, 12, Node(4, 16), Direction.NORTH, 4 to 9), needs.copy(from = needs.from?.copy(level = 0)))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, PuzzleSolving.walkOnly(solving, field)).route(start, RouteOptions(), stairs) { it.x == 3 && it.y == 2 })
    }

    @Test
    fun `Blackthorn Gym - Clair needs a platform moved`() {
        val reader = HgssReader(HgssFixtures.load("pz_blackthorn_entrance"), HgssVersion.HEARTGOLD_US)
        val area = HgssWorldRom.require().areaOf(HgssBlackthornGym.MAP)!!
        val puzzle = assertNotNull(HgssPuzzles.read(HgssBlackthornGym.MAP, HgssPuzzles.reads(reader), area))
        val field = field("pz_blackthorn_entrance").copy(puzzle = puzzle)
        val solving = overlay(field)
        val start = start(field, solving, area)
        val isGoal = { n: Node -> n.x == 12 && n.y == 4 }
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, PuzzleSolving.walkOnly(solving, field)).route(start, RouteOptions(), isGoal = isGoal))
        val needs = assertNotNull(PuzzleSolving.diagnose(area, field, solving, start, RouteOptions(), emptySet(), isGoal))
        assertEquals(PuzzleMechanism.MOVING_PLATFORM, needs.mechanism)
        // The platforms stay walkable where they are: the first one is reached on foot, its trigger isn't stepped on.
        assertTrue(PuzzleSolving.isMechanism(field, needs.x, needs.y))
    }

    @Test
    fun `Ice Path B1F - push plans one boulder into its own hole`() {
        val field = field("pz_icepath_b1f")
        val area = assertNotNull(HgssWorldRom.require().areaOf(field.mapId))
        val solving = overlay(field)
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))
        val route = assertNotNull(PushPlanner(area, solving).pushInto(start(field, solving, area), options, 18 to 12))
        val pushes = route.edges.filterIsInstance<PushEdge>()
        assertEquals(18 to 7, pushes.last().objectTo)
        // Only that boulder moves: each push starts where the previous one left it.
        pushes.zipWithNext().forEach { (a, b) -> assertEquals(a.objectTo, b.objectFrom) }
        assertEquals(18 to 12, pushes.first().objectFrom)
        // The player never follows a boulder by pushing: they stay on the tile they pushed from.
        route.edges.zipWithNext().filter { (_, next) -> next is PushEdge }.forEach { (before, push) -> assertEquals(before.to, push.to) }
    }
}
