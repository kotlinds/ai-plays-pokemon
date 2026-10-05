package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.view.StateView
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.ScriptWarp
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HgssPuzzles] on synthetic game states: the rules come from the decomp (scr_seq_0096_D37R0104.s, overlay_04
 * Azalea tables, see the KDoc of [HgssPuzzles]); the live readings are checked on RAM fixtures in the JVM tests.
 */
class HgssPuzzlesTest {

    private class FakeReads(
        val vars: Map<Int, Int> = emptyMap(),
        val flags: Set<Int> = emptySet(),
        val gymmick: ByteArray? = null,
    ) : HgssPuzzles.Reads {
        override fun variable(id: Int) = vars[id] ?: 0
        override fun flag(id: Int) = id in flags
        override fun gymmick() = gymmick
    }

    /** The tunnel's state on entry (scr_seq_D37R0104_006): gates 2 and 9 closed, the others open. */
    private val tunnelOnEntry = mapOf(0x4002 to 1, 0x4009 to 1)

    @Test
    fun goldenrodTunnelShuttersFollowTheirVariables() {
        val puzzle = assertNotNull(HgssPuzzles.read(HgssPuzzles.GOLDENROD_TUNNEL_B2F, FakeReads(tunnelOnEntry), null))
        assertEquals(PuzzleKind.SHUTTER_SWITCHES, puzzle.kind)
        assertEquals(setOf("shutter:2", "shutter:9", "shutter:10"), puzzle.barriers.filterNot { it.open }.map { it.id }.toSet())
        assertEquals(listOf(PuzzleTile(6, 10)), puzzle.barriers.first { it.id == "shutter:2" }.tiles)
        assertEquals((2..4).map { PuzzleTile(it, 8) }, puzzle.barriers.first { it.id == "shutter:0" }.tiles)
        // The green switch (bg event 1) toggles gates 0, 2, 4, 6, 8, 9.
        val green = puzzle.switches.first { it.id == "switch:1" }
        assertEquals(listOf("sign:1"), green.targets)
        assertEquals(listOf(0, 2, 4, 6, 8, 9).map { "shutter:$it" }, green.toggles)
        val purple = puzzle.switches.first { it.id == "switch:3" }
        assertTrue(purple.oneShot && !purple.used)

        val opened = assertNotNull(HgssPuzzles.read(HgssPuzzles.GOLDENROD_TUNNEL_B2F, FakeReads(mapOf(0x4000 to 1), setOf(HgssPuzzles.FLAG_PURPLE_GATE)), null))
        assertEquals(setOf("shutter:0"), opened.barriers.filterNot { it.open }.map { it.id }.toSet())
        assertTrue(opened.switches.first { it.id == "switch:3" }.used)
    }

    @Test
    fun agentsReadThePuzzleWithItsIds() {
        val puzzle = assertNotNull(HgssPuzzles.read(HgssPuzzles.GOLDENROD_TUNNEL_B2F, FakeReads(tunnelOnEntry), null))
        val json = StateView.puzzle(puzzle).toString()
        assertTrue("\"kind\":\"shutter_switches\"" in json)
        assertTrue("{\"id\":\"shutter:2\",\"open\":false,\"tiles\":[\"6,10\"]}" in json, json)
        assertTrue("\"interact\":[\"sign:1\"]" in json, json)
    }

    /** `Gymmick` bytes: u32 type 5 (GYMMICK_AZALEA), u8 spiders[4], int switches. */
    private fun azalea(spiders: List<Int>, switches: Int) = ByteArray(0x24).also { b ->
        b[0] = 5
        spiders.forEachIndexed { i, s -> b[4 + i] = s.toByte() }
        b[8] = switches.toByte()
    }

