package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ASCII areas: '.' floor, '#' wall. */
private fun puzzleArea(vararg rows: String): Area {
    val width = rows.maxOf { it.length }
    val tiles = Array<TileInfo?>(width * rows.size) { i ->
        when (rows[i / width].getOrElse(i % width) { ' ' }) {
            '.' -> TileInfo(false, TileKind.Floor, listOf(0))
            '#' -> TileInfo(true, TileKind.Wall)
            else -> null
        }
    }
    return Area(0, "puzzle", 0, 0, width, rows.size, tiles)
}

/** The live puzzle parts of [Overlay]: shutters ([Overlay.blockedTiles]) and teleports ([Edge.Teleport]). */
class PathfinderPuzzleTest {

    private fun Pathfinder.to(x: Int, y: Int, from: Node) = route(from) { it.x == x && it.y == y }

    @Test
    fun closedShuttersBlockTheirTiles() {
        val map = puzzleArea(
            ".#.",
            "...",
            ".#.",
        )
        assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(0, 0)))
        val shut = Pathfinder(map, Overlay(blockedTiles = setOf(1 to 1)))
        // The failure names the shutter in the way (go_to tells the agent to work the puzzle).
        assertEquals(RouteFailure.BlockedByBarrier(1, 1), assertIs<Pathfinder.Result.Failed>(shut.to(2, 0, Node(0, 0))).failure)
    }

    @Test
    fun aTeleportIsTakenBySteppingOnItsSourceAndLandsOnItsDestination() {
        // Two rooms with no walkable link: only the pad at (1,0) leads to (4,2).
        val map = puzzleArea(
            "...#...",
            "...#...",
            "...#...",
        )
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(5, 2, Node(0, 0)))
        val pad = Pathfinder(map, Overlay(activeTriggers = setOf(1 to 0), teleports = listOf(TeleportLink(1, 0, 4, 2))))
        val route = assertIs<Pathfinder.Result.Found>(pad.to(5, 2, Node(0, 0))).route
        val teleport = assertIs<Edge.Teleport>(route.edges.first())
        assertEquals(Node(1, 0), teleport.via)
        assertEquals(Node(4, 2), teleport.to)
        assertEquals(Direction.EAST, teleport.direction)
        assertEquals(listOf(Node(5, 2)), route.edges.drop(1).map { it.to })
    }

    @Test
    fun aTeleportSourceIsNeverWalkedThroughAndIsSkippedWhenAPersonStandsOnIt() {
        val map = puzzleArea(
            "...#...",
            "...#...",
        )
        val pad = TeleportLink(1, 0, 4, 0)
        // Going to (2,0) from (0,0): through the pad would teleport, so the route goes around it.
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(map, Overlay(activeTriggers = setOf(1 to 0), teleports = listOf(pad))).to(2, 0, Node(0, 0))).route
        assertTrue(around.edges.none { it is Edge.Teleport } && around.edges.none { it.to == Node(1, 0) })
        val occupied = Pathfinder(map, Overlay(objects = listOf(LiveObject(1, 0, null)), teleports = listOf(pad)))
        assertIs<Pathfinder.Result.Failed>(occupied.to(5, 0, Node(0, 0)))
    }

    @Test
    fun reachableFollowsTeleports() {
        val map = puzzleArea("..#..")
        val reach = Pathfinder(map, Overlay(teleports = listOf(TeleportLink(1, 0, 4, 0)))).reachable(Node(0, 0))
        assertTrue(Node(4, 0) in reach && Node(3, 0) in reach)
    }

    /**
     * The Violet Gym lift in small: a lower floor (rows 2..4, height 32) and an upper floor (row 0, height 496) never
     * joined by walking; the platform (row 1..3, x 1..3) is a moving floor at both heights, and its center (2,2) rides
     * the lift to the other floor ([TeleportLink.fromHeight] / [TeleportLink.toHeight]).
     */
    @Test
    fun aLiftOnItsCenterTileChangesFloorBothWays() {
        val low = TileInfo(false, TileKind.Floor, listOf(32))
        val high = TileInfo(false, TileKind.Floor, listOf(496))
        val width = 5
        val tiles = Array<TileInfo?>(width * 5) { i -> if (i / width == 0) high else low }
        val map = Area(0, "lift", 0, 0, width, 5, tiles)
        val platform = (1..3).flatMap { y -> (1..3).map { x -> (x to y) to listOf(32, 496) } }.toMap()
        val lift = listOf(TeleportLink(2, 2, 2, 2, fromHeight = 32, toHeight = 496), TeleportLink(2, 2, 2, 2, fromHeight = 496, toHeight = 32))
        // Without the lift, the upper floor is out of reach.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(2, 0, Node(2, 4)))
        val withLift = Pathfinder(map, Overlay(activeTriggers = setOf(2 to 2), teleports = lift, surfaces = platform))
        val up = assertIs<Pathfinder.Result.Found>(withLift.to(2, 0, Node(2, 4))).route
        val ride = assertIs<Edge.Teleport>(up.edges.single { it is Edge.Teleport })
        assertEquals(Node(2, 2, 0), ride.via)
        assertEquals(Node(2, 2, 1), ride.to, "lands on the platform's upper surface")
        assertEquals(Node(2, 0, 0), up.edges.last().to)
        // Down again from the upper floor: the same tile, the other way.
        val down = assertIs<Pathfinder.Result.Found>(withLift.to(2, 4, Node(2, 0))).route
        val back = assertIs<Edge.Teleport>(down.edges.single { it is Edge.Teleport })
        assertEquals(Node(2, 2, 1) to Node(2, 2, 0), back.via to back.to)
        // The live heights are the ones the walker reads its level from.
        assertEquals(1, withLift.levelAt(1, 1, 496))
    }
}
