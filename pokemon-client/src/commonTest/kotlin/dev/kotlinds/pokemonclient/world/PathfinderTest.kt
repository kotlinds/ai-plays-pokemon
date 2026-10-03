package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Areas drawn in ASCII: '.' floor, '#' wall, '"' tall grass, 'v' '^' '<' '>' ledges (jump in that direction),
 * '~' surfable water, 'W' warp, 'H' a second surface 40 units higher (a walkway), '/' stairs (height 20),
 * '*' ice, 'R' 'L' 'U' 'D' spinner arrows (push right, left, up, down), 'S' the spinner stop tile, '@' whirlpool,
 * '|' waterfall, 'C' a Rock Climb wall.
 */
private fun area(vararg rows: String): Area {
    val height = rows.size
    val width = rows.maxOf { it.length }
    val warps = mutableListOf<Warp>()
    val tiles = Array<TileInfo?>(width * height) { i ->
        val c = rows[i / width].getOrElse(i % width) { ' ' }
        val x = i % width
        val y = i / width
        when (c) {
            '.' -> TileInfo(false, TileKind.Floor, listOf(0))
            '#' -> TileInfo(true, TileKind.Wall)
            '"' -> TileInfo(false, TileKind.TallGrass, listOf(0))
            'v' -> TileInfo(false, TileKind.Ledge(Direction.SOUTH))
            '^' -> TileInfo(false, TileKind.Ledge(Direction.NORTH))
            '<' -> TileInfo(false, TileKind.Ledge(Direction.WEST))
            '>' -> TileInfo(false, TileKind.Ledge(Direction.EAST))
            '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
            'W' -> TileInfo(false, TileKind.Door, listOf(0)).also { warps += Warp(0, warps.size, x, y, 1, 0) }
            'H' -> TileInfo(false, TileKind.Floor, listOf(40))
            '/' -> TileInfo(false, TileKind.Floor, listOf(20))
            '*' -> TileInfo(false, TileKind.Ice, listOf(0))
            'R' -> TileInfo(false, TileKind.Spinner(Direction.EAST), listOf(0))
            'L' -> TileInfo(false, TileKind.Spinner(Direction.WEST), listOf(0))
            'U' -> TileInfo(false, TileKind.Spinner(Direction.NORTH), listOf(0))
            'D' -> TileInfo(false, TileKind.Spinner(Direction.SOUTH), listOf(0))
            'S' -> TileInfo(false, TileKind.SpinnerStop, listOf(0))
            '@' -> TileInfo(false, TileKind.Whirlpool)
            '|' -> TileInfo(false, TileKind.Waterfall)
            'C' -> TileInfo(true, TileKind.RockClimb)
            else -> null
        }
    }
    return Area(0, "test", 0, 0, width, height, tiles, warps = warps)
}

class PathfinderTest {

    private fun Pathfinder.to(x: Int, y: Int, from: Node, options: RouteOptions = RouteOptions(), goalTiles: Set<Pair<Int, Int>> = emptySet()) =
        route(from, options, goalTiles) { it.x == x && it.y == y }