    @Test
    fun azaleaGymCartsRideOnEntry() {
        // InitAzaleaGym: carts at stations 0, 1, 2, 7, levers 0.
        val puzzle = assertNotNull(HgssPuzzles.read(HgssPuzzles.AZALEA_GYM, FakeReads(gymmick = azalea(listOf(0, 1, 2, 7), 0)), null))
        assertEquals(PuzzleKind.CART_RIDES, puzzle.kind)
        val rides = puzzle.teleports.associateBy { it.id }
        assertEquals(setOf("cart:0", "cart:1", "cart:2", "cart:7"), rides.keys)
        // Station 1: entered from (9,32), arrives at station 5 (15,24), the player steps off north (NOTES 17h: 9,31 -> 15,23).
        assertEquals(listOf(PuzzleTile(9, 32)), rides.getValue("cart:1").from)
        assertEquals(PuzzleTile(15, 23), rides.getValue("cart:1").to)
        assertEquals(TeleportKind.CART_RIDE, rides.getValue("cart:1").kind)
        // Station 7 with levers 0 goes to station 9 (3,9): off at (3,8).
        assertEquals(PuzzleTile(3, 8), rides.getValue("cart:7").to)
        assertEquals(listOf(false, false), puzzle.switches.map { it.flipped })
        assertEquals(listOf("sign:0", "sign:1", "sign:2"), puzzle.switches[0].targets)
        assertEquals(listOf("cart:6", "cart:7", "cart:9", "cart:11"), puzzle.switches[0].toggles)
    }

    @Test
    fun azaleaGymLeversChangeTheUpperRoutesAndReversedRidesLandSouth() {
        // Cart at station 4 (reversed: the station tile is the trigger), back to station 0 (3,31): off at (3,33).
        assertEquals(PuzzleTile(9, 24), HgssPuzzles.azaleaTrigger(4))
        assertEquals(PuzzleTile(3, 33), HgssPuzzles.azaleaLanding(4, 0))
        assertEquals(listOf(9, 11, 10, 11), (0..3).map { HgssPuzzles.azaleaRoute(7, it) })
        val puzzle = assertNotNull(HgssPuzzles.read(HgssPuzzles.AZALEA_GYM, FakeReads(gymmick = azalea(listOf(4, 6, 8, 10), 3)), null))
        // Station 8 never goes anywhere; 6 and 10 with both levers flipped go to 10 and 6.
        assertEquals(mapOf("cart:4" to PuzzleTile(3, 33), "cart:6" to PuzzleTile(9, 8), "cart:10" to PuzzleTile(3, 18)), puzzle.teleports.associate { it.id to it.to })
        assertEquals(listOf(true, true), puzzle.switches.map { it.flipped })
    }

    @Test
    fun noAzaleaPuzzleWithoutTheAzaleaGymmick() {
        assertNull(HgssPuzzles.read(HgssPuzzles.AZALEA_GYM, FakeReads(gymmick = ByteArray(0x24)), null))
        assertNull(HgssPuzzles.read(1, FakeReads(), null))
    }

    @Test
    fun warpPadsAreListedWhileTheirTriggerIsActive() {
        val tiles = Array<TileInfo?>(100) { TileInfo(false, TileKind.Floor) }
        val area = Area(
            0, "gym", 0, 0, 10, 10, tiles,
            triggers = listOf(Trigger(141, 0, 5, 9, 1, 1, 3, 0x4111, 0)),
            scriptWarps = listOf(ScriptWarp(141, 0, 8, 3, Direction.NORTH)),
        )
        val active = assertNotNull(HgssPuzzles.read(141, FakeReads(), area))
        assertEquals(PuzzleKind.TELEPORT_PADS, active.kind)
        val pad = active.teleports.single()
        assertEquals("teleport:0", pad.id)
        assertEquals(listOf(PuzzleTile(5, 9)), pad.from)
        assertEquals(PuzzleTile(8, 3), pad.to)
        assertNull(HgssPuzzles.read(141, FakeReads(vars = mapOf(0x4111 to 1)), area))
    }
}
