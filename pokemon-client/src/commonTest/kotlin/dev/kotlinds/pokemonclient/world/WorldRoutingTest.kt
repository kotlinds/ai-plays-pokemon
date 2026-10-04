package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.view.MapView
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Areas drawn in ASCII: '.' floor, '#' wall, '~' water, 's' bridge start, '=' bridge, 'b' bridge over water,
 * ']' floor with a railing on its east side. [zones] gives each tile's zone digit (shared areas), else [zone].
 */
private fun area(zone: Int, vararg rows: String, zones: List<String>? = null, warps: List<Warp> = emptyList(), holes: List<TriggerWarp> = emptyList(), signs: List<Sign> = emptyList()): Area {
    val width = rows.maxOf { it.length }
    val tiles = Array<TileInfo?>(width * rows.size) { i ->
        when (rows[i / width].getOrElse(i % width) { '#' }) {
            '#' -> TileInfo(true, TileKind.Wall)
            '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
            's' -> TileInfo(false, TileKind.Bridge(start = true))
            '=' -> TileInfo(false, TileKind.Bridge())
            'b' -> TileInfo(false, TileKind.Bridge(overWater = true))
            ']' -> TileInfo(false, TileKind.Railing(setOf(Direction.EAST)))
            else -> TileInfo(false, TileKind.Floor)
        }
    }
    val zoneArray = zones?.let { z -> IntArray(width * rows.size) { i -> z[i / width][i % width].digitToInt() } } ?: IntArray(width * rows.size) { zone }
    return Area(zone, "Zone $zone", 0, 0, width, rows.size, tiles, warps = warps, signs = signs, zones = zoneArray, triggerWarps = holes)
}

class WorldRoutingTest {

    private fun Pathfinder.to(x: Int, y: Int, from: Node, options: RouteOptions = RouteOptions(), goalTiles: Set<Pair<Int, Int>> = emptySet()) =
        route(from, options, goalTiles) { it.x == x && it.y == y }

