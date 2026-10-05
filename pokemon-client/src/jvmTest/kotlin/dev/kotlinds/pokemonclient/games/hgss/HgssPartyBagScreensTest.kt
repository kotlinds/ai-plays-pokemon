package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.console.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Field party menu and bag screens ([HgssPartyBagScreens]) on real HeartGold (US) snapshots, captured with the
 * dev bench (8-badge save, Ecruteak Pokémon Center). Every topology asserted here was also checked live, one tap at
 * a time (the bench's `walk` command): the cursor landed where the topology predicted.
 *
 * Party of the save: 0 AMPHAROS, 1 Kenya (FEAROW, Fly), 2 TYPHLOSION (Cut), 3 Muscle (MACHOKE, Strength),
 * 4 GYARADOS (Waterfall, Whirlpool, Surf), 5 SWINUB (holds an Exp. Share). `pb_grid3` has only AMPHAROS, Kenya and
 * SWINUB (the others deposited in the PC).
 */
class HgssPartyBagScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    private fun Screen.Selectable.ids() = entries.map { it.id }

    private fun Screen.Selectable.moves(from: Int) =
        listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT).associateWith { topology.next(from, it) }

    private val amph = "mon:8dd175d1.76f3a6fb"
    private val kenya = "mon:00006b5e.000003e9"
    private val typhlosion = "mon:52d32ba1.76f3a6fb"
    private val muscle = "mon:00002310.00009254"
    private val gyarados = "mon:49c199cc.76f3a6fb"
    private val swinub = "mon:033ef671.76f3a6fb"

    // ---------------------------------------------------------------------------------------------------------------
    // Party grid
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun fieldGridHasTheSixMonsThenCancel() {
        val grid = assertIs<Screen.PartyGrid>(screen("pb_grid6"))
        assertEquals(PartyPurpose.FIELD, grid.purpose)
        assertEquals(listOf(amph, kenya, typhlosion, muscle, gyarados, swinub, "option:cancel"), grid.ids())
        assertEquals("AMPHAROS Lv34 110/110", grid.entries[0].label)
        assertEquals("Kenya Lv37 102/102", grid.entries[1].label)
        assertTrue(grid.entries.all { it.selectable })
        assertEquals(Cursor.At(0), grid.cursor)
        assertEquals(CancelBehavior.CLOSES, grid.cancel)
    }

    @Test
    fun fieldGridTopologyWithSixMons() {
        val grid = assertIs<Screen.PartyGrid>(screen("pb_grid6"))
        // Two columns (0 2 4 | 1 3 5), CANCEL = 6. LEFT / RIGHT walk the reading order with wrap through CANCEL.
        assertEquals(mapOf(Button.UP to 6, Button.DOWN to 2, Button.LEFT to 6, Button.RIGHT to 1), grid.moves(0))
        assertEquals(mapOf(Button.UP to 6, Button.DOWN to 3, Button.LEFT to 0, Button.RIGHT to 2), grid.moves(1))
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 4, Button.LEFT to 1, Button.RIGHT to 3), grid.moves(2))
        assertEquals(mapOf(Button.UP to 3, Button.DOWN to 6, Button.LEFT to 4, Button.RIGHT to 6), grid.moves(5))
        // From CANCEL (menu opened on slot 0, left column): UP = bottom-left 4, DOWN = top-left 0.
        assertEquals(mapOf(Button.UP to 4, Button.DOWN to 0, Button.LEFT to 5, Button.RIGHT to 0), grid.moves(6))
        assertNull(grid.topology.next(0, Button.A))
    }

    @Test
    fun cursorOnCancel() {
        val grid = assertIs<Screen.PartyGrid>(screen("pb_grid6_cancel"))
        assertEquals(Cursor.At(6), grid.cursor)
        assertEquals("option:cancel", grid.entries[6].id)
    }

    @Test
    fun emptySlotsAreSkipped() {
        val grid = assertIs<Screen.PartyGrid>(screen("pb_grid3"))
        assertEquals(listOf(amph, kenya, swinub, "slot:3", "slot:4", "slot:5", "option:cancel"), grid.ids())
        assertEquals(listOf(true, true, true, false, false, false, true), grid.entries.map { it.selectable })
        assertEquals(Cursor.At(1), grid.cursor)
        // DOWN from 1 jumps over the empty slot 3 and 5 to CANCEL; RIGHT from 2 too; from CANCEL LEFT skips to 2.
        assertEquals(mapOf(Button.UP to 6, Button.DOWN to 6, Button.LEFT to 0, Button.RIGHT to 2), grid.moves(1))
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 6, Button.LEFT to 1, Button.RIGHT to 6), grid.moves(2))
        assertEquals(mapOf(Button.UP to 2, Button.DOWN to 0, Button.LEFT to 2, Button.RIGHT to 0), grid.moves(6))
        // Empty slots are never reached.
        assertNull(grid.topology.next(3, Button.UP))
    }

    @Test
    fun cancelGoesBackToTheColumnTheMenuOpenedOn() {
        // Reopened on slot 1 (after SUMMARY): from CANCEL, UP = bottom-right 5, DOWN = top-right 1.
        val grid = assertIs<Screen.PartyGrid>(screen("pb_grid_odd_cancel"))
        assertEquals(Cursor.At(6), grid.cursor)
        assertEquals(5, grid.topology.next(6, Button.UP))
        assertEquals(1, grid.topology.next(6, Button.DOWN))
    }

    @Test
    fun gridTopologyFromTheDecomp() {
        // 2 mons, menu opened on slot 0: CANCEL UP / DOWN choose the first existing slot in the left column order.
        val two = HgssPartyMenuScreen.gridTopology(booleanArrayOf(true, true, false, false, false, false), false)
        assertEquals(0, two.next(6, Button.UP))
        assertEquals(0, two.next(6, Button.DOWN))
        assertEquals(1, two.next(6, Button.LEFT))
        assertEquals(6, two.next(1, Button.RIGHT))
        assertEquals(6, two.next(0, Button.DOWN))
        // 1 mon: everything goes between 0 and CANCEL.
        val one = HgssPartyMenuScreen.gridTopology(booleanArrayOf(true, false, false, false, false, false), true)
        assertEquals(6, one.next(0, Button.RIGHT))
        assertEquals(0, one.next(6, Button.UP))
        assertNull(one.next(0, Button.A))
    }

    @Test
    fun switchModeMarksTheMovedMon() {
        val grid = assertIs<Screen.PartyGrid>(screen("pb_switch"))
        assertEquals(Cursor.At(1), grid.cursor)
        assertEquals(PartyPurpose.SWITCH, grid.purpose)
        assertEquals(kenya, grid.entries[1].id)
        assertEquals(kenya, grid.moving.toString())
    }

    @Test
    fun purposesFromTheBag() {
        val give = assertIs<Screen.PartyGrid>(screen("pb_give"))
        assertEquals(PartyPurpose.GIVE_ITEM, give.purpose)
        assertEquals(swinub, give.entries[5].id)
        val use = assertIs<Screen.PartyGrid>(screen("pb_use_item"))
        assertEquals(PartyPurpose.USE_ITEM, use.purpose)
        val teach = assertIs<Screen.PartyGrid>(screen("pb_teach"))
        assertEquals(PartyPurpose.TEACH, teach.purpose)
        assertEquals(Cursor.At(0), teach.cursor)
        assertEquals(6, teach.topology.next(0, Button.LEFT))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Context menu, submenu, messages, yes/no
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun contextMenuWithoutFieldMoves() {
        val menu = assertIs<Screen.ContextMenu>(screen("pb_ctx4"))
        assertEquals(amph, menu.owner.toString())
        assertEquals(listOf("option:summary", "option:switch", "option:item", "option:quit"), menu.ids())
        assertEquals(listOf("SUMMARY", "SWITCH", "ITEM", "QUIT"), menu.entries.map { it.label })
        assertEquals(Cursor.At(0), menu.cursor)
        // A vertical ring; LEFT / RIGHT do nothing.
        assertEquals(mapOf(Button.UP to 3, Button.DOWN to 1, Button.LEFT to null, Button.RIGHT to null), menu.moves(0))
        assertEquals(0, menu.topology.next(3, Button.DOWN))
    }

    @Test
    fun contextMenuWithFly() {
        val menu = assertIs<Screen.ContextMenu>(screen("pb_ctx5_fly"))
        assertEquals(kenya, menu.owner.toString())
        assertEquals(listOf("option:summary", "option:switch", "option:item", "option:quit", "fieldmove:fly"), menu.ids())
        assertEquals(Cursor.At(4), menu.cursor)
        // The field move column: UP / DOWN stay, LEFT / RIGHT go back to SUMMARY; 0-2 go to it sideways, QUIT never.
        assertEquals(mapOf(Button.UP to null, Button.DOWN to null, Button.LEFT to 0, Button.RIGHT to 0), menu.moves(4))
        assertEquals(4, menu.topology.next(2, Button.RIGHT))
        assertNull(menu.topology.next(3, Button.LEFT))
    }

    @Test
    fun contextMenuWithThreeFieldMoves() {
        val menu = assertIs<Screen.ContextMenu>(screen("pb_ctx7"))
        assertEquals(gyarados, menu.owner.toString())
        assertEquals(
            listOf("option:summary", "option:switch", "option:item", "option:quit", "fieldmove:waterfall", "fieldmove:whirlpool", "fieldmove:surf"),
            menu.ids(),
        )
        assertEquals(Cursor.At(5), menu.cursor)
        // Left column is a ring 4 -> 5 -> 6 -> 4; each row crosses to the same row on the right.
        assertEquals(mapOf(Button.UP to 4, Button.DOWN to 6, Button.LEFT to 1, Button.RIGHT to 1), menu.moves(5))
        assertEquals(4, menu.topology.next(6, Button.DOWN))
        assertEquals(6, menu.topology.next(2, Button.LEFT))
    }

    @Test
    fun contextMenuTopologiesFromTheDecomp() {
        val six = HgssPartyMenuScreen.contextMenuTopology(6)
        assertEquals(5, six.next(2, Button.RIGHT))
        assertEquals(5, six.next(4, Button.UP))
        assertEquals(1, six.next(5, Button.LEFT))
        val eight = HgssPartyMenuScreen.contextMenuTopology(8)
        assertEquals(4, eight.next(7, Button.DOWN))
        assertEquals(2, eight.next(7, Button.RIGHT))
        val egg = HgssPartyMenuScreen.contextMenuTopology(3)
        assertEquals(2, egg.next(0, Button.UP))
        assertNull(egg.next(0, Button.RIGHT))
    }

    @Test
    fun itemSubmenu() {
        val menu = assertIs<Screen.ContextMenu>(screen("pb_sub_item"))
        assertEquals(kenya, menu.owner.toString())
        assertEquals(listOf("option:give", "option:take", "option:quit"), menu.ids())
        assertEquals(Cursor.At(1), menu.cursor)
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 2, Button.LEFT to null, Button.RIGHT to null), menu.moves(1))
        assertEquals(0, menu.topology.next(2, Button.DOWN))
    }

    @Test
    fun messagesInsideThePartyMenu() {
        val fly = assertIs<Screen.Dialogue>(screen("pb_msg_fly"))
        assertEquals("You can’t use that here.", fly.text)
        assertEquals(Awaiting.INPUT, fly.awaiting)
        val take = assertIs<Screen.Dialogue>(screen("pb_msg_take"))
        assertEquals("Received the Exp. Share\nfrom SWINUB.", take.text)
    }

    @Test
    fun switchItemsYesNo() {
        val yesNo = assertIs<Screen.YesNo>(screen("pb_yesno_switch_items"))
        assertEquals(listOf("option:yes", "option:no"), yesNo.ids())
        assertEquals(Cursor.At(1), yesNo.cursor)
        assertTrue(yesNo.question!!.startsWith("SWINUB is already holding"))
        assertEquals(0, yesNo.topology.next(1, Button.DOWN))
        assertEquals(0, yesNo.topology.next(1, Button.UP))
        assertNull(yesNo.topology.next(1, Button.LEFT))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bag
    // ---------------------------------------------------------------------------------------------------------------

    private val tabs = listOf(
        "pocket:items", "pocket:medicine", "pocket:balls", "pocket:tms_hms",
        "pocket:berries", "pocket:mail", "pocket:battle_items", "pocket:key_items",
    )

    @Test
    fun bagItemsPocket() {
        val bag = assertIs<Screen.Bag>(screen("pb_bag_items"))
        assertEquals("items", bag.pocket)
        assertEquals(8, bag.pockets.size)
        assertEquals(0, bag.page)
        assertEquals(1, bag.pages)
        assertFalse(bag.inBattle)
        assertEquals(
            tabs + listOf("item:239", "item:215", "slot:2", "slot:3", "slot:4", "slot:5", "page:prev", "page:next", "option:cancel"),
            bag.ids(),
        )
        assertEquals("Miracle Seed x1", bag.entries[8].label)
        assertFalse(bag.entries[10].selectable)
        // One page only: the page arrows are refused; they are touch-only.
        assertFalse(bag.entries[14].selectable)
        assertEquals(TouchPoint(20, 180), bag.entries[14].touch)
        assertEquals(Cursor.At(8), bag.cursor)
        // Items: UP from the top row = the open pocket's tab; LEFT / RIGHT at the edges turn pages (no cursor move).
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 10, Button.LEFT to null, Button.RIGHT to 9), bag.moves(8))
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 11, Button.LEFT to 8, Button.RIGHT to null), bag.moves(9))
        assertEquals(mapOf(Button.UP to 10, Button.DOWN to 0, Button.LEFT to null, Button.RIGHT to 13), bag.moves(12))
        assertEquals(mapOf(Button.UP to 11, Button.DOWN to 16, Button.LEFT to 12, Button.RIGHT to null), bag.moves(13))
    }

    @Test
    fun bagTabsTopology() {
        val bag = assertIs<Screen.Bag>(screen("pb_bag_tab"))
        assertEquals(Cursor.At(0), bag.cursor)
        // Tabs: LEFT / RIGHT with wrap; UP goes to the bottom row (left tabs) or CANCEL (right tabs).
        assertEquals(mapOf(Button.UP to 12, Button.DOWN to 8, Button.LEFT to 7, Button.RIGHT to 1), bag.moves(0))
        assertEquals(mapOf(Button.UP to 16, Button.DOWN to 9, Button.LEFT to 6, Button.RIGHT to 0), bag.moves(7))
        // L / R open the previous / next pocket: the cursor follows only on the tabs.
        assertEquals(7, bag.topology.next(0, Button.L))
        assertEquals(1, bag.topology.next(0, Button.R))
        assertNull(bag.topology.next(8, Button.R))
    }

    @Test
    fun bagCancel() {
        val bag = assertIs<Screen.Bag>(screen("pb_bag_cancel"))
        assertEquals(Cursor.At(16), bag.cursor)
        assertEquals(mapOf(Button.UP to 13, Button.DOWN to 0, Button.LEFT to null, Button.RIGHT to null), bag.moves(16))
        assertEquals(CancelBehavior.CLOSES, bag.cancel)
    }

    @Test
    fun bagPages() {
        val p0 = assertIs<Screen.Bag>(screen("pb_bag_key_p0"))
        assertEquals("key_items", p0.pocket)
        assertEquals(0, p0.page)
        assertEquals(2, p0.pages)
        assertEquals(listOf("item:468", "item:465", "item:477", "item:437", "item:470", "item:446"), p0.ids().subList(8, 14))
        assertTrue(p0.entries[15].selectable)
        // UP from the items goes to the key items tab (7).
        assertEquals(7, p0.topology.next(8, Button.UP))
        assertEquals(7, p0.topology.next(12, Button.DOWN))
        val p1 = assertIs<Screen.Bag>(screen("pb_bag_key_p1"))
        assertEquals(1, p1.page)
        assertEquals(Cursor.At(9), p1.cursor)
        assertEquals(listOf("item:450", "item:476", "item:475", "item:483", "slot:4", "slot:5"), p1.ids().subList(8, 14))
        // RIGHT on the last page wraps to the first page, the cursor staying on 9.
        val wrapped = assertIs<Screen.Bag>(screen("pb_bag_key_wrap"))
        assertEquals(0, wrapped.page)
        assertEquals(Cursor.At(9), wrapped.cursor)
        assertIs<Screen.Animation>(screen("pb_bag_page_anim"))
    }

    @Test
    fun bagTmPocket() {
        val bag = assertIs<Screen.Bag>(screen("pb_bag_tms"))
        assertEquals("tms_hms", bag.pocket)
        assertEquals(3, bag.pages)
        assertEquals("item:328", bag.entries[8].id)
        assertEquals("TM01 x1", bag.entries[8].label)
        assertEquals(3, bag.topology.next(8, Button.UP))
    }

    @Test
    fun itemActionMenuOfAHeldItem() {
        val menu = assertIs<Screen.ContextMenu>(screen("pb_act_seed"))
        assertNull(menu.owner)
        // Miracle Seed: no USE (slot 0 drawn empty, A does nothing there), TRASH, GIVE, MOVE, CANCEL.
        assertEquals(listOf("slot:0", "option:trash", "option:give", "option:move", "option:cancel"), menu.ids())
        assertEquals(listOf("", "TRASH", "GIVE", "MOVE", "CANCEL"), menu.entries.map { it.label })
        assertFalse(menu.entries[0].selectable)
        assertEquals(Cursor.At(0), menu.cursor)
        // Row 1 = 0 <-> 1, row 2 = ring 2 -> 3 -> 4 (CANCEL) -> 2; UP / DOWN toggle 0 <-> 2, 1 <-> 3; CANCEL stays.
        assertEquals(mapOf(Button.UP to 2, Button.DOWN to 2, Button.LEFT to 1, Button.RIGHT to 1), menu.moves(0))
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to 0, Button.LEFT to 4, Button.RIGHT to 3), menu.moves(2))
        assertEquals(mapOf(Button.UP to null, Button.DOWN to null, Button.LEFT to 3, Button.RIGHT to 2), menu.moves(4))
    }

    @Test
    fun itemActionMenuOfAKeyItemAndAMedicine() {
        val bike = assertIs<Screen.ContextMenu>(screen("pb_act_bike"))
        assertEquals(listOf("option:use", "option:register", "slot:2", "option:move", "option:cancel"), bike.ids())
        assertEquals(Cursor.At(1), bike.cursor)
        val potion = assertIs<Screen.ContextMenu>(screen("pb_act_potion_cancel"))
        assertEquals(listOf("option:use", "option:trash", "option:give", "option:move", "option:cancel"), potion.ids())
        assertEquals(Cursor.At(4), potion.cursor)
    }

    @Test
    fun bagMessagesAndTmQuestion() {
        val booted = assertIs<Screen.Dialogue>(screen("pb_bag_tm_msg"))
        assertEquals("Booted up a TM.", booted.text)
        val question = assertIs<Screen.YesNo>(screen("pb_bag_tm_yesno"))
        assertEquals("It contained\nFocus Punch.\nTeach Focus Punch\nto a Pokémon?", question.question)
        assertEquals(Cursor.At(0), question.cursor)
        val error = assertIs<Screen.Dialogue>(screen("pb_bag_error"))
        assertTrue(error.text.startsWith("Oak’s words echoed..."))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Detection negatives
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun otherScreensAreNotPartyOrBag() {
        for (name in listOf("startmenu1", "nb1", "battle1", "yesno1", "mailbox1", "starterapp1")) {
            val decoded = HgssPartyBagScreens.decode(
                HgssMemory(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US),
                HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).read()!!,
            )
            assertNull(decoded, name)
        }
    }

    @Test
    fun partyAndBagAreNotTheOtherOne() {
        assertIs<Screen.PartyGrid>(screen("pb_grid6"))
        assertFalse(screen("pb_bag_items") is Screen.PartyGrid)
        assertFalse(screen("pb_grid6") is Screen.Bag)
    }

    @Test
    fun theRareCandyStatsPanelsWaitForAPress() {
        for ((name, panel) in listOf("pb_rarecandy_gains" to "gains panel", "pb_rarecandy_totals" to "totals panel")) {
            val screen = assertIs<Screen.PressToContinue>(screen(name), name)
            assertEquals(dev.kotlinds.pokemonclient.state.ContinueReason.LEVEL_UP_STATS, screen.reason)
            kotlin.test.assertTrue(screen.text.orEmpty().startsWith("HO-OH grew to Lv47 ($panel): Max HP 167"), screen.text)
        }
    }

    @Test
    fun theFieldLearnMoveQuestionSaysWhatYesMeansAndWhichMove() {
        val screen = assertIs<Screen.YesNo>(screen("pb_rarecandy_learn"))
        val offer = kotlin.test.assertNotNull(screen.learning)
        assertEquals(dev.kotlinds.pokemonclient.state.LearnQuestion.FORGET_A_MOVE, offer.question)
        assertEquals("PILOSWINE", offer.monName)
        kotlin.test.assertTrue(offer.move.id.value > 0)
        assertEquals(listOf("option:yes", "option:no"), screen.entries.map { it.id })
    }
}
