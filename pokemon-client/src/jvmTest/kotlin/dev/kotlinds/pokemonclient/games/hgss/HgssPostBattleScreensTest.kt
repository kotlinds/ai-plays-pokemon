package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Post-battle screens on real HeartGold snapshots (level up of Gyarados, capture of a Spinarak, TM01 from the bag). */
class HgssPostBattleScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    private fun postBattleDecoder(name: String): Screen? {
        val memory = HgssFixtures.load(name)
        val state = HgssReader(memory, HgssVersion.HEARTGOLD_US).read()!!
        return HgssPostBattleScreens.decode(HgssMemory(memory, HgssVersion.HEARTGOLD_US), state)
    }

    private val ampharos = "mon:8dd175d1.76f3a6fb"

    /**
     * Ampharos (slot 0) and Gyarados (slot 4) fought; Ampharos got its experience first, so Task_GetExp's start slot
     * is 1 (Kenya) while Gyarados, in slot 4, levels up: the panel used to show Kenya with negative gains.
     */
    @Test
    fun levelUpPanelWaitsForAWithTheGains() {
        for (name in listOf("bt_levelup_slot4", "bt_levelup_slot4_totals")) {
            val s = assertIs<Screen.PressToContinue>(screen(name))
            assertEquals(ContinueReason.LEVEL_UP_STATS, s.reason)
            assertEquals(
                "GYARADOS Lv35: Max HP 119 (+3), Attack 105 (+4), Defense 61 (+1), Sp. Atk 43 (+1), Sp. Def 82 (+2), Speed 73 (+3)",
                s.text,
            )
        }
    }

    @Test
    fun pokedexPageAfterACapture() {
        val s = assertIs<Screen.PressToContinue>(screen("pb_dex"))
        assertEquals(ContinueReason.POKEDEX_ENTRY, s.reason)
        assertEquals("Spinarak", s.text!!.lowercase().replaceFirstChar { it.uppercase() })
    }

    @Test
    fun tmGridShowsAbleAndUnable() {
        val s = assertIs<Screen.PartyGrid>(screen("pb_tm_grid"))
        assertEquals(PartyPurpose.TEACH, s.purpose)
        assertEquals(ampharos, s.entries[0].id)
        assertEquals(listOf(true, false, true, true, false, false, true), s.entries.map { it.selectable })
        assertTrue(s.entries[0].label.endsWith("ABLE!"))
        assertTrue(s.entries[1].label.endsWith("UNABLE!"))
        assertEquals("option:cancel", s.entries[6].id)
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
    }

    @Test
    fun tmGridTopologyIsTheFieldPartyGrid() {
        val t = assertIs<Screen.PartyGrid>(screen("pb_tm_grid")).topology
        assertEquals(6, t.next(0, Button.LEFT))
        assertEquals(6, t.next(0, Button.UP))
        assertEquals(1, t.next(0, Button.RIGHT))
        assertEquals(2, t.next(0, Button.DOWN))
        assertEquals(6, t.next(5, Button.RIGHT))
        assertEquals(0, t.next(6, Button.RIGHT))
        assertEquals(5, t.next(6, Button.LEFT))
        val cancel = assertIs<Screen.PartyGrid>(screen("pb_tm_grid_cancel"))
        assertEquals(Cursor.At(6), cancel.cursor)
        assertEquals(4, cancel.topology.next(6, Button.UP), "menu opened on the left column: CANCEL UP goes to slot 4")
        assertEquals(0, cancel.topology.next(6, Button.DOWN))
    }

    @Test
    fun machinesData() {
        assertEquals(264, HgssMachines.moveOf(328)) // TM01 Focus Punch
        assertEquals(15, HgssMachines.moveOf(420)) // HM01 Cut
        assertTrue(HgssMachines.isHm(57)) // Surf
        assertFalse(HgssMachines.isHm(264))
        assertEquals(TmCompatibility.ABLE, HgssMachines.compatibility(181, false, listOf(435), 328))
        assertEquals(TmCompatibility.UNABLE, HgssMachines.compatibility(22, false, emptyList(), 328))
        assertEquals(TmCompatibility.LEARNED, HgssMachines.compatibility(181, false, listOf(264), 328))
        assertEquals(TmCompatibility.UNABLE, HgssMachines.compatibility(181, true, emptyList(), 328))
        assertNull(HgssMachines.machineOf(17))
    }

    @Test
    fun summaryForgetScreenListsMovesThenTheNewOne() {
        val s = assertIs<Screen.MoveSelect>(screen("pb_summary"))
        assertEquals(MoveContext.FORGET_SUMMARY, s.context)
        assertEquals(ampharos, s.mon.toString())
        assertEquals(264, s.newMove!!.id.value)
        assertEquals(listOf("move:435", "move:86", "move:84", "move:9", "move:264"), s.entries.map { it.id })
        assertTrue(s.entries.all { it.selectable })
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
        // A vertical ring over the 5 rows (verified live: DOWN from the new move wraps to the first move).
        assertEquals(1, s.topology.next(0, Button.DOWN))
        assertEquals(4, s.topology.next(0, Button.UP))
        assertEquals(0, s.topology.next(4, Button.DOWN))
        assertNull(s.topology.next(0, Button.RIGHT))
        assertEquals(Cursor.At(4), assertIs<Screen.MoveSelect>(screen("pb_summary_new")).cursor)
    }

    @Test
    fun summaryForgetConfirmation() {
        val s = assertIs<Screen.ContextMenu>(screen("pb_summary_confirm"))
        assertEquals(ampharos, s.owner.toString())
        assertEquals(listOf("option:forget", "option:cancel"), s.entries.map { it.id })
        assertEquals("FORGET Thunder Wave", s.entries[0].label)
        assertNotNull(s.entries[1].touch)
        assertEquals(Cursor.At(0), s.cursor)
    }

    @Test
    fun postBattleDecoderLeavesOtherScreensAlone() {
        for (name in listOf("bt_cmd_hidden", "bt_move_first", "bt_bag_menu", "bt_party_grid", "bt_nickname", "bt_learn_hidden", "ow_grass", "bt_turn_anim")) {
            assertNull(postBattleDecoder(name), name)
        }
    }

    @Test
    fun noCatchShakesOutsideACapture() {
        assertNull(HgssPostBattleScreens.catchShakes(HgssMemory(HgssFixtures.load("bt_cmd_hidden"), HgssVersion.HEARTGOLD_US)))
    }
}
