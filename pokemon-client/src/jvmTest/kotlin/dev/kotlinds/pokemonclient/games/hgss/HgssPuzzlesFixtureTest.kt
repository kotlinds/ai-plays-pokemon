package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.ScriptWarp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [HgssPuzzles] on real HeartGold (USA) RAM snapshots, captured with the bench (DeSmuME, 8-badge save where the
 * three puzzles were solved by hand during the agent's run, NOTES H7-H9):
 * - `gimmick_goldenrod_tunnel`: Goldenrod Tunnel B2F on entry (gates 2 and 9 closed, purple gate opened earlier);
 * - `gimmick_goldenrod_tunnel_green`: the same after `interact sign:1` (green switch);
 * - `gimmick_azalea_gym`: Azalea Gym after riding carts 1→5, 5→1, 0→4, 7→11 and flipping lever 0 (sign:2), at (14,6);
 * - `gimmick_blackthorn_gym`: Blackthorn Gym at the entrance (exit pads active, `VAR_UNK_4111` = 0).
 * The ROM checks are skipped without `POKEMON_ROM` ([HgssWorldRom]).
 */
class HgssPuzzlesFixtureTest {

    private fun field(name: String): FieldState = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    @Test
    fun goldenrodTunnelShuttersMatchTheShutterObjectsInRam() {
        for ((fixture, closed) in listOf(
            "gimmick_goldenrod_tunnel" to setOf(2, 9),
            "gimmick_goldenrod_tunnel_green" to setOf(0, 4, 6, 8),
        )) {
            val field = field(fixture)
            assertEquals(HgssPuzzles.GOLDENROD_TUNNEL_B2F, field.mapId)
            val puzzle = assertNotNull(field.puzzle, fixture)
            assertEquals(PuzzleKind.SHUTTER_SWITCHES, puzzle.kind)
            assertEquals(closed.map { "shutter:$it" }.toSet(), puzzle.barriers.filterNot { it.open }.map { it.id }.toSet(), fixture)
            assertTrue(puzzle.switches.first { it.id == "switch:3" }.used, "the purple gate was opened during the run")
            // The model agrees with the game's own objects: a closed shutter's tiles hold its halves and its invisible
            // `STOP` blocker (hidden objects: not in FieldState.objects, hence the barrier tiles), an open one's are free.
            val objects = assertNotNull(HgssReader(HgssFixtures.load(fixture), HgssVersion.HEARTGOLD_US).read()?.surroundings).objects
            val occupied = objects.map { PuzzleTile(it.x, it.z) }.toSet()
            for (barrier in puzzle.barriers) {
                if (barrier.open) assertTrue(barrier.tiles.none { it in occupied }, "$fixture ${barrier.id} open but occupied")
                else assertTrue(barrier.tiles.all { it in occupied }, "$fixture ${barrier.id} closed but free")
            }
            val stops = objects.filter { it.sprite == "STOP" }
            assertTrue(stops.isNotEmpty() && stops.all { it.hidden }, "the STOP blockers are invisible objects")
        }
    }

    @Test
    fun azaleaGymCartsAndLeversAreReadFromTheGymmick() {
        val field = field("gimmick_azalea_gym")
        assertEquals(HgssPuzzles.AZALEA_GYM, field.mapId)
        assertEquals(14 to 6, field.x to field.y)
        val puzzle = assertNotNull(field.puzzle)
        assertEquals(PuzzleKind.CART_RIDES, puzzle.kind)
        assertEquals(listOf(true, false), puzzle.switches.map { it.flipped })
        val rides = puzzle.teleports.associate { it.id to it.to }
        // Carts now at stations 1, 2, 4, 11; with lever 0 flipped, 11 goes back to 7 (off at 9,18).
        assertEquals(
            mapOf("cart:1" to PuzzleTile(15, 23), "cart:2" to PuzzleTile(3, 23), "cart:4" to PuzzleTile(3, 33), "cart:11" to PuzzleTile(9, 18)),
            rides,
        )
        assertTrue(puzzle.teleports.all { it.kind == TeleportKind.CART_RIDE })
    }

    @Test
    fun blackthornGymExitPadsAreActive() {
        val memory = HgssFixtures.load("gimmick_blackthorn_gym")
        val reader = HgssReader(memory, HgssVersion.HEARTGOLD_US)
        assertEquals(0, reader.variable(0x4111))
        val area = HgssWorldRom.require().areaOf(141)
        val puzzle = assertNotNull(HgssPuzzles.read(141, HgssPuzzles.reads(reader), area))
        assertEquals(PuzzleKind.TELEPORT_PADS, puzzle.kind)
        assertEquals(
            mapOf("teleport:0" to listOf(PuzzleTile(5, 9)), "teleport:1" to listOf(PuzzleTile(23, 41)), "teleport:2" to listOf(PuzzleTile(3, 62))),
            puzzle.teleports.associate { it.id to it.from },
        )
        assertTrue(puzzle.teleports.all { it.to == PuzzleTile(8, 83) && it.kind == TeleportKind.PAD })
    }

    @Test
    fun scriptWarpsAreDecodedFromTheTriggerScripts() {
        val world = HgssWorldRom.require()
        // scr_seq_T30GYM0101_002: Warp MAP_BLACKTHORN_GYM, 0, 8, 83, DIR_NORTH (coords 0..2).
        assertEquals((0..2).map { ScriptWarp(141, it, 8, 83, Direction.NORTH) }, world.areaOf(141)!!.scriptWarps)
        // scr_seq_D35R0102_029: the Team Rocket HQ B1F trap (16,28) -> Warp MAP_TEAM_ROCKET_HEADQUARTERS_B1F, 0, 50, 4, DIR_WEST.
        assertEquals(listOf(ScriptWarp(247, 21, 50, 4, Direction.WEST)), world.areaOf(247)!!.scriptWarps)
        // Triggers that don't warp (Azalea cart stations, Goldenrod tunnel rival) are not script warps.
        assertEquals(emptyList(), world.areaOf(HgssPuzzles.AZALEA_GYM)!!.scriptWarps)
        assertEquals(emptyList(), world.areaOf(HgssPuzzles.GOLDENROD_TUNNEL_B2F)!!.scriptWarps)
    }

    @Test
    fun azaleaStationTriggersMatchTheZoneEvents() {
        // Coordinate events 0..11 of T23GYM0102 run AzaleaGymSpinarak 0..11.
        val triggers = HgssWorldRom.require().areaOf(HgssPuzzles.AZALEA_GYM)!!.triggers.sortedBy { it.id }
        assertEquals((0 until 12).map { HgssPuzzles.azaleaTrigger(it) }, triggers.map { PuzzleTile(it.x, it.y) })
    }
}
