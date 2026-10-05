package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.view.StateView
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HgssGymPuzzles] on synthetic `Gymmick` slots (u32 type, then the union, include/gymmick.h); the rules come from the
 * decomp (see its KDoc). The live readings are checked on RAM fixtures in the JVM tests, except the Vermilion Gym
 * (Kanto is out of reach of our saves).
 */
class HgssGymPuzzlesTest {

    private class FakeReads(
        val gymmick: ByteArray? = null,
        val flags: Set<Int> = emptySet(),
    ) : HgssPuzzles.Reads {
        override fun variable(id: Int) = 0
        override fun flag(id: Int) = id in flags
        override fun gymmick() = gymmick
    }

    /** A `Gymmick` slot of [type] whose union starts with [data]. */
    private fun gymmick(type: Int, vararg data: Int) = ByteArray(0x24).also { b ->
        b[0] = type.toByte()
        data.forEachIndexed { i, v -> b[4 + i] = v.toByte() }
    }

    @Test
    fun theVioletGymLiftRidesBothWaysFromItsCenterAndShowsItsFloor() {
        val down = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.VIOLET_GYM, FakeReads(gymmick(4, 0)), null))
        assertEquals(PuzzleKind.LIFT, down.kind)
        assertEquals(listOf("lift:up", "lift:down"), down.teleports.map { it.id })
        assertTrue(down.teleports.all { it.kind == TeleportKind.LIFT && it.from == listOf(PuzzleTile(15, 20)) && it.to == PuzzleTile(15, 20) })
        assertEquals(listOf(4 to 62, 62 to 4), down.teleports.map { it.fromHeight to it.toHeight })
        assertEquals(9, down.surfaces.single().tiles.size)
        assertFalse(down.indicators.single { it.id == "lift:0" }.on)
        val up = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.VIOLET_GYM, FakeReads(gymmick(4, 1)), null))
        assertTrue(up.indicators.single { it.id == "lift:0" }.on)
        // Another gym's slot (left from a previous map) is not read as the lift.
        assertNull(HgssPuzzles.read(HgssGymPuzzles.VIOLET_GYM, FakeReads(gymmick(5)), null))
        // The view gives the agent the floor reached.
        assertTrue("\"to_height\":62" in StateView.puzzle(up).toString())
    }

    @Test
    fun theEcruteakCandlesFollowTheirMediums() {
        val tiles = Array<TileInfo?>(100) { TileInfo(false, TileKind.Floor) }
        val mediums = listOf(17 to 39, 19 to 30, 9 to 29, 11 to 19).mapIndexed { i, (x, y) ->
            PersonTemplate(HgssGymPuzzles.ECRUTEAK_GYM, 2 + i, 219, x, y, Direction.SOUTH, 1, 0, 0)
        }
        val area = Area(0, "gym", 0, 0, 10, 10, tiles, people = mediums)
        val puzzle = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.ECRUTEAK_GYM, FakeReads(gymmick(1, 0, 1, 0, 0)), area))
        assertEquals(PuzzleKind.HIDDEN_FLOOR, puzzle.kind)
        assertEquals(listOf(true, false, true, true), puzzle.indicators.map { it.on }, "candle 1 blown out (Grace beaten)")
        assertEquals(listOf(PuzzleTile(19, 30)), puzzle.indicators.single { it.id == "candle:1" }.tiles)
    }

    @Test
    fun theCianwoodWinchStopsTheWaterfall() {
        val flowing = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.CIANWOOD_GYM, FakeReads(gymmick(2, 0)), null))
        assertEquals(PuzzleKind.WATERFALL_WINCH, flowing.kind)
        val winch = flowing.switches.single()
        assertEquals(listOf("sign:0"), winch.targets)
        assertFalse(winch.used)
        assertTrue(flowing.indicators.single { it.id == "waterfall:0" }.on)
        assertTrue(flowing.barriers.isEmpty(), "the waterfall blocks no tile")
        // Turned: the gymmick's winch, or the script's flag.
        for (reads in listOf(FakeReads(gymmick(2, 1)), FakeReads(gymmick(2, 0), setOf(HgssGymPuzzles.FLAG_WATERFALL_DISABLE)))) {
            val stopped = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.CIANWOOD_GYM, reads, null))
            assertTrue(stopped.switches.single().used)
            assertFalse(stopped.indicators.single().on)
        }
    }

    @Test
    fun theVermilionCansHideTwoSwitchesThatOpenTheGates() {
        // First switch in can 7 (6,15), second in its neighbour 12 (6,17); both gates closed.
        val closed = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.VERMILION_GYM, FakeReads(gymmick(3, 7, 12, 0, 0)), null))
        assertEquals(PuzzleKind.TRASH_CAN_SWITCHES, closed.kind)
        assertEquals(listOf("gate:0" to false, "gate:1" to false), closed.barriers.map { it.id to it.open })
        assertEquals((5..7).map { PuzzleTile(it, 10) }, closed.barriers[0].tiles)
        assertEquals((5..7).map { PuzzleTile(it, 8) }, closed.barriers[1].tiles)
        assertEquals(listOf(listOf("sign:7"), listOf("sign:12")), closed.switches.map { it.targets })
        assertEquals(listOf(PuzzleTile(6, 15)), closed.switches[0].tiles)
        assertEquals(listOf(PuzzleTile(6, 17)), closed.switches[1].tiles)
        assertTrue(closed.switches.all { it.hidden })
        // Hidden switches are only shown with a walkthrough.
        assertTrue("sign:7" in StateView.puzzle(closed).toString())
        assertFalse("sign:7" in StateView.puzzle(closed, showHidden = false).toString())
        // First switch found: gate 0 open.
        val half = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.VERMILION_GYM, FakeReads(gymmick(3, 7, 12, 1, 0)), null))
        assertEquals(listOf(true, false), half.barriers.map { it.open })
        assertTrue(half.switches[0].used && !half.switches[1].used)
        // With the Thunder Badge (InitVermilionGym): both open, nothing left to find.
        val solved = assertNotNull(HgssPuzzles.read(HgssGymPuzzles.VERMILION_GYM, FakeReads(gymmick(3, 0, 0, 1, 1)), null))
        assertTrue(solved.barriers.all { it.open } && solved.switches.isEmpty())
        // The 5x3 grid of cans (bg events 0..14).
        assertEquals(listOf(PuzzleTile(2, 13), PuzzleTile(10, 13), PuzzleTile(2, 15), PuzzleTile(10, 17)), listOf(0, 4, 5, 14).map(HgssGymPuzzles::trashCan))
    }

    @Test
    fun gymsWhoseMechanismIsNotModeledSaySo() {
        val blackthorn = assertNotNull(HgssPuzzles.read(141, FakeReads(gymmick(6)), null))
        assertEquals(PuzzleKind.UNMODELED, blackthorn.kind)
        assertTrue("platforms" in assertNotNull(blackthorn.unmodeled))
        assertTrue("invisible walls" in assertNotNull(HgssPuzzles.read(0, FakeReads(gymmick(7)), null)?.unmodeled))
        // Modeled mechanisms say nothing.
        assertNull(HgssPuzzles.read(HgssGymPuzzles.VIOLET_GYM, FakeReads(gymmick(4)), null)?.unmodeled)
        assertNull(HgssPuzzles.read(1, FakeReads(gymmick(0)), null))
    }
}
