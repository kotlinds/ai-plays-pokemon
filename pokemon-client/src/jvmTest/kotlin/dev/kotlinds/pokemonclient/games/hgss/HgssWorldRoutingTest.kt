package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The world knowledge `go_to` relies on, on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`): every
 * tile behavior decoded, bridges, map edges, holes, hidden items and routes across floors. Expected values come
 * from the decomp (zone_event JSON files, scr_seq_D43R0103 / D39R0102 hole scripts) and the agent's notes ("19.
 * Nouveau MCP (v2)": Route 26/27, Victory Road).
 *
 * Zone ids: Route 26 = 30, Route 27 = 31, Indigo Plateau = 58, Victory Road 1F/2F/3F = 124/178/179, Ice Path
 * B1F/B2F = 237/238, Bell Tower 1F = 111, roof = 340.
 */
class HgssWorldRoutingTest {

    private val world get() = HgssWorldRom.require()

    @Test
    fun `every tile behavior of the ROM has a kind`() {
        val w = world
        val unknown = (0 until w.zoneCount).flatMap { zone ->
            val area = w.areaOf(zone) ?: return@flatMap emptyList()
            val bounds = area.zoneBounds[zone] ?: return@flatMap emptyList()
            (bounds[1]..bounds[3]).flatMap { y -> (bounds[0]..bounds[2]).mapNotNull { x -> (area.tile(x, y)?.kind as? TileKind.Unknown)?.behavior?.takeIf { area.zoneAt(x, y) == zone } } }
        }.toSet()
        assertEquals(emptySet(), unknown)
    }

    @Test
    fun `the Route 26 bridges are walked without Surf`() {
        val area = assertNotNull(world.areaOf(30))
        // Notes: wooden bridges 898→912 at y 404, and 914 from y 399 to 388; the start tile on the Route 27 side.
        assertEquals(TileKind.Bridge(overWater = true), area.tile(900, 404)?.kind)
        assertIs<TileKind.Bridge>(area.tile(895, 404)?.kind)
        val east = assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(890, 404), RouteOptions()) { it.x == 916 && it.y == 404 })
        assertTrue(east.route.edges.map { it.to }.any { it.x == 905 && it.y == 404 }, "over the bridge")
        // On the deck (height 48; the water under it is at 8).
        val deck = Pathfinder(area).levelAt(914, 400, 48)
        assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(914, 400, deck), RouteOptions()) { it.x == 914 && it.y == 387 })
    }

    @Test
    fun `Route 27 lists its east edge towards Route 26`() {
        val area = assertNotNull(world.areaOf(31))
        val east = WorldLinks.connections(area, 31).filter { it.direction == Direction.EAST && it.toZone == 30 }
        assertTrue(east.isNotEmpty())
        assertTrue(east.flatMap { it.tiles }.any { it == 895 to 404 }, east.toString())
    }

    @Test
    fun `holes are the trigger warps of Victory Road and Ice Path`() {
        val w = world
        val vr = assertNotNull(w.areaOf(179)).triggerWarps
        assertEquals(
            listOf(TriggerWarp(179, 1, 55, 42, 178, 57, 42), TriggerWarp(179, 2, 26, 38, 178, 28, 38), TriggerWarp(179, 3, 29, 44, 178, 31, 44), TriggerWarp(179, 4, 20, 18, 178, 22, 17), TriggerWarp(179, 5, 56, 28, 178, 58, 28)),
            vr,
        )
        assertEquals(TriggerWarp(237, 0, 11, 10, 238, 12, 12), assertNotNull(w.areaOf(237)).triggerWarps.first())
        // The rival scene trigger and the monk / Ho-Oh scenes are triggers, not holes.
        assertTrue(assertNotNull(w.areaOf(111)).triggerWarps.isEmpty())
        assertTrue(assertNotNull(w.areaOf(340)).triggers.any { it.x == 15 && it.y == 20 })
    }

    @Test
    fun `warps lead to the arrival tile of the other floor`() {
        val w = world
        val links = WorldLinks.links(w, assertNotNull(w.areaOf(178)), 178)
        val ladder = links.single { it.id == "warp:5" }
        assertEquals(Triple(179, 54, 22), Triple(ladder.targetZone, ladder.toX, ladder.toY))
        assertEquals(Direction.NORTH, ladder.exitDirection)
    }

    @Test
    fun `hidden items are not signs`() {
        val signs = assertNotNull(world.areaOf(58)).signs.filter { it.zone == 58 }
        assertEquals(SignKind.SIGN, signs.single { it.id == 0 }.kind)
        // std_hiddenitem_t10_rare_candy (8190) → FLAG_HIDDENITEM 800 + 190.
        val candy = signs.single { it.id == 1 }
        assertEquals(Triple(SignKind.HIDDEN_ITEM, 910 to 209, 990), Triple(candy.kind, candy.x to candy.y, candy.flag))
    }

    @Test
    fun `the router crosses Victory Road floors where the third floor alone has no way`() {
        val w = world
        val third = assertNotNull(w.areaOf(179))
        // From the Indigo Plateau exit (38,14) to the ladder at (54,22): not on 3F alone (notes: "no way… blocked").
        assertIs<Pathfinder.Result.Failed>(Pathfinder(third).route(Node(38, 14), RouteOptions(), setOf(54 to 22)) { it.x == 54 && it.y == 22 })
        val route = assertNotNull(WorldRouter(w) { _, _ -> dev.kotlinds.pokemonclient.world.Overlay() }.route(179, Node(38, 14), RouteOptions(), goalTiles = { if (it === third) setOf(54 to 22) else emptySet() }) {
            it.area === third && it.node.x == 54 && it.node.y == 22
        })
        assertTrue(route.links.any { it.kind == dev.kotlinds.pokemonclient.world.ZoneLink.Kind.HOLE }, route.links.toString())
        assertEquals(178, route.links.last().zone)
    }
}