    @Test
    fun findsTheShortestWalkAroundWalls() {
        val map = area(
            ".....",
            ".###.",
            ".....",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0)))
        assertEquals(4, found.route.edges.size)
    }

    @Test
    fun theFollowingPokemonIsNotAnObstacleButPeopleAre() {
        val map = area("...")
        val follower = Pathfinder(map, Overlay(listOf(LiveObject(1, 0, Direction.EAST, isFollower = true))))
        assertIs<Pathfinder.Result.Found>(follower.to(2, 0, Node(0, 0)))
        val person = Pathfinder(map, Overlay(listOf(LiveObject(1, 0, Direction.EAST))))
        assertIs<Pathfinder.Result.Failed>(person.to(2, 0, Node(0, 0)))
    }

    @Test
    fun ledgesAreJumpedOnlyInTheirDirectionAndOnlyWhenAccepted() {
        val map = area(
            "...",
            "vvv",
            "...",
        )
        val pathfinder = Pathfinder(map)
        // Down: only by jumping, refused unless one-way routes are accepted.
        assertEquals(RouteFailure.OnlyOneWay, assertIs<Pathfinder.Result.Failed>(pathfinder.to(1, 2, Node(1, 0))).failure)
        val jump = assertIs<Pathfinder.Result.Found>(pathfinder.to(1, 2, Node(1, 0), RouteOptions(acceptOneWay = true)))
        assertIs<Edge.Jump>(jump.route.edges.single())
        assertTrue(RouteWarning.OneWay in jump.route.warnings)
        // Up: never.
        assertIs<Pathfinder.Result.Failed>(pathfinder.to(1, 0, Node(1, 2), RouteOptions(acceptOneWay = true)))
    }

    @Test
    fun aRouteWithoutLedgesIsPreferredWhenItExists() {
        val map = area(
            "....",
            "vvv.",
            "....",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0)))
        assertTrue(found.route.edges.none { it is Edge.Jump })
    }

    @Test
    fun tallGrassIsAvoidedOnlyWhenAsked() {
        val map = area(
            "...",
            "\"#.",
            "...",
        )
        val direct = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0)))
        assertEquals(2, direct.route.edges.size)
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0), RouteOptions(avoidTallGrass = true)))
        assertEquals(6, around.route.edges.size)
        assertTrue(around.route.warnings.none { it is RouteWarning.CrossesTallGrass })
    }

    @Test
    fun waterNeedsSurf() {
        val map = area(".~.")
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(2, 0, Node(0, 0)))
        assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(0, 0), RouteOptions(canSurf = true)))
        assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(1, 0), RouteOptions(mode = MovementMode.SURF)))
    }

    @Test
    fun warpsAreOnlyEnteredWhenTheyAreTheDestination() {
        val map = area(
            ".W.",
            "...",
        )
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(0, 0)))
        assertTrue(around.route.edges.none { it.to.x == 1 && it.to.y == 0 })
        val into = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(1, 0, Node(0, 0), goalTiles = setOf(1 to 0)))
        assertEquals(1, into.route.edges.size)
    }

    @Test
    fun heightLevelsAreOnlyJoinedThroughStairs() {
        // Ground row, a walkway (H) 40 units up next to it; stairs (/) at 20 connect them on the right.
        val map = area(
            "HHH",
            "../",
        )
        val pathfinder = Pathfinder(map)
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.to(0, 0, Node(0, 1)))
        assertEquals(listOf(1 to 1, 2 to 1, 2 to 0, 1 to 0, 0 to 0), found.route.edges.map { it.to.x to it.to.y })
    }

    @Test
    fun refusedMovesAreRememberedAndAvoided() {
        val map = area(
            "..",
            "..",
        )
        val refused = Pathfinder(map, Overlay(refused = setOf(Node(0, 0) to Direction.EAST)))
        val found = assertIs<Pathfinder.Result.Found>(refused.to(1, 0, Node(0, 0)))
        assertEquals(3, found.route.edges.size)
    }

    @Test
    fun trainerSightIsAvoidedWhenAsked() {
        val map = area(
            "....",
            "....",
        )
        // A trainer at (3,0) looking west sees (2,0), (1,0), (0,0)... with range 2: (2,0) and (1,0).
        val overlay = Overlay(listOf(LiveObject(3, 0, Direction.WEST, sightRange = 2)))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map, overlay).to(2, 1, Node(0, 0), RouteOptions(avoidTrainers = true)))
        assertTrue(found.route.edges.none { it.to.y == 0 })
    }

    @Test
    fun levelAtPicksTheClosestSurface() {
        val map = Area(0, "bridge", 0, 0, 1, 1, arrayOf(TileInfo(false, TileKind.Floor, listOf(0, 64))))
        assertEquals(1, Pathfinder(map).levelAt(0, 0, 60))
        assertEquals(0, Pathfinder(map).levelAt(0, 0, 5))
    }

    @Test
    fun reachableStopsAtWallsAndLedgesAndHonoursTheRadius() {
        val map = area(
            "..#..",
            "vv#..",
            "..#..",
        )
        val reach = Pathfinder(map).reachable(Node(0, 0)).keys.map { it.x to it.y }.toSet()
        assertEquals(setOf(0 to 0, 1 to 0), reach)
        val jumping = Pathfinder(map).reachable(Node(0, 0), RouteOptions(acceptOneWay = true)).keys.map { it.x to it.y }.toSet()
        assertTrue(0 to 2 in jumping && 1 to 2 in jumping)
        assertEquals(setOf(3 to 0, 4 to 0), Pathfinder(map).reachable(Node(3, 0), maxCost = 1).keys.map { it.x to it.y }.toSet() - setOf(3 to 1))
    }

    // region Ice and spinners

    private fun Edge.tilePositions() = tiles.map { it.x to it.y }

    @Test
    fun iceSlidesToTheFirstTileThatIsNotIce() {
        val map = area(
            "#######",
            ".****..",
            "#######",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(5, 1, Node(0, 1)))
        val slide = assertIs<Edge.Slide>(found.route.edges.single())
        assertEquals(Direction.EAST, slide.direction)
        assertEquals(Node(5, 1), slide.to)
        assertEquals(listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1), slide.tilePositions())
        // The player can't stop in the middle of the ice.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 1, Node(0, 1)))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 1, Node(6, 1)))
    }

    @Test
    fun iceStopsBeforeAWallOrAPerson() {
        val map = area("..***#")
        val wall = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0)))
        assertEquals(Node(4, 0), assertIs<Edge.Slide>(wall.route.edges.last()).to)
        val person = Pathfinder(map, Overlay(listOf(LiveObject(4, 0, Direction.WEST))))
        val stopped = assertIs<Pathfinder.Result.Found>(person.to(3, 0, Node(0, 0)))
        assertEquals(listOf(2 to 0, 3 to 0), stopped.route.edges.last().tilePositions())
    }

    @Test
    fun iceRoutesUseRocksToStop() {
        // The goal (3,3) is in the middle of the ice: only reachable by sliding south from (3,0)... stopping on the
        // rock at (3,4). From the start, sliding east along row 1 goes to the wall: the route goes around.
        val map = area(
            "......",
            "#****#",
            "#****#",
            "#****#",
            "###.##",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 3, Node(0, 0)))
        val last = assertIs<Edge.Slide>(found.route.edges.last())
        assertEquals(Direction.SOUTH, last.direction)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), last.tilePositions())
        // Down the column with the exit, the slide goes through to the floor below.
        val through = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 4, Node(0, 0)))
        assertEquals(Node(3, 4), assertIs<Edge.Slide>(through.route.edges.last()).to)
    }

    @Test
    fun aSlideIntoAWarpIsOnlyPlannedWhenTheWarpIsTheDestination() {
        val map = area(".***W")
        // Sliding east would end in the warp: not a way to (3,0)...
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 0, Node(0, 0)))
        // ...but the way to the warp itself.
        val into = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 0, Node(0, 0), goalTiles = setOf(4 to 0)))
        assertEquals(Node(4, 0), assertIs<Edge.Slide>(into.route.edges.single()).to)
    }

    @Test
    fun spinnersPushUntilTheStopTileTurningOnArrows() {
        val map = area(
            "....",
            "R..D",
            "...S",
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(3, 2, Node(0, 0)))
        val slide = assertIs<Edge.Slide>(found.route.edges.single())
        assertEquals(Direction.SOUTH, slide.direction)
        assertEquals(listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 3 to 2), slide.tilePositions())
    }

    @Test
    fun spinnersStopBeforeObstaclesAndNeverLoop() {
        val blocked = area(".R..#")
        val push = assertIs<Edge.Slide>(Pathfinder(blocked).neighbours(Node(0, 0), RouteOptions()).single())
        assertEquals(Node(3, 0), push.to)
        // R pushes east, L pushes back west: a loop forever is never planned.
        val loop = area(
            ".##",
            "R.L",
        )
        assertTrue(Pathfinder(loop).neighbours(Node(0, 0), RouteOptions()).isEmpty())
    }

    // endregion

    // region Field moves

    private fun Pathfinder.Result.needs(): RouteFailure.NeedsFieldMove =
        assertIs<RouteFailure.NeedsFieldMove>(assertIs<Pathfinder.Result.Failed>(this).failure)

    @Test
    fun aFailedRouteNamesTheFieldMoveThatOpensTheWay() {
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.SURF, 2, 0), Pathfinder(area("..~~..")).to(5, 0, Node(0, 0)).needs())
        assertEquals(FieldMoveKind.WHIRLPOOL, Pathfinder(area("~@~")).to(2, 0, Node(0, 0), RouteOptions(mode = MovementMode.SURF)).needs().move)
        assertEquals(FieldMoveKind.WATERFALL, Pathfinder(area("~|~")).to(2, 0, Node(0, 0), RouteOptions(mode = MovementMode.SURF)).needs().move)
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.ROCK_CLIMB, 1, 1), Pathfinder(area("##", ".C.")).to(2, 1, Node(0, 1)).needs())
        // Nothing opens a wall.
        assertEquals(RouteFailure.Unreachable, assertIs<Pathfinder.Result.Failed>(Pathfinder(area(".#.")).to(2, 0, Node(0, 0))).failure)
    }

    @Test
    fun aBoulderBlocksTheRouteAndTheFailureSaysStrength() {
        val map = area(
            "#####",
            ".....",
            "#####",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.STRENGTH, 2, 1), Pathfinder(map, boulder).to(4, 1, Node(0, 1)).needs())
        // The fewest field moves: a way around a boulder is taken, and water is preferred to two obstacles.
        val open = area(
            ".....",
            ".....",
        )
        assertIs<Pathfinder.Result.Found>(Pathfinder(open, boulder).to(4, 1, Node(0, 1)))
        // A person is not an obstacle a field move clears.
        val person = Overlay(listOf(LiveObject(2, 1, Direction.WEST)))
        assertEquals(RouteFailure.Unreachable, assertIs<Pathfinder.Result.Failed>(Pathfinder(map, person).to(4, 1, Node(0, 1))).failure)
    }

    // endregion
}
