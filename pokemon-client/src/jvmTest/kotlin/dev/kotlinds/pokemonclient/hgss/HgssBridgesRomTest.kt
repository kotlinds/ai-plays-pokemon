package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bridges on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`): every bridge of every map is walked on foot,
 * and the Sprout Tower bridge of Violet City (NOTES-run P6: "needs Surf at 490,238") is crossed.
 */
class HgssBridgesRomTest {

    private val world get() = HgssWorldRom.require()

    /**
     * Every group of bridge tiles ([TileKind.Bridge]: wooden bridges of Routes 26/27/47/48, Ecruteak, the Route 12
     * pier, cave bridges) is reachable on foot from the land next to it: a bridge the pathfinder can't walk is listed.
     */
    @Test
    fun `every bridge of the ROM is walked without Surf`() {
        val w = world
        val areas = LinkedHashSet<Area>()
        for (zone in 0 until w.zoneCount) w.areaOf(zone)?.let { areas += it }
        val unwalkable = mutableListOf<String>()
        var groups = 0
        for (area in areas) {
            val bridge = HashSet<Pair<Int, Int>>()
            for (y in area.originY until area.originY + area.height) for (x in area.originX until area.originX + area.width) {
                if (area.tile(x, y)?.kind is TileKind.Bridge) bridge += x to y
            }
            val seen = HashSet<Pair<Int, Int>>()
            for (first in bridge) {
                if (!seen.add(first)) continue
                val group = mutableListOf<Pair<Int, Int>>()
                val stack = ArrayDeque(listOf(first))
                while (stack.isNotEmpty()) {
                    val c = stack.removeLast()
                    group += c
                    for (d in Direction.entries) {
                        val n = c.first + d.dx to c.second + d.dy
                        if (n in bridge && seen.add(n)) stack += n
                    }
                }
                groups++
                val land = group.flatMap { c -> Direction.entries.map { d -> c.first + d.dx to c.second + d.dy } }.distinct().filter { it !in bridge }
                    .filter { (x, y) -> area.tile(x, y)?.let { !it.blocked && it.kind !is TileKind.Water && it.kind != TileKind.Wall } == true }
                val pathfinder = Pathfinder(area)
                val reached = HashSet<Pair<Int, Int>>()
                for ((x, y) in land) {
                    val levels = area.tile(x, y)!!.heights.indices.toList().ifEmpty { listOf(0) }
                    for (level in levels) pathfinder.reachable(Node(x, y, level), RouteOptions(), REACH).keys.forEach { reached += it.x to it.y }
                }
                val missing = group.filter { it !in reached }
                if (missing.isNotEmpty()) unwalkable += "${area.name} (zone ${area.zoneAt(first.first, first.second)}): ${missing.take(MAX_LISTED)}"
            }
        }
        assertTrue(groups >= MIN_GROUPS, "only $groups bridges found")
        assertEquals(emptyList(), unwalkable, "bridge tiles the pathfinder can't walk")
    }

    /**
     * Violet City's Sprout Tower bridge (x 486..488, an arched floor over the pond, heights 48 → 59 → 48) carries a
     * placeholder scene trigger (scr_seq_T22_002: `End`, active while VAR_SCENE_VIOLET_CITY_OW is 0, the whole early
     * game) across its full width at y 234. It is inert: the route from the Pokémon Center to the tower crosses the
     * bridge instead of failing with "needs Surf".
     */
    @Test
    fun `the Sprout Tower bridge is crossed in the early game`() {
        val w = world
        val violet = assertNotNull(w.areaOf(VIOLET))
        val trigger = violet.triggers.single { it.zone == VIOLET && it.y == 234 && it.x == 486 }
        assertTrue(trigger.inert, "scr_seq_T22_002 is a placeholder")
        // Live triggers as go_to reads them: active (variable 0) and not inert.
        val active = violet.triggers.filter { it.zone == VIOLET && it.value == 0 && !it.inert }
            .flatMap { t -> (t.x until t.x + maxOf(1, t.width)).flatMap { x -> (t.y until t.y + maxOf(1, t.height)).map { y -> x to y } } }.toSet()
        val tower = assertNotNull(w.areaOf(SPROUT_TOWER_1F))
        val router = WorldRouter(w) { _, a -> if (a === violet) Overlay(activeTriggers = active) else WorldRouter.staticOverlay(a) }
        val route = assertNotNull(router.route(VIOLET, Node(497, 272), RouteOptions()) { it.area === tower }, "no route to Sprout Tower")
        assertEquals(listOf("warp:5"), route.links.map { it.id })
        assertTrue(route.places.any { it.node.x in 486..488 && it.node.y == 236 }, "over the bridge")
        // Treated as a scene, the same trigger would close the only dry way.
        val closed = WorldRouter(w) { _, a -> if (a === violet) Overlay(activeTriggers = (486..488).map { it to 234 }.toSet()) else WorldRouter.staticOverlay(a) }
        assertEquals(null, closed.route(VIOLET, Node(497, 272), RouteOptions()) { it.area === tower })
    }

    private companion object {
        const val VIOLET = 73
        const val SPROUT_TOWER_1F = 110
        const val REACH = 4000
        const val MIN_GROUPS = 20
        const val MAX_LISTED = 6
    }
}
