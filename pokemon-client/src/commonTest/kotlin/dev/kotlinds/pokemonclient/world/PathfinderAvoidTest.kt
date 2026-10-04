package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Strict avoidance (NOTES-run P11: avoid_tall_grass crossed grass) and the failures go_to explains: a closed shutter,
 * a target on another height level. Areas in ASCII: '.' floor, '#' wall, '"' tall grass, 'H' floor 40 units higher.
 */
class PathfinderAvoidTest {

    private fun area(vararg rows: String): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { ' ' }) {
                '.', 'T' -> TileInfo(false, TileKind.Floor, listOf(0))
                '"' -> TileInfo(false, TileKind.TallGrass, listOf(0))
                'H' -> TileInfo(false, TileKind.Floor, listOf(40))
                '#' -> TileInfo(true, TileKind.Wall)
                else -> null
            }
        }
        return Area(0, "test", 0, 0, width, rows.size, tiles)
    }

    private fun Pathfinder.to(x: Int, y: Int, from: Node, options: RouteOptions = RouteOptions()) = route(from, options) { it.x == x && it.y == y }

    @Test
    fun aGrassFreeWayIsTakenEvenWhenMuchLonger() {
        // One grass tile straight ahead, or a detour of 60 tiles around the wall.
        val row = ".".repeat(31)
        val map = area(
            row,
            "\"" + "#".repeat(29) + ".",
            "." + "#".repeat(29) + ".",
            row,
        )
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0), RouteOptions(avoidTallGrass = true)))
        assertTrue(found.route.warnings.none { it is RouteWarning.CrossesTallGrass }, found.route.warnings.toString())
        assertTrue(found.route.edges.size > 60)
        // Without the option, the short way.
        assertEquals(2, assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0))).route.edges.size)
    }

    @Test
    fun whenGrassCantBeAvoidedTheLeastIsCrossed() {
        val map = area(
            ".\"\"\".",
            ".\"#\".",
            ".\".\".",
        )
        // (2,2) is closed in by grass on both sides and a wall above: one grass tile at least.
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 2, Node(0, 0), RouteOptions(avoidTallGrass = true)))
        assertEquals(RouteWarning.CrossesTallGrass(1), found.route.warnings.filterIsInstance<RouteWarning.CrossesTallGrass>().single())
    }

    @Test
    fun aTrainersSightOutweighsTallGrass() {
        // The trainer at (3,0) looks west over the top row; the bottom row is grass.
        val map = area(
            "...T",
            ".#..",
            "\"\"\".",
        )
        val trainer = LiveObject(3, 0, Direction.WEST, sightRange = 3)
        val found = assertIs<Pathfinder.Result.Found>(
            Pathfinder(map, Overlay(listOf(trainer))).to(3, 2, Node(0, 0), RouteOptions(avoidTrainers = true, avoidTallGrass = true)),
        )
        assertTrue(RouteWarning.PassesTrainerSight !in found.route.warnings)
        assertTrue(found.route.warnings.any { it is RouteWarning.CrossesTallGrass })
    }

    @Test
    fun aClosedShutterIsNamedAsTheCause() {
        // The only way east goes through (4,1), closed by a shutter.
        val map = area("....#.", "......", "....#.")
        val failed = assertIs<Pathfinder.Result.Failed>(Pathfinder(map, Overlay(blockedTiles = setOf(4 to 1))).to(5, 1, Node(0, 1)))
        assertEquals(RouteFailure.BlockedByBarrier(4, 1), failed.failure)
    }

    @Test
    fun aTargetOnAnotherHeightLevelIsSaidSo() {
        // A walkway 40 units up, reached by nothing from here.
        val map = area("..HH", "..HH")
        val failed = assertIs<Pathfinder.Result.Failed>(Pathfinder(map).to(3, 0, Node(0, 0)))
        assertEquals(RouteFailure.DifferentLevel, failed.failure)
    }
}
