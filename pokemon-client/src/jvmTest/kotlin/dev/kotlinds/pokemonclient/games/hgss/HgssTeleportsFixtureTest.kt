package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import dev.kotlinds.pokemonclient.world.ZoneLink
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Same-map warps, object ids and map objects on the player's Kanto save (bench, DeSmuME; skipped without
 * `POKEMON_ROM`):
 * - `tp_sgym_entry`: Saffron Gym (map 410) at its entrance (15,28). Live, `interact person:5` (Sabrina) failed with
 *   "warp:1 at 18,22 didn't take the player anywhere" although the pad had moved the player; with the pads routed as
 *   teleports, `go_to person:5` reached (15,14) in one call.
 * - `tp_sgym_sabrina`: the same gym next to Sabrina (15,14), already beaten.
 * - `tp_nb_outside`: New Bark (map 60) in front of the player's house (695,397).
 * - `tp_route8`: Route 8 (map 16) at 1404,238, just come from Lavender Town (map 53): a Lavender boy is still loaded.
 * - `tp_vermilion`: Vermilion City (map 54) after flying there (1297,295); a Cut tree stands at 1300,319 on the way to
 *   the Gym door.
 */
class HgssTeleportsFixtureTest {

    private fun state(name: String): GameState {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        return HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))
    }

    private fun field(name: String): FieldState = assertNotNull(state(name).field)

    private fun overlay(field: FieldState, teleports: Boolean) = Overlay(
        objects = field.objects.map { LiveObject(it.x, it.y, it.facing) },
        teleports = if (teleports) WorldLinks.sameZoneTeleports(assertNotNull(HgssData.world?.areaOf(field.mapId)), field.mapId) else emptyList(),
    )

    @Test
    fun `the Saffron Gym's pads lead to Sabrina in one route`() {
        val field = field("tp_sgym_entry")
        assertEquals(410 to (15 to 28), field.mapId to (field.x to field.y))
        val area = assertNotNull(HgssData.world?.areaOf(410))
        val nextToSabrina: (Node) -> Boolean = { it.x == 15 && it.y == 14 }
        // Walking only: Sabrina's room is closed by walls.
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, overlay(field, teleports = false)).route(Node(15, 28), RouteOptions(), isGoal = nextToSabrina))
        val route = assertIs<Pathfinder.Result.Found>(Pathfinder(area, overlay(field, teleports = true)).route(Node(15, 28), RouteOptions(), isGoal = nextToSabrina)).route
        assertTrue(route.edges.count { it is Edge.Teleport } >= 3, route.edges.toString())
        // The pads are no links of the world router: the route stays on the map.
        val world = HgssWorldRom.require()
        val across = assertNotNull(WorldRouter(world) { _, a -> if (a === area) overlay(field, true) else WorldRouter.staticOverlay(a) }
            .route(410, Node(15, 28), RouteOptions()) { it.area === area && nextToSabrina(it.node) })
        assertEquals(emptyList(), across.links)
        // The exit pad (a warp script) stays a puzzle teleport, the pads to the same map are listed as such.
        assertTrue(WorldLinks.sameZoneWarps(area, 410).size == 30)
    }

    @Test
    fun `neither the exit pad nor Sabrina is a story blocker`() {
        val entry = state("tp_sgym_entry")
        assertEquals(emptyList(), entry.story?.blockers.orEmpty().map { it.target })
        val sabrina = state("tp_sgym_sabrina")
        assertEquals(emptyList(), sabrina.story?.blockers.orEmpty().map { it.target })
        val leader = assertNotNull(sabrina.field?.objects?.firstOrNull { it.id == "person:5" })
        assertEquals(15 to 13, leader.x to leader.y)
        assertTrue(leader.role != PersonRole.BLOCKER)
    }

    @Test
    fun `New Bark lists neither the parked Prof Elm nor a hole on the lab's ladder`() {
        val field = field("tp_nb_outside")
        assertEquals(60, field.mapId)
        assertNull(field.objects.firstOrNull { it.x == 703 && it.y == 384 })
        assertTrue(field.objects.any { it.id == "person:1" })
        val world = HgssWorldRom.require()
        val area = assertNotNull(world.areaOf(60))
        assertTrue(WorldLinks.links(world, area, 60).none { it.kind == ZoneLink.Kind.HOLE }, WorldLinks.links(world, area, 60).toString())
        val exits = assertNotNull(MapView.render(area, field, HgssData::mapName, world = world)["exits"]).jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exits.none { it.startsWith("hole:") }, exits.toString())
        assertTrue(exits.any { it.startsWith("warp:4 at 688,392") }, exits.toString())
    }

    @Test
    fun `objects of the zone just left keep their own zone in their id`() {
        val field = field("tp_route8")
        assertEquals(16, field.mapId)
        val ids = field.objects.map { it.id }
        assertEquals(ids.distinct(), ids)
        val boy = assertNotNull(field.objects.firstOrNull { it.id == "person:1@53" })
        assertEquals(1414 to 244, boy.x to boy.y)
        assertNull(boy.trainer)
        val harris = assertNotNull(field.objects.firstOrNull { it.id == "person:1" })
        assertEquals(1360 to 244, harris.x to harris.y)
        assertNotNull(harris.trainer)
    }

    @Test
    fun `routes across maps cut the tree in front of the Vermilion Gym like on one map`() {
        val field = field("tp_vermilion")
        assertEquals(54, field.mapId)
        val world = HgssWorldRom.require()
        val city = assertNotNull(world.areaOf(54))
        val gymZone = city.warps.first { it.zone == 54 && it.id == 6 }.targetZone
        val gym = assertNotNull(world.areaOf(gymZone))
        val live = Overlay(objects = field.objects.map { LiveObject(it.x, it.y, it.facing, clearedBy = FieldMoveKind.CUT.takeIf { _ -> it.obstacle == ObstacleKind.CUT_TREE }) })
        fun route(moves: Set<FieldMoveKind>) = WorldRouter(world) { _, a -> if (a === city) live else WorldRouter.staticOverlay(a) }
            .route(54, Node(field.x, field.y), RouteOptions(fieldMoves = moves)) { it.area === gym }
        assertNull(route(emptySet()))
        val withCut = assertNotNull(route(setOf(FieldMoveKind.CUT)))
        assertEquals("warp:6", withCut.links.single().id)
        assertFalse(withCut.oneWay)
    }
}
