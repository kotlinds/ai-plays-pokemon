package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PlatformPlanner
import dev.kotlinds.pokemonclient.world.PlatformPose
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.TeleportLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Blackthorn Gym platforms ([HgssBlackthornGym]) on the real ROM's lava (skipped without `POKEMON_ROM`): the
 * platforms' rules and a planned crossing from the entrance to Clair.
 */
class HgssBlackthornGymTest {

    private val area get() = HgssWorldRom.require().areaOf(HgssBlackthornGym.MAP)!!

    /** `InitBlackthornGym` (src/gymmick_init.c). */
    private val initial = listOf(PlatformPose(13, 75, 0), PlatformPose(9, 58, 1), PlatformPose(14, 32, 0))

    /** The gym's people where the ROM puts them (Clair at 12,3) and the exit pads (always active). */
    private fun overlay() = Overlay(
        objects = area.people.filter { it.zone == HgssBlackthornGym.MAP }.map { LiveObject(it.x, it.y, it.facing) },
        teleports = listOf(5 to 9, 23 to 41, 3 to 62).map { (x, y) -> TeleportLink(x, y, 8, 83) },
    )

    @Test
    fun `the gymmick slot gives the three platform poses`() {
        val bytes = ByteArray(4 + 0x20)
        bytes[0] = 6
        listOf(13, 9, 14).forEachIndexed { i, x -> bytes[4 + 2 * i] = x.toByte() }
        listOf(75, 58, 32).forEachIndexed { i, y -> bytes[10 + 2 * i] = y.toByte() }
        bytes[17] = 1
        assertEquals(initial, HgssBlackthornGym.poses(bytes))
        bytes[0] = 5
        assertNull(HgssBlackthornGym.poses(bytes), "another gym's slot")
    }

    @Test
    fun `the platforms start on the lava and their trigger tiles turn or slide them`() {
        val gym = HgssBlackthornGym(initial, area)
        // Every walkable platform tile is lava in the ROM: the platforms are what makes it walkable.
        assertTrue(gym.walkTiles(initial).all { (x, y) -> area.tile(x, y)?.kind == dev.kotlinds.pokemonclient.world.TileKind.Lava })
        // Platform 1 (rotation 1, its long side east-west) has its triggers on the column of its pivot.
        assertEquals(Triple(9 to 58, 9 to 59, 9 to 57), gym.triggers(initial[1]))
        // Plain platform tiles aren't triggers.
        assertNull(gym.ride(initial, 13, 77))
        // A ride carries the player: a slide moves them as far as the platform.
        for (pose in initial) {
            val (pivot, forward, backward) = gym.triggers(pose)
            for (tile in listOf(pivot, forward, backward)) {
                val ride = assertNotNull(gym.ride(initial, tile.first, tile.second))
                val moved = ride.poses[initial.indexOf(pose)]
                if (ride.moved) assertEquals(tile.first + moved.x - pose.x to tile.second + moved.y - pose.y, ride.playerX to ride.playerY)
                else assertEquals(initial, ride.poses)
            }
        }
    }

    @Test
    fun `without the platforms the lava cuts Clair off from the entrance`() {
        val result = Pathfinder(area, overlay()).route(Node(13, 86), RouteOptions()) { it.x == 12 && it.y == 4 }
        assertIs<Pathfinder.Result.Failed>(result)
    }

    @Test
    fun `go_to plans the rides from the entrance to Clair`() {
        val gym = HgssBlackthornGym(initial, area)
        val route = assertNotNull(PlatformPlanner(area, overlay(), gym).route(Node(13, 86), RouteOptions(), emptySet()) { it.x == 12 && it.y == 4 })
        val rides = route.edges.filterIsInstance<Edge.Teleport>()
        assertTrue(rides.isNotEmpty(), "the lava is only crossed by riding")
        assertEquals(12 to 4, route.end!!.x to route.end!!.y)
        // Replaying the route with the rules: every step lands on a walkable tile of the configuration of the moment,
        // and every ride is what the trigger does.
        var poses = initial
        var at = 13 to 86
        for (edge in route.edges) {
            val tiles = gym.walkTiles(poses)
            when (edge) {
                is Edge.Teleport -> {
                    val ride = assertNotNull(gym.ride(poses, edge.via.x, edge.via.y))
                    assertTrue(ride.moved)
                    assertEquals(edge.to.x to edge.to.y, ride.playerX to ride.playerY)
                    poses = ride.poses
                }
                else -> for (n in edge.tiles) {
                    val lava = area.tile(n.x, n.y)?.kind == dev.kotlinds.pokemonclient.world.TileKind.Lava
                    assertFalse(lava && (n.x to n.y) !in tiles, "step on bare lava at ${n.x},${n.y}")
                    assertFalse(gym.ride(poses, n.x, n.y)?.moved == true, "a plain step on a live trigger at ${n.x},${n.y}")
                }
            }
            at = edge.to.x to edge.to.y
        }
        assertEquals(12 to 4, at)
    }
}

