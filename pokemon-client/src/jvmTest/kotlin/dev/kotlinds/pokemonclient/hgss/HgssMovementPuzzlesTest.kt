package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reference routes of the movement puzzles on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`, see
 * [HgssWorldRom]): ice and spinner slides computed from the ROM's tiles. The Ice Path route was walked live (bench,
 * DeSmuME, 8-badge save): every slide landed on the tile predicted here.
 *
 * Zone ids (include/constants/maps.h): Ice Path 1F = 120, Ice Path B1F = 237, Viridian Gym = 496.
 */
class HgssMovementPuzzlesTest {

    private val world get() = HgssWorldRom.require()

    private fun Pathfinder.Result.landings() =
        assertIs<Pathfinder.Result.Found>(this).route.edges.filterIsInstance<Edge.Slide>().map { it.direction.name.first() to (it.to.x to it.to.y) }

    @Test
    fun `Ice Path 1F is crossed from the Blackthorn entrance to the stairs down by sliding`() {
        val area = world.areaOf(120)!!
        // From the Blackthorn side (warp 1 at 55,40) to the stairs to B1F (warp 3 at 55,20).
        val route = Pathfinder(area).route(Node(55, 40), RouteOptions(), goalTiles = setOf(55 to 20)) { it.x == 55 && it.y == 20 }
        assertEquals(
            listOf(
                'N' to (55 to 25), 'W' to (51 to 25), 'S' to (51 to 28), 'W' to (48 to 28), 'S' to (48 to 32),
                'E' to (56 to 32), 'N' to (56 to 29), 'W' to (52 to 29), 'N' to (52 to 24),
            ),
            route.landings(),
        )
    }

    @Test
    fun `the Viridian Gym spinners lead from the entrance to the leader`() {
        val area = world.areaOf(496)!!
        val people = Overlay(area.people.map { LiveObject(it.x, it.y, it.facing) })
        // Entrance (warp 0 at 5,47) to the tile in front of Blue (5,4).
        val route = Pathfinder(area, people).route(Node(5, 46), RouteOptions()) { it.x == 5 && it.y == 5 }
        val landings = route.landings()
        assertTrue(landings.size >= 4, "$landings")
        // The last push: up the east column, then west along row 9 to its stop tile (5,9).
        assertEquals('E' to (5 to 9), landings.last())
    }

    @Test
    fun `an Ice Path boulder is reported as needing Strength`() {
        val area = world.areaOf(237)!!
        val boulder = Overlay(listOf(LiveObject(18, 12, null, clearedBy = FieldMoveKind.STRENGTH)))
        val failed = assertIs<Pathfinder.Result.Failed>(Pathfinder(area, boulder).route(Node(23, 6), RouteOptions()) { it.x == 18 && it.y == 12 })
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.STRENGTH, 18, 12), failed.failure)
    }
}
