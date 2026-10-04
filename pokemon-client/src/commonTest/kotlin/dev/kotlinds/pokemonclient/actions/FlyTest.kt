package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Region
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A10: what `fly` says when it can't take the player where asked: another region (Johto ↔ Kanto), a town not visited,
 * and from indoors the nearest place where Fly works.
 *
 * The world: a house (zone 1, Fly not allowed, door at (1,2)) in Violet (zone 2, outdoors, the house door at (1,0));
 * Pallet (zone 5) is in Kanto, the others in Johto.
 */
class FlyTest {

    private val johto = Region(0, "Johto")
    private val kanto = Region(1, "Kanto")

    private fun area(zone: Int, rows: List<String>, warps: List<Warp>): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width][i % width]) {
                'D' -> TileInfo(true, TileKind.Door)
                '#' -> TileInfo(true, TileKind.Wall)
                else -> TileInfo(false, TileKind.Floor)
            }
        }
        return Area(zone, "zone $zone", 0, 0, width, rows.size, tiles, warps = warps, zones = IntArray(width * rows.size) { zone })
    }

    private val house = area(1, listOf("...", "...", ".D."), listOf(Warp(1, 0, 1, 2, 2, 0)))
    private val violet = area(2, listOf(".D...", ".....", "....."), listOf(Warp(2, 0, 1, 0, 1, 0)))

    private val flyer = PartyMon(
        MonId(1, 1), 0, Named(SpeciesId(18), "PIDGEOT"), null, 40, 100, 100, null, listOf("Normal", "Flying"), null, null,
        listOf(KnownMove(Named(MoveId(19), "Fly"), 15, 15, "Flying")), emptyMap(), 0, null, false,
    )

    /** A game standing in [zone] at ([x], [y]), on [screen]. */
    private fun game(zone: Int, x: Int, y: Int, screen: Screen = Screen.Overworld(null, Awaiting.INPUT)): Pair<PokemonGame, PlanContext> {
        val field = FieldState(zone, "zone $zone", x, y, 0, Direction.NORTH, MovementMode.WALK, false, flyAllowed = zone != 1)
        val fake = FakeGame(screen) { GameState(0, it, PlayerInfo("ACE", 0, listOf("Storm"), 1, badgeIds = setOf(4)), listOf(flyer), null, null, field) }
        val game = object : PokemonGame by fake {
            override val world = object : WorldSource {
                override fun areaOf(zoneId: Int) = when (zoneId) { 1 -> house; 2 -> violet; else -> null }
                override val zoneCount get() = 6
                override fun flyAllowed(zoneId: Int) = zoneId != 1
                override fun regionOf(zoneId: Int) = if (zoneId == 5) kanto else johto
            }
            override fun zoneName(id: Int) = when (id) { 1 -> "House"; 2 -> "Violet"; 5 -> "Pallet"; else -> null }
        }
        return game to PlanContext(fake.scope(), game)
    }

    @Test
    fun aTownOfTheOtherRegionIsOtherRegionByNameOrById() {
        val (_, context) = game(2, 2, 2)
        val byName = assertIs<ActionError.Unavailable>(FlyHints.otherRegion(context, "Pallet Town", startMap = 2))
        assertEquals(UnavailableReason.OTHER_REGION, byName.reason)
        assertTrue("Kanto" in byName.detail && "Johto" in byName.detail, byName.detail)
        assertEquals(UnavailableReason.OTHER_REGION, (FlyHints.otherRegion(context, "fly:5", startMap = 2) as ActionError.Unavailable).reason)
        // Same region, or a name that isn't a map: not this error.
        assertNull(FlyHints.otherRegion(context, "Violet City", startMap = 2))
        assertNull(FlyHints.otherRegion(context, "Atlantis", startMap = 2))
    }

    @Test
    fun aFlyMapEntryIsEitherInAnotherRegionOrNotVisited() {
        val (_, context) = game(2, 2, 2)
        val pallet = Entry("fly:5", "Pallet Town", selectable = false)
        val azalea = Entry("fly:74", "Azalea Town", selectable = false)
        val map = Screen.FlyMap(listOf(pallet, azalea), Cursor.Hidden, Topology { _, _ -> null }, otherRegion = setOf("fly:5"))
        val other = assertIs<ActionError.Unavailable>(FlyHints.notSelectable(context, pallet, map, startMap = 2))
        assertEquals(UnavailableReason.OTHER_REGION, other.reason)
        val notVisited = assertIs<ActionError.Unavailable>(FlyHints.notSelectable(context, azalea, map, startMap = 2))
        assertEquals(UnavailableReason.NOT_VISITED, notVisited.reason)
    }

    @Test
    fun indoorsTheRefusalNamesTheNearestPlaceWhereFlyWorks() {
        val (game, context) = game(1, 1, 0)
        val outcome = ActionRegistry.of().execute(GameAction.Fly("Violet"), context.scope, game)
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals(UnavailableReason.NOT_FLYABLE_HERE, error.reason)
        assertEquals("the nearest place where Fly works: Violet, via warp:0 (go_to \"Violet\")", error.hint)
    }
}