/**
 * The platforms read live (bench, DeSmuME, 8-badge save): `pz_blackthorn_entrance` on entering the gym (13,87), and
 * `pz_blackthorn_clair` after one `go_to person:0` from there, which rode the platforms to Clair (13,3).
 */
class HgssBlackthornGymFixtureTest {

    private fun field(name: String) = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    private fun poses(name: String) = assertNotNull(HgssBlackthornGym.poses(assertNotNull(HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).gymmick())))

    @Test
    fun `the platforms are read from the gymmick on entering the gym`() {
        assertEquals(listOf(PlatformPose(13, 75, 0), PlatformPose(9, 58, 1), PlatformPose(14, 32, 0)), poses("pz_blackthorn_entrance"))
    }

    @Test
    fun `the puzzle lists the platforms and what each trigger does now`() {
        val reader = HgssReader(HgssFixtures.load("pz_blackthorn_entrance"), HgssVersion.HEARTGOLD_US)
        val area = HgssWorldRom.require().areaOf(HgssBlackthornGym.MAP)
        val puzzle = assertNotNull(HgssPuzzles.read(HgssBlackthornGym.MAP, HgssPuzzles.reads(reader), area))
        assertEquals(dev.kotlinds.pokemonclient.state.PuzzleKind.MOVING_PLATFORMS, puzzle.kind)
        assertNotNull(puzzle.mechanics)
        assertEquals(listOf("platform:0", "platform:1", "platform:2"), puzzle.platforms.map { it.id })
        // Platform 0 can only slide west at first (the floor is right east of it, and too close to turn).
        val first = puzzle.platforms[0].triggers.associate { (it.tile.x to it.tile.y) to it.possible }
        assertEquals(mapOf((13 to 75) to false, (14 to 75) to false, (12 to 75) to true), first)
        // The exit pads are still there.
        assertEquals(3, puzzle.teleports.size)
    }

    @Test
    fun `the planned rides end with the platforms where the game put them`() {
        val area = HgssWorldRom.require().areaOf(HgssBlackthornGym.MAP)!!
        val start = field("pz_blackthorn_entrance")
        assertEquals(13 to 87, start.x to start.y)
        assertEquals(13 to 3, field("pz_blackthorn_clair").let { it.x to it.y })
        val gym = HgssBlackthornGym(poses("pz_blackthorn_entrance"), area)
        val overlay = Overlay(objects = start.objects.map { LiveObject(it.x, it.y, it.facing) })
        val route = assertNotNull(PlatformPlanner(area, overlay, gym).route(Node(13, 87), RouteOptions()) { it.x == 13 && it.y == 3 })
        var poses = gym.poses
        route.edges.filterIsInstance<Edge.Teleport>().forEach { poses = assertNotNull(gym.ride(poses, it.via.x, it.via.y)).poses }
        assertEquals(poses("pz_blackthorn_clair"), poses)
    }
}

/** The Ilex Forest Farfetch'd plans ([HgssIlexFarfetchd]) on the ROM's tiles (skipped without `POKEMON_ROM`). */
class HgssIlexFarfetchdRomTest {

    @Test
    fun `every tile of the plans from the forest's entry state can be stood on`() {
        val area = HgssWorldRom.require().areaOf(HgssIlexFarfetchd.MAP)!!
        val walkable: (Int, Int) -> Boolean = { x, y -> area.tile(x, y)?.let { !it.blocked } ?: false }
        val plans = listOf(
            HgssIlexFarfetchd.plan(1, HgssIlexFarfetchd.State(HgssIlexFarfetchd.Spot.BOTTOM_LEFT, 1, false), walkable),
            HgssIlexFarfetchd.plan(2, HgssIlexFarfetchd.State(HgssIlexFarfetchd.Spot.TOP_LEFT, 1 shl 3, false), walkable),
        )
        for (plan in plans) {
            assertTrue(plan.isNotEmpty())
            assertIs<dev.kotlinds.pokemonclient.state.HerdOutcome.Caught>((plan.last() as dev.kotlinds.pokemonclient.state.HerdStep.TalkFrom).outcome)
        }
        // From every spot and twig state, a plan exists on the real forest.
        for (bird in 1..2) for (spot in HgssIlexFarfetchd.Spot.entries) {
            assertTrue(HgssIlexFarfetchd.plan(bird, HgssIlexFarfetchd.State(spot, 0, false), walkable).isNotEmpty(), "bird $bird at $spot")
        }
    }
}
