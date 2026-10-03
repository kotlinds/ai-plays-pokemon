package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Battle input screens on real HeartGold snapshots (wild battle on Route 37, 8-badge save; captured with the dev
 * bench, see the scenario in the decoder report). Party: Ampharos (lead), Fearow "Kenya", Typhlosion, Machoke
 * "Muscle", Gyarados, Swinub.
 */
class HgssBattleScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    private fun battleDecoder(name: String): Screen? {
        val memory = HgssFixtures.load(name)
        val state = HgssReader(memory, HgssVersion.HEARTGOLD_US).read()!!
        return HgssBattleScreens.decode(HgssMemory(memory, HgssVersion.HEARTGOLD_US), state)
    }

    private val ampharos = "mon:8dd175d1.76f3a6fb"
    private val gyarados = "mon:49c199cc.76f3a6fb"

    // region Command menu

    @Test
    fun commandMenuFirstTurnHasAHiddenCursorAndSemanticIds() {
        val s = assertIs<Screen.BattleCommand>(screen("bt_cmd_hidden"))
        assertEquals(BattlerRef.PLAYER_LEFT, s.actor)
        assertEquals(listOf("option:fight", "option:bag", "option:run", "option:pokemon"), s.entries.map { it.id })
        assertEquals(listOf("FIGHT", "BAG", "RUN", "POKéMON"), s.entries.map { it.label })
        assertEquals(Cursor.Hidden, s.cursor)
        assertEquals(CancelBehavior.NONE, s.cancel)
    }

    @Test
    fun commandMenuTopologyFollowsBattleInputCursorMoveMainMenu() {
        val t = assertIs<Screen.BattleCommand>(screen("bt_cmd_hidden")).topology
        // FIGHT: LEFT -> BAG, RIGHT -> POKéMON, DOWN -> BAG (column 0), UP -> nothing.
        assertEquals(1, t.next(0, Button.LEFT))
        assertEquals(3, t.next(0, Button.RIGHT))
        assertEquals(1, t.next(0, Button.DOWN))
        assertNull(t.next(0, Button.UP))
        // BAG: UP -> FIGHT, RIGHT -> RUN, no wrap.
        assertEquals(0, t.next(1, Button.UP))
        assertEquals(2, t.next(1, Button.RIGHT))
        assertNull(t.next(1, Button.LEFT))
        assertNull(t.next(1, Button.DOWN))
        // RUN: UP is blocked, LEFT -> BAG, RIGHT -> POKéMON.
        assertNull(t.next(2, Button.UP))
        assertEquals(1, t.next(2, Button.LEFT))
        assertEquals(3, t.next(2, Button.RIGHT))
        // POKéMON: UP -> FIGHT, LEFT -> RUN, no wrap.
        assertEquals(0, t.next(3, Button.UP))
        assertEquals(2, t.next(3, Button.LEFT))
        assertNull(t.next(3, Button.RIGHT))
    }

    @Test
    fun commandMenuCursorPositions() {
        assertEquals(Cursor.At(1), assertIs<Screen.BattleCommand>(screen("bt_cmd_bag")).cursor)
        assertEquals(Cursor.At(2), assertIs<Screen.BattleCommand>(screen("bt_cmd_run")).cursor)
        assertEquals(Cursor.At(3), assertIs<Screen.BattleCommand>(screen("bt_cmd_pokemon")).cursor)
    }

    @Test
    fun fightEnteredFromPokemonGoesBackDownToPokemon() {
        // UP from POKéMON keeps the column (x = 2): DOWN from FIGHT returns to POKéMON, not BAG (verified live).
        val s = assertIs<Screen.BattleCommand>(screen("bt_cmd_fight_right"))
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(3, s.topology.next(0, Button.DOWN))
        assertEquals(1, s.topology.next(0, Button.LEFT))
    }

    // endregion

    // region Move select

    @Test
    fun moveSelectListsMovesWithPpAndCancel() {
        val s = assertIs<Screen.MoveSelect>(screen("bt_move_first"))
        assertEquals(MoveContext.BATTLE, s.context)
        assertEquals(ampharos, s.mon.toString())
        assertNull(s.newMove)
        assertEquals(listOf("move:435", "move:86", "move:84", "move:9", "option:cancel"), s.entries.map { it.id })
        assertEquals("Discharge (Electric, 15/15 PP)", s.entries[0].label)
        assertTrue(s.entries.all { it.selectable })
        // The previous choice was made with buttons: the cursor is shown at once, on the first move.
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
    }

    @Test
    fun moveSelectTopologyIsTwoByTwoPlusCancel() {
        val t = assertIs<Screen.MoveSelect>(screen("bt_move_first")).topology
        assertEquals(1, t.next(0, Button.RIGHT))
        assertEquals(2, t.next(0, Button.DOWN))
        assertNull(t.next(0, Button.UP))
        assertNull(t.next(0, Button.LEFT))
        assertEquals(3, t.next(1, Button.DOWN))
        assertNull(t.next(1, Button.RIGHT))
        assertEquals(4, t.next(2, Button.DOWN))
        assertEquals(4, t.next(3, Button.DOWN))
        assertNull(t.next(4, Button.DOWN))
        assertNull(t.next(4, Button.RIGHT))
    }

    @Test
    fun moveSelectCursorOnLastMoveAndOnCancel() {
        assertEquals(Cursor.At(3), assertIs<Screen.MoveSelect>(screen("bt_move_last")).cursor)
        val s = assertIs<Screen.MoveSelect>(screen("bt_move_cancel"))
        assertEquals(Cursor.At(4), s.cursor)
        // CANCEL was entered from the right column: UP goes back there (verified live).
        assertEquals(3, s.topology.next(4, Button.UP))
    }

    @Test
    fun staleFightMenuIdDuringTheTurnIsNotAMenu() {
        // curMenuId still says FIGHT (11) while the turn plays: no input task, so it is the battle message.
        val s = assertIs<Screen.Dialogue>(screen("bt_turn_anim"))
        assertEquals(TextSource.BATTLE, s.source)
        assertTrue(s.awaiting != Awaiting.INPUT)
        assertTrue(s.text.contains("GYARADOS"))
    }

    // endregion

    // region Bag

    @Test
    fun bagMenuHasPocketsLastUsedAndCancel() {
        val s = assertIs<Screen.Bag>(screen("bt_bag_menu"))
        assertTrue(s.inBattle)
        assertEquals("HP/PP RESTORE", s.pocket)
        assertEquals(
            listOf("pocket:hp_pp_restore", "pocket:status_healers", "pocket:poke_balls", "pocket:battle_items", "option:last_used", "option:cancel"),
            s.entries.map { it.id },
        )
        assertFalse(s.entries[4].selectable, "no item used yet: LAST USED ITEM is disabled")
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
    }

    @Test
    fun bagMenuTopologyComesFromTheGameTable() {
        val t = assertIs<Screen.Bag>(screen("bt_bag_menu")).topology
        assertEquals(1, t.next(0, Button.DOWN))
        assertEquals(2, t.next(0, Button.RIGHT))
        assertNull(t.next(0, Button.UP))
        assertEquals(4, t.next(1, Button.DOWN))
        assertEquals(3, t.next(1, Button.RIGHT))
        assertEquals(5, t.next(4, Button.RIGHT))
        assertEquals(1, t.next(4, Button.UP))
        assertEquals(3, t.next(5, Button.UP))
        assertEquals(4, t.next(5, Button.LEFT))
        assertEquals(Cursor.At(5), assertIs<Screen.Bag>(screen("bt_bag_menu_cancel")).cursor)
    }

    @Test
    fun bagItemListShowsTheBallsPocket() {
        val s = assertIs<Screen.Bag>(screen("bt_bag_list"))
        assertEquals("POKé BALLS", s.pocket)
        assertEquals(0, s.page)
        assertEquals(1, s.pages)
        assertEquals(listOf("item:3", "item:2", "item:1", "slot:3", "slot:4", "slot:5", "option:cancel"), s.entries.map { it.id })
        assertEquals("Great Ball x7", s.entries[0].label)
        assertEquals(listOf(true, true, true, false, false, false, true), s.entries.map { it.selectable })
        assertEquals(Cursor.At(3), s.cursor)
        val t = s.topology
        assertEquals(1, t.next(0, Button.RIGHT))
        assertEquals(2, t.next(0, Button.DOWN))
        assertEquals(6, t.next(5, Button.DOWN))
        assertEquals(5, t.next(6, Button.UP))
        assertNull(t.next(0, Button.LEFT), "a single page: LEFT on the left column does nothing")
        assertNull(t.next(1, Button.RIGHT))
    }

    @Test
    fun bagUseScreen() {
        val s = assertIs<Screen.ContextMenu>(screen("bt_bag_use"))
        assertEquals(listOf("option:use", "option:cancel"), s.entries.map { it.id })
        assertEquals("USE Great Ball", s.entries[0].label)
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(1, s.topology.next(0, Button.RIGHT))
        assertEquals(0, s.topology.next(1, Button.LEFT))
    }

    // endregion

    // region Party

    @Test
    fun partyGridForAVoluntarySwitch() {
        val s = assertIs<Screen.PartyGrid>(screen("bt_party_grid"))
        assertEquals(PartyPurpose.BATTLE_SWITCH, s.purpose)
        assertEquals(7, s.entries.size)
        assertEquals(ampharos, s.entries[0].id)
        assertEquals("option:cancel", s.entries[6].id)
        assertFalse(s.entries[0].selectable, "the active Pokémon can't be sent out")
        assertTrue(s.entries.drop(1).all { it.selectable })
        assertTrue(s.entries[1].label.startsWith("Kenya Lv37"))
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
    }

    @Test
    fun partyGridTopologyWrapsInReadingOrder() {
        val t = assertIs<Screen.PartyGrid>(screen("bt_party_grid")).topology
        assertEquals(1, t.next(0, Button.RIGHT))
        assertEquals(6, t.next(5, Button.RIGHT))
        assertEquals(0, t.next(6, Button.RIGHT))
        assertEquals(6, t.next(0, Button.UP))
        assertEquals(2, t.next(0, Button.DOWN))
        assertEquals(1, t.next(4, Button.DOWN))
        assertEquals(3, t.next(1, Button.DOWN))
        assertEquals(Cursor.At(6), assertIs<Screen.PartyGrid>(screen("bt_party_grid_cancel")).cursor)
    }

    @Test
    fun partySelectSubmenuRefusesShiftForTheActivePokemon() {
        val s = assertIs<Screen.ContextMenu>(screen("bt_party_select"))
        assertEquals(ampharos, s.owner.toString())
        assertEquals(listOf("option:shift", "option:summary", "option:check_moves", "option:cancel"), s.entries.map { it.id })
        assertFalse(s.entries[0].selectable)
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(1, s.topology.next(0, Button.DOWN))
        assertEquals(2, s.topology.next(1, Button.RIGHT))
        assertEquals(3, s.topology.next(2, Button.RIGHT))
        assertEquals(0, s.topology.next(3, Button.UP))
    }

    @Test
    fun partyMessageWaitsForA() {
        val s = assertIs<Screen.Dialogue>(screen("bt_party_msg"))
        assertEquals(Awaiting.INPUT, s.awaiting)
        assertTrue(s.text.contains("already"))
    }

    @Test
    fun partyGridToUseAnItemAcceptsEveryPokemon() {
        val s = assertIs<Screen.PartyGrid>(screen("bt_party_item"))
        assertEquals(PartyPurpose.BATTLE_USE_ITEM, s.purpose)
        assertTrue(s.entries.all { it.selectable })
        val msg = assertIs<Screen.Dialogue>(screen("bt_party_item_msg"))
        assertEquals(Awaiting.INPUT, msg.awaiting)
    }

    // endregion

    // region Two-option prompts and capture

    @Test
    fun nicknamePromptCursorIsShownOnYes() {
        val s = assertIs<Screen.YesNo>(screen("bt_nickname"))
        assertEquals(listOf("option:yes", "option:no"), s.entries.map { it.id })
        assertTrue(s.question!!.contains("nickname"))
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(1, s.topology.next(0, Button.DOWN))
        assertNull(s.topology.next(1, Button.DOWN))
        assertEquals(CancelBehavior.CONFIRMS_LAST, s.cancel)
        assertEquals(Cursor.At(1), assertIs<Screen.YesNo>(screen("bt_nickname_no")).cursor)
    }

    @Test
    fun ballShakingIsNotAnInput() {
        val s = screen("bt_catch_shake")
        assertFalse(s is Screen.Selectable)
        assertTrue(s.awaiting != Awaiting.INPUT)
    }

    @Test
    fun forgetMovePrompts() {
        val forget = assertIs<Screen.YesNo>(screen("bt_forget_prompt"))
        assertEquals(listOf("option:forget", "option:keep"), forget.entries.map { it.id })
        assertEquals(Cursor.At(0), forget.cursor)
        val giveUp = assertIs<Screen.YesNo>(screen("bt_giveup"))
        assertEquals(listOf("option:give_up", "option:keep"), giveUp.entries.map { it.id })
        assertTrue(giveUp.entries[0].label.contains("Aqua Tail"))
        assertEquals(Cursor.Hidden, giveUp.cursor, "the learn screen was left with B: the next prompt hides its cursor")
    }

    // endregion

    // region In-battle forget-move screen

    @Test
    fun learnMoveScreenListsMovesTheNewOneAndCancel() {
        val s = assertIs<Screen.MoveSelect>(screen("bt_learn_hidden"))
        assertEquals(MoveContext.FORGET_IN_BATTLE, s.context)
        assertEquals(gyarados, s.mon.toString())
        assertEquals(401, s.newMove!!.id.value)
        assertEquals(listOf("move:127", "move:250", "move:423", "move:57", "move:401", "option:cancel"), s.entries.map { it.id })
        assertEquals(Cursor.Hidden, s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
    }

    @Test
    fun learnMoveTopologySkipsTheMaskedContestButton() {
        val t = assertIs<Screen.MoveSelect>(screen("bt_learn_hidden")).topology
        assertNull(t.next(0, Button.UP), "UP leads to the masked contest button: no move")
        assertNull(t.next(1, Button.UP))
        assertEquals(1, t.next(0, Button.RIGHT))
        assertEquals(2, t.next(0, Button.DOWN))
        assertEquals(5, t.next(3, Button.DOWN))
        assertEquals(4, t.next(2, Button.DOWN))
        assertEquals(5, t.next(4, Button.RIGHT))
        assertEquals(Cursor.At(5), assertIs<Screen.MoveSelect>(screen("bt_learn_cancel")).cursor)
    }

    @Test
    fun learnMoveConfirmation() {
        val s = assertIs<Screen.ContextMenu>(screen("bt_learn_confirm"))
        assertEquals(gyarados, s.owner.toString())
        assertEquals(listOf("option:forget", "option:cancel"), s.entries.map { it.id })
        assertEquals("FORGET Whirlpool", s.entries[0].label)
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(1, s.topology.next(0, Button.RIGHT))
        assertNull(s.topology.next(0, Button.UP))
    }

    // endregion

    // region Detection negatives

    @Test
    fun battleDecoderIgnoresScreensOutsideBattles() {
        assertNull(battleDecoder("ow_grass"))
        assertNull(battleDecoder("pb_tm_grid"))
        assertNull(battleDecoder("pb_summary"))
    }

    @Test
    fun overworldStaysOverworld() {
        assertIs<Screen.Overworld>(screen("ow_grass"))
    }

    @Test
    fun twoOptionPromptIsNotMistakenForAnotherList() {
        assertFalse(screen("bt_forget_prompt") is Screen.ListMenu)
        assertIs<Screen.YesNo>(battleDecoder("bt_nickname"))
        assertFalse(MenuKind.BATTLE_SWITCH_OR_KEEP == (screen("bt_nickname") as? Screen.ListMenu)?.kind)
    }

    // endregion
}