    @Test
    fun aBridgeOverWaterIsWalkedFromTheBridgeOnly() {
        // Route 26: land, the bridge's start tile, the planks over water, land again.
        val bridge = area(0, ".sbbb=.", "~~~~~~~")
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(bridge).to(6, 0, Node(0, 0)))
        assertEquals(6, found.route.edges.size)
        // From the water side (not on the bridge), the planks are water: Surf is needed, not a walk.
        val fromWater = assertIs<Pathfinder.Result.Failed>(Pathfinder(area(0, "..", ".b")).to(1, 1, Node(0, 1)))
        assertEquals(RouteFailure.Unreachable, fromWater.failure)
        // Surfing passes under it.
        assertIs<Pathfinder.Result.Found>(Pathfinder(area(0, "~b~")).to(2, 0, Node(0, 0), RouteOptions(mode = MovementMode.SURF)))
    }

    @Test
    fun aRailingBlocksItsSideBothWays() {
        val map = area(0, ".].", "...")
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(2, 0, Node(1, 0)))
        assertEquals(3, found.route.edges.size, "around the railing, not through its east side")
        assertEquals(3, assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(1, 0, Node(2, 0))).route.edges.size)
    }

    @Test
    fun anActiveTriggerIsCrossedOnlyAsTheLastResortWithAWarning() {
        val corridor = area(0, ".....")
        val overlay = Overlay(activeTriggers = setOf(2 to 0))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(corridor, overlay).to(4, 0, Node(0, 0)))
        assertEquals(listOf<RouteWarning>(RouteWarning.StartsScene(2, 0)), found.route.warnings)
        // With another way, the trigger is avoided.
        val open = area(0, ".....", ".....")
        val around = assertIs<Pathfinder.Result.Found>(Pathfinder(open, overlay).to(4, 0, Node(0, 0)))
        assertTrue(around.route.edges.none { it.to.x == 2 && it.to.y == 0 })
        assertTrue(around.route.warnings.isEmpty())
        // As the destination (a goal tile), it's entered.
        val goal = assertIs<Pathfinder.Result.Found>(Pathfinder(open, overlay).to(2, 0, Node(0, 0), goalTiles = setOf(2 to 0)))
        assertEquals(listOf<RouteWarning>(RouteWarning.StartsScene(2, 0)), goal.route.warnings)
    }

    @Test
    fun linksGiveTheArrivalTileAndHoles() {
        val up = area(2, "...", warps = listOf(Warp(2, 0, 0, 0, 1, 1)))
        val down = area(1, "...", warps = listOf(Warp(1, 0, 0, 0, 9, 0), Warp(1, 1, 2, 0, 2, 0, Direction.NORTH)), holes = listOf(TriggerWarp(1, 3, 1, 0, 2, 2, 0)))
        val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = mapOf(1 to down, 2 to up)[zoneId]
        }
        val links = WorldLinks.links(world, down, 1)
        assertEquals(ZoneLink(ZoneLink.Kind.WARP, "warp:1", 1, 2, 0, Direction.NORTH, 2, 0, 0), links.first { it.id == "warp:1" })
        assertNull(links.first { it.id == "warp:0" }.toX, "unknown destination")
        val hole = links.single { it.kind == ZoneLink.Kind.HOLE }
        assertEquals("hole:3" to (2 to 0), hole.id to (hole.toX!! to hole.toY!!))
        assertTrue(hole.oneWay)
    }

    @Test
    fun connectionsAreTheCrossableEdgesBetweenZonesOfAnArea() {
        val outdoor = area(1, "...~..", "..#...", zones = listOf("111222", "111222"))
        val connections = WorldLinks.connections(outdoor, 1)
        val east = connections.single()
        assertEquals(Direction.EAST to 2, east.direction to east.toZone)
        assertEquals(listOf(2 to 0), east.tiles, "the wall at 2,1 isn't a way")
        assertTrue(east.byWater)
        assertEquals("exit:east", east.id)
        assertEquals(Direction.WEST, WorldLinks.connections(outdoor, 2).single().direction)
    }

    @Test
    fun theRouterGoesThroughWarpsAndHoles() {
        // Zone 1: start walled off from the goal; a ladder (press north) up to zone 2, whose hole falls next to the goal.
        val one = area(1, ".#..", ".#..", warps = listOf(Warp(1, 0, 0, 1, 2, 0, Direction.NORTH)))
        val two = area(2, "....", warps = listOf(Warp(2, 0, 0, 0, 1, 0, Direction.SOUTH)), holes = listOf(TriggerWarp(2, 0, 3, 0, 1, 3, 1)))
        val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = mapOf(1 to one, 2 to two)[zoneId]
        }
        val route = assertNotNull(WorldRouter(world).route(1, Node(0, 0), RouteOptions()) { it.area === one && it.node.x == 3 && it.node.y == 0 })
        assertEquals(listOf("warp:0", "hole:0"), route.links.map { it.id })
        assertTrue(route.oneWay)
        // A boulder where the map places it blocks the other floors too (static overlay).
        val blocked = area(2, "....", warps = listOf(Warp(2, 0, 0, 0, 1, 0, Direction.SOUTH)), holes = listOf(TriggerWarp(2, 0, 3, 0, 1, 3, 1)))
        val withBoulder = Area(2, "Zone 2", 0, 0, 4, 1, Array(4) { blocked.tile(it, 0) }, warps = blocked.warps, people = listOf(PersonTemplate(2, 1, 0, 2, 0, null, 0, 0, 0, FieldMoveKind.STRENGTH)), zones = IntArray(4) { 2 }, triggerWarps = blocked.triggerWarps)
        val world2 = object : WorldSource {
            override fun areaOf(zoneId: Int) = mapOf(1 to one, 2 to withBoulder)[zoneId]
        }
        assertNull(WorldRouter(world2).route(1, Node(0, 0), RouteOptions()) { it.area === one && it.node.x == 3 && it.node.y == 0 })
    }

    @Test
    fun theMapListsArrivalsHolesEdgesItemsAndHiddenItems() {
        val outdoor = area(
            1, ".....~", ".....~", zones = listOf("111122", "111122"),
            warps = listOf(Warp(1, 0, 0, 0, 5, 2, Direction.NORTH)),
            holes = listOf(TriggerWarp(1, 4, 1, 1, 6, 7, 8)),
            signs = listOf(Sign(1, 0, 2, 0, 1), Sign(1, 1, 3, 0, 8190, SignKind.HIDDEN_ITEM, 990), Sign(1, 2, 3, 1, 8191, SignKind.HIDDEN_ITEM, 991)),
        )
        val inside = area(5, "...", warps = listOf(Warp(5, 0, 0, 0, 1, 0), Warp(5, 1, 1, 0, 1, 0), Warp(5, 2, 2, 0, 1, 0)))
        val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = mapOf(1 to outdoor, 5 to inside)[zoneId]
        }
        val field = FieldState(
            1, "Town", 3, 1, 0, Direction.NORTH, MovementMode.WALK, false,
            objects = listOf(FieldObject("person:9", "item ball", FieldObjectKind.ITEM_BALL, 0, 1, null)),
            pickedUp = setOf("hidden_item:2"),
        )
        val names = mapOf(5 to "House", 6 to "Cellar", 2 to "Route 1")
        val view = MapView.render(outdoor, field, { names[it] }, width = 6, height = 2, world = world)
        val exits = view["exits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exits.any { it.startsWith("warp:0 at 0,0") && "→ House (2,0)" in it }, exits.toString())
        assertTrue(exits.any { it.startsWith("hole:4 at 1,1") && "→ Cellar (7,8)" in it && "one way" in it }, exits.toString())
        assertTrue(exits.any { it.startsWith("exit:east → Route 1 along 3,0..1") }, exits.toString())
        assertEquals(listOf("item:9 item ball at 0,1 (3 west)"), view["items"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(null, view["people"])
        assertEquals(listOf("sign:0 at 2,0 (1 west, 1 north)"), view["signs"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The picked-up hidden item is gone; the other one is listed with its own id, not as a sign.
        assertEquals(listOf("hidden_item:1 at 3,0 (1 north): face it and press A (interact)"), view["hidden_items"]!!.jsonArray.map { it.jsonPrimitive.content })
        val rows = view["map"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("   0 E . S $ . ~", rows[1])
        assertEquals("   1 o O . A . ~", rows[2])
    }
}
