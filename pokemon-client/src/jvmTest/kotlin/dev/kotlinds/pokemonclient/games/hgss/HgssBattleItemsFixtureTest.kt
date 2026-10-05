package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Double battles, the battle bag's pockets and the field "Restore which move?" list on real HeartGold snapshots
 * (8-badge save). Double battle: the Route 37 twins Tori & Til (Mareep on the left, Marill on the right as drawn on
 * both screens) against Ho-Oh (left) and Fearow "Kenya" (right).
 */
class HgssBattleItemsFixtureTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun doubleBattlePositionsMatchTheScreen() {
        val battle = state("bt_double_cmd").battle!!
        assertTrue(battle.isDouble)
        // Battler 1 (the twins' first Pokémon, Marill) is drawn on the right; battler 3 (Mareep) on the left.
        assertEquals(183, battle.battlers.single { it.ref == BattlerRef.FOE_RIGHT }.species.id.value)
        assertEquals(179, battle.battlers.single { it.ref == BattlerRef.FOE_LEFT }.species.id.value)
        assertEquals(250, battle.battlers.single { it.ref == BattlerRef.PLAYER_LEFT }.species.id.value)
        // The target screen uses the same names: foe_left is Mareep.
        val targets = assertIs<Screen.TargetSelect>(state("bt_double_target").screen)
        assertEquals("MAREEP", targets.entries.single { it.id == BattlerRef.FOE_LEFT.wire }.label)
        assertEquals("MARILL", targets.entries.single { it.id == BattlerRef.FOE_RIGHT.wire }.label)
    }

    @Test
    fun theRightPokemonChoosesWithItsOwnMovesInDoubles() {
        val state = state("bt_double_cmd_right")
        assertEquals(BattlerRef.PLAYER_RIGHT, state.battle!!.actor)
        val attack = ActionRegistry.of().available(state, ActionMode.ASSISTED).single { it.name == "attack" }
        // Kenya (Fearow): Fly, Attract, Assurance, Aerial Ace.
        assertEquals(listOf("move:19", "move:213", "move:372", "move:332"), attack.choices.getValue("move").map { it.value })
        // Both Pokémon on the field are already in battle.
        val switch = ActionRegistry.of().available(state, ActionMode.ASSISTED).single { it.name == "switch" }
        assertTrue(switch.choices.getValue("pokemon").none { it.value == "mon:00006b5e.000003e9" || it.value == "mon:59e9db62.76f3a6fb" })
    }

    @Test
    fun theBattleBagTellsWhichPocketHoldsEachItem() {
        val bag = assertIs<Screen.Bag>(state("bt_bag_menu_contents").screen)
        assertTrue(ItemId(17) in bag.pocketContents.getValue("pocket:hp_pp_restore"), "Potion")
        assertTrue(ItemId(39) in bag.pocketContents.getValue("pocket:hp_pp_restore"), "Max Ether")
        assertTrue(ItemId(27) in bag.pocketContents.getValue("pocket:status_healers"), "Full Heal")
        assertTrue(ItemId(3) in bag.pocketContents.getValue("pocket:poke_balls"), "Great Ball")
        assertTrue(ItemId(60) in bag.pocketContents.getValue("pocket:battle_items"), "X Accuracy")
    }

    @Test
    fun restoreWhichMoveListsTheMovesById() {
        val list = assertIs<Screen.ListMenu>(state("pb_restore_move").screen)
        assertEquals(listOf("move:435", "move:86", "move:84", "move:9", "option:cancel"), list.entries.map { it.id })
        assertEquals("Thunder Wave (16/20 PP)", list.entries[1].label)
    }

    @Test
    fun anItemMessageWaitsForAOnlyOnceItsPrinterWaits() {
        val printing = assertIs<Screen.Dialogue>(state("pb_item_msg_printing").screen)
        assertEquals(Awaiting.TEXT_PRINTING, printing.awaiting)
        val waiting = assertIs<Screen.Dialogue>(state("pb_item_msg_waiting").screen)
        assertEquals(Awaiting.INPUT, waiting.awaiting)
        assertTrue(waiting.text.contains("AMPHAROS"))
    }
}
