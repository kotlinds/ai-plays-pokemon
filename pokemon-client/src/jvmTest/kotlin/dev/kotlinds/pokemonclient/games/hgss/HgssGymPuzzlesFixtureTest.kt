package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [HgssGymPuzzles] on real HeartGold (USA) RAM snapshots captured with the bench (8-badge save, gyms entered again):
 * - `gym_violet_down`: Violet Gym at the entrance (15,28), lift down;
 * - `gym_violet_up`: the same after `go_to person:0` (Falkner) rode the lift up, at (16,4);
 * - `gym_ecruteak_morty`: Ecruteak Gym next to Morty (10,9) after `go_to person:1` from the entrance;
 * - `gym_cianwood_waterfall` / `gym_cianwood_winch`: Cianwood Gym at the entrance, then after turning the winch (10,3);
 * - `gym_blackthorn_unmodeled`: Blackthorn Gym at the entrance (13,87).
 * The routes are computed on the ROM's maps (skipped without `POKEMON_ROM`, [HgssWorldRom]).
 */
class HgssGymPuzzlesFixtureTest {

    private fun field(name: String): FieldState = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    private fun reader(name: String) = HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US)

    /** The live overlay the walker builds ([MovePlans.overlay]): armed triggers, people, the puzzle's teleports and floors. */
    private fun overlay(area: Area, field: FieldState, puzzle: PuzzleState?, reader: HgssReader) = Overlay(
        objects = field.objects.map { dev.kotlinds.pokemonclient.world.LiveObject(it.x, it.y, it.facing) },
        activeTriggers = area.triggers.filter { it.zone == field.mapId && reader.variable(it.variable) == it.value }
            .flatMap { t -> (t.x until t.x + maxOf(1, t.width)).flatMap { x -> (t.y until t.y + maxOf(1, t.height)).map { y -> x to y } } }.toSet(),
        teleports = MovePlans.puzzleTeleports(puzzle),
        surfaces = MovePlans.puzzleSurfaces(puzzle),
    )

    private fun route(name: String, map: Int, toX: Int, toY: Int): Route {
        val reader = reader(name)
        val area = assertNotNull(HgssWorldRom.require().areaOf(map))
        val field = field(name)
        val puzzle = HgssPuzzles.read(map, HgssPuzzles.reads(reader), area)
        val pathfinder = Pathfinder(area, overlay(area, field, puzzle, reader))
        val start = Node(field.x, field.y, pathfinder.levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
        return assertIs<Pathfinder.Result.Found>(pathfinder.route(start) { it.x == toX && it.y == toY }, "$name to $toX,$toY").route
    }

    @Test
    fun theVioletGymLiftIsReadFromTheGymmick() {
        val down = field("gym_violet_down")
        assertEquals(HgssGymPuzzles.VIOLET_GYM to (15 to 28), down.mapId to (down.x to down.y))
        val puzzle = assertNotNull(down.puzzle)
        assertEquals(PuzzleKind.LIFT, puzzle.kind)
        assertFalse(puzzle.indicators.single { it.id == "lift:0" }.on)
        val up = field("gym_violet_up")
        assertEquals(16 to 4, up.x to up.y)
        assertEquals(HgssGymPuzzles.LIFT_UP, up.height, "the upper floor is at the lift's top height")
        assertTrue(assertNotNull(up.puzzle).indicators.single { it.id == "lift:0" }.on)
        // The lift's trigger is a mechanism, not a story scene.
        val blockers = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("gym_violet_down")).story?.blockers.orEmpty()
        assertTrue(blockers.none { it.target == "trigger:0" }, blockers.toString())
    }

    @Test
    fun theRouteToFalknerRidesTheLiftUpAndTheWayOutRidesItDown() {
        // From the entrance to the tile south of Falkner (15,4): through the lift, landing on its upper surface.
        val upRoute = route("gym_violet_down", HgssGymPuzzles.VIOLET_GYM, 15, 5)
        val ride = assertIs<Edge.Teleport>(upRoute.edges.single { it is Edge.Teleport })
        assertEquals(15 to 20, ride.via.x to ride.via.y)
        assertTrue(ride.to.level > ride.via.level, "rides up")
        // Back from Falkner to the tile before the exit: the lift down.
        val downRoute = route("gym_violet_up", HgssGymPuzzles.VIOLET_GYM, 15, 27)
        val back = assertIs<Edge.Teleport>(downRoute.edges.single { it is Edge.Teleport })
        assertTrue(back.to.level < back.via.level, "rides down")
    }

    @Test
    fun theEcruteakGymIsAHiddenFloorAndTheRouteToMortyAvoidsEveryPit() {
        val reader = reader("gym_ecruteak_morty")
        val area = HgssWorldRom.require().areaOf(HgssGymPuzzles.ECRUTEAK_GYM)
        val puzzle = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.ECRUTEAK_GYM, HgssPuzzles.reads(reader), area))
        assertEquals(PuzzleKind.HIDDEN_FLOOR, puzzle.kind)
        assertEquals(15, puzzle.teleports.size, "the 15 pits")
        // Candles are lit again on each entry (InitEcruteakGym), whatever the Mediums.
        assertEquals(listOf("candle:0", "candle:1", "candle:2", "candle:3"), puzzle.indicators.map { it.id })
        assertTrue(puzzle.indicators.all { it.on })
        // The live route the walker took: entrance (16,53) to the tile south of Morty (10,8), never on a pit.
        val field = field("gym_ecruteak_morty").copy(x = 16, y = 53)
        val pathfinder = Pathfinder(area!!, overlay(area, field, puzzle, reader))
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.route(Node(16, 53)) { it.x == 10 && it.y == 9 }).route
        val pits = puzzle.teleports.flatMap { it.from }.map { it.x to it.y }.toSet()
        assertTrue(found.edges.none { it is Edge.Teleport }, "never falls")
        assertTrue(found.edges.flatMap { it.tiles }.none { (it.x to it.y) in pits }, "never steps on a pit")
    }

    @Test
    fun theCianwoodWinchIsReadAndLiftsChucksBlocker() {
        val game = HgssGame(HgssVersion.HEARTGOLD_US)
        val flowing = game.state(HgssFixtures.load("gym_cianwood_waterfall"))
        val puzzle = assertNotNull(flowing.field?.puzzle)
        assertEquals(PuzzleKind.WATERFALL_WINCH, puzzle.kind)
        assertFalse(puzzle.switches.single().used)
        assertTrue(puzzle.indicators.single().on)
        assertEquals(listOf("person:0"), flowing.story?.blockers.orEmpty().map { it.target })
        assertTrue(flowing.story!!.blockers.single().reason.startsWith("Chuck under the waterfall: turn the winch"))
        val turned = game.state(HgssFixtures.load("gym_cianwood_winch"))
        val after = assertNotNull(turned.field?.puzzle)
        assertTrue(after.switches.single().used)
        assertFalse(after.indicators.single().on)
        assertTrue(turned.story?.blockers.orEmpty().isEmpty())
    }

    @Test
    fun theBlackthornPlatformsAreReportedAsUnmodeled() {
        val puzzle = assertNotNull(field("gym_blackthorn_unmodeled").puzzle)
        assertTrue("platforms" in assertNotNull(puzzle.unmodeled))
        val hint = with(MovePlans) { dev.kotlinds.pokemonclient.world.RouteFailure.Unreachable.hint(field("gym_blackthorn_unmodeled")) }!!
        assertTrue("doesn't model" in hint, hint)
    }
}
