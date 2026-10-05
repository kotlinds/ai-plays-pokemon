package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HgssKeyboardPcShopScreens] on real HeartGold (USA) RAM captured in Ecruteak City (Pokémon Center PC, Poké Mart,
 * start menu SAVE, box rename keyboard). Topology expectations were checked live: each press was made in the
 * emulator and the cursor read back.
 */
class HgssKeyboardPcShopScreensTest {

    private val version = HgssVersion.HEARTGOLD_US

    private fun decode(name: String): Screen? {
        val memory = HgssFixtures.load(name)
        val state = assertNotNull(HgssReader(memory, version).read())
        return HgssKeyboardPcShopScreens.decode(HgssMemory(memory, version), state)
    }

    private inline fun <reified T : Screen> screen(name: String): T = assertIs<T>(decode(name), name)

    private fun Screen.Selectable.ids() = entries.map { it.id }
    private fun Screen.Selectable.cursorIndex() = assertIs<Cursor.At>(cursor).index
    private fun Screen.Selectable.next(from: Int, button: Button) = topology.next(from, button)

    // region Naming keyboard

    @Test
    fun keyboardUpperPageWhenRenamingABox() {
        val kb = screen<Screen.Keyboard>("kb_upper")
        assertEquals("box", kb.purpose)
        assertEquals("upper", kb.page)
        assertEquals("BOX 1", kb.buffer)
        assertEquals(8, kb.maxLength)
        assertEquals(78, kb.entries.size)
        assertEquals(13, kb.cursorIndex()) // (0, 1) = "A"
        assertEquals(
            listOf("page:upper", "page:upper", "page:lower", "page:lower", "page:others", "page:others", "key:skip", "key:skip",
                "option:back", "option:back", "option:back", "option:ok", "option:ok"),
            kb.ids().take(13),
        )
        assertFalse(kb.entries[6].selectable)
        assertFalse(kb.entries[7].selectable)
        assertEquals("key:A", kb.entries[13].id)
        assertEquals("key:J", kb.entries[22].id)
        assertEquals("key: ", kb.entries[23].id)
        assertEquals("key:’", kb.entries[37].id)
        assertEquals("key:♀", kb.entries[51].id)
        assertEquals((0..9).map { "key:$it" }, kb.ids().subList(65, 75))
        assertTrue(kb.entries.filter { it.id.startsWith("key:") && it.id != "key:skip" }.all { it.selectable })
    }

    @Test
    fun keyboardTopologyWrapsAndCrossesMultiCellButtons() {
        val kb = screen<Screen.Keyboard>("kb_upper")
        assertEquals(0, kb.next(13, Button.UP)) // row 1 -> home row, same x
        assertEquals(65, kb.next(0, Button.UP)) // home row -> row 5 (vertical wrap)
        assertEquals(13, kb.next(0, Button.DOWN))
        assertEquals(25, kb.next(13, Button.LEFT)) // horizontal wrap on a character row
        assertEquals(14, kb.next(13, Button.RIGHT))
        assertEquals(2, kb.next(0, Button.RIGHT)) // UPPER spans x0-1: one press reaches lower
        assertEquals(1, kb.next(2, Button.LEFT)) // lower -> the right cell of UPPER
        assertEquals(8, kb.next(4, Button.RIGHT)) // Others -> skips SKIP x6-7 -> BACK
        assertEquals(5, kb.next(8, Button.LEFT)) // BACK -> skips SKIP -> Others
        assertEquals(11, kb.next(8, Button.RIGHT)) // BACK spans x8-10
        assertEquals(10, kb.next(12, Button.LEFT)) // OK spans x11-12
        assertEquals(0, kb.next(12, Button.RIGHT)) // OK -> wraps to UPPER
        assertEquals(12, kb.next(0, Button.LEFT))
        assertEquals(71, kb.next(19, Button.UP)) // x6 row 1: the cell above is SKIP -> keeps going up to row 5
        assertEquals(19, kb.next(71, Button.DOWN)) // and back down through the SKIP cell
        assertEquals(21, kb.next(8, Button.DOWN))
        assertEquals(73, kb.next(8, Button.UP))
        assertEquals(8, kb.next(73, Button.DOWN))
    }

    @Test
    fun keyboardCursorOnOkAfterStart() {
        val kb = screen<Screen.Keyboard>("kb_ok")
        assertEquals(12, kb.cursorIndex())
        assertEquals("option:ok", kb.entries[kb.cursorIndex()].id)
        assertEquals(77, kb.next(12, Button.UP))
        assertEquals(25, kb.next(12, Button.DOWN))
    }

    @Test
    fun keyboardBufferFollowsDeletionAndTyping() {
        assertEquals("", screen<Screen.Keyboard>("kb_empty").buffer)
        assertEquals("A", screen<Screen.Keyboard>("kb_typed").buffer)
        assertEquals("A,", screen<Screen.Keyboard>("kb_touched").buffer)
    }

    @Test
    fun keyboardPageSlideIsAnAnimation() {
        assertEquals(Screen.Animation(AnimationKind.TRANSITION), decode("kb_transition"))
    }

    @Test
    fun keyboardLowerAndOthersPagesReadTheLiveLayout() {
        val lower = screen<Screen.Keyboard>("kb_lower")
        assertEquals("lower", lower.page)
        assertEquals("key:a", lower.entries[13].id)
        assertEquals("key:z", lower.entries[44].id)
        assertEquals("page:upper", lower.entries[0].id)
        val others = screen<Screen.Keyboard>("kb_others")
        assertEquals("others", others.page)
        assertEquals(listOf("key:,", "key:.", "key::", "key:;", "key:!", "key:?"), others.ids().subList(13, 19))
        assertEquals("key:@", others.entries[42].id)
        assertEquals(13, others.cursorIndex())
        assertEquals(0, screen<Screen.Keyboard>("kb_others_home").cursorIndex())
    }

    @Test
    fun numberPadTopologySkipsTheEightSkipCells() {
        // Type UNK4 (naming_screen.c:204): row 0 = SKIP x8, BACK x3, OK x2; rows 1-2 digits, then spaces.
        val k = HgssKeyboardPcShopAddresses
        val cells = IntArray(78) { 0x1DE }
        for (x in 0 until 8) cells[x] = k.KEY_SKIP
        for (x in 8 until 11) cells[x] = k.KEY_BACK
        for (x in 11 until 13) cells[x] = k.KEY_OK
        for (d in 0 until 5) { cells[13 + d] = 0x121 + d; cells[26 + d] = 0x126 + d }
        val topology = HgssNamingKeyboard.topology(cells)
        assertEquals(12, topology.next(8, Button.LEFT)) // skips x7..x0, wraps to OK
        assertEquals(8, topology.next(12, Button.RIGHT)) // wraps, skips x0..x7
        assertEquals(68, topology.next(16, Button.UP)) // x3: row 0 is SKIP -> row 5
    }

    // endregion

    // region PC storage

    @Test
    fun moveModeBoxGrid() {
        val box = screen<Screen.PcBox>("pc_move")
        assertEquals(0, box.box)
        assertEquals("BOX 1", box.boxName)
        assertEquals(36, box.entries.size)
        assertEquals(0, box.cursorIndex())
        assertEquals("mon:c50a0956.76f3a6fb", box.entries[0].id)
        assertEquals("mon:5ddade1d.76f3a6fb", box.entries[1].id)
        assertEquals("slot:2", box.entries[2].id)
        assertTrue(box.entries[2].selectable)
        assertEquals(5, box.entries.take(30).count { it.id.startsWith("mon:") })
        assertEquals(listOf("option:box", "option:prev_box", "option:next_box", "option:party", "option:move", "option:return"), box.ids().drop(30))
        // The box arrows have no d-pad link: touch only.
        assertEquals(TouchPoint(11, 27), box.entries[31].touch)
        assertEquals(TouchPoint(155, 27), box.entries[32].touch)
        assertNull(box.entries[33].touch)
        assertTrue(box.entries.none { it.dangerous })
    }

    @Test
    fun moveModeTopology() {
        val box = screen<Screen.PcBox>("pc_move")
        assertEquals(30, box.next(0, Button.UP)) // top row -> title
        assertEquals(6, box.next(0, Button.DOWN))
        assertEquals(5, box.next(0, Button.LEFT)) // rows wrap
        assertEquals(0, box.next(5, Button.RIGHT))
        assertEquals(33, box.next(24, Button.DOWN)) // bottom row -> PARTY PKMN (0xA1, nothing remembered)
        assertEquals(33, box.next(29, Button.DOWN))
        assertEquals(0, box.next(30, Button.DOWN)) // title -> remembered slot, else slot 0
        assertEquals(33, box.next(30, Button.UP))
        assertNull(box.next(30, Button.LEFT)) // the app changes the box instead
        assertNull(box.next(30, Button.RIGHT))
        assertEquals(34, box.next(33, Button.RIGHT))
        assertEquals(35, box.next(34, Button.RIGHT))
        assertNull(box.next(35, Button.RIGHT))
        assertEquals(33, box.next(34, Button.LEFT))
        assertEquals(30, box.next(35, Button.DOWN))
        assertEquals(24, box.next(35, Button.UP)) // 0x98: nothing remembered -> slot 24
        assertNull(box.next(31, Button.UP)) // arrows: no d-pad links
        assertNull(box.next(32, Button.DOWN))
    }

    @Test
    fun moveModeTitleAndBoxChange() {
        assertEquals(30, screen<Screen.PcBox>("pc_move_title").cursorIndex())
        val last = screen<Screen.PcBox>("pc_move_box18")
        assertEquals(17, last.box) // LEFT on the title of box 1 wraps to box 18
        assertEquals("BOX 18", last.boxName)
        assertTrue(last.entries.take(30).all { it.id.startsWith("slot:") })
    }

    @Test
    fun moveModeRememberedIndex() {
        // DOWN from slot 25 reached PARTY PKMN and remembered 25: UP goes back there, not to 24.
        val party = screen<Screen.PcBox>("pc_move_party_btn")
        assertEquals(33, party.cursorIndex())
        assertEquals(25, party.next(33, Button.UP))
        assertEquals(24, party.next(34, Button.UP)) // remembered only for the current cursor
        val ret = screen<Screen.PcBox>("pc_move_return")
        assertEquals(35, ret.cursorIndex())
        assertEquals(24, ret.next(35, Button.UP))
        assertEquals(21, screen<Screen.PcBox>("pc_move_back_up").cursorIndex())
    }

    @Test
    fun moveModeContextMenu() {
        val menu = screen<Screen.ContextMenu>("pc_menu")
        assertEquals("mon:c50a0956.76f3a6fb", menu.owner.toString())
        assertEquals(
            listOf("option:move", "option:summary", "option:held_items", "option:marking", "option:release", "option:cancel"),
            menu.ids(),
        )
        assertEquals(listOf("option:release"), menu.entries.filter { it.dangerous }.map { it.id })
        assertEquals(0, menu.cursorIndex())
        assertEquals(1, menu.next(0, Button.DOWN))
        assertEquals(5, menu.next(0, Button.UP)) // wraps through EXIT
        assertEquals(0, menu.next(5, Button.DOWN))
        assertNull(menu.next(2, Button.LEFT))
        assertNull(menu.next(2, Button.RIGHT))
        assertEquals(5, screen<Screen.ContextMenu>("pc_menu_exit").cursorIndex())
    }

    @Test
    fun releasePromptDefaultsToNoAndYesIsDangerous() {
        val no = screen<Screen.YesNo>("pc_release_no")
        assertEquals(listOf("option:yes", "option:no"), no.ids())
        assertEquals(1, no.cursorIndex())
        assertTrue(no.entries[0].dangerous)
        assertFalse(no.entries[1].dangerous)
        assertEquals(TouchPoint(224, 112), no.entries[0].touch)
        assertEquals(TouchPoint(224, 144), no.entries[1].touch)
        assertEquals(0, no.next(1, Button.UP))
        assertEquals(0, no.next(1, Button.DOWN))
        assertEquals(0, screen<Screen.YesNo>("pc_release_yes").cursorIndex())
    }

    @Test
    fun moveModePartyPanel() {
        val panel = screen<Screen.PcBox>("pc_move_party")
        assertEquals(8, panel.entries.size)
        assertTrue(panel.entries.take(6).all { it.id.startsWith("mon:") })
        assertEquals("mon:00006b5e.000003e9", panel.entries[1].id) // a traded Pokémon: another trainer id
        assertEquals(listOf("option:switch", "option:return"), panel.ids().drop(6))
        assertEquals(0, panel.cursorIndex())
        assertEquals(7, panel.next(0, Button.UP))
        assertEquals(2, panel.next(0, Button.DOWN))
        assertEquals(1, panel.next(0, Button.RIGHT))
        assertNull(panel.next(0, Button.LEFT))
    }

    @Test
    fun depositPartyAndMenu() {
        val deposit = screen<Screen.PcBox>("pc_deposit")
        assertEquals(PcMode.DEPOSIT, deposit.mode)
        assertEquals(7, deposit.entries.size)
        assertEquals("option:return", deposit.entries[6].id)
        assertEquals(6, deposit.next(0, Button.UP))
        assertEquals(6, deposit.next(5, Button.DOWN))
        assertEquals(0, deposit.next(6, Button.DOWN))
        val menu = screen<Screen.ContextMenu>("pc_deposit_menu")
        assertEquals(deposit.entries[0].id, menu.owner.toString()) // opened on party slot 0 (selected slot 30)
        assertEquals(listOf("option:deposit", "option:summary", "option:marking", "option:release", "option:cancel"), menu.ids())
        assertEquals(4, menu.next(0, Button.UP))
    }

    @Test
    fun withdrawBoxAndMenu() {
        val withdraw = screen<Screen.PcBox>("pc_withdraw")
        assertEquals(PcMode.WITHDRAW, withdraw.mode)
        assertEquals(34, withdraw.entries.size)
        assertEquals("option:return", withdraw.entries[33].id)
        assertEquals(33, withdraw.next(24, Button.DOWN))
        val menu = screen<Screen.ContextMenu>("pc_withdraw_menu")
        assertEquals(listOf("option:withdraw", "option:summary", "option:marking", "option:release", "option:cancel"), menu.ids())
        assertEquals(0, menu.cursorIndex())
    }

    @Test
    fun moveItemsBoxAndMenu() {
        val items = screen<Screen.PcBox>("pc_items")
        assertEquals(listOf("option:party", "option:sort_items", "option:return"), items.ids().drop(33))
        val menu = screen<Screen.ContextMenu>("pc_items_menu")
        assertEquals(listOf("option:give", "option:cancel"), menu.ids()) // the Pokémon holds nothing
        assertEquals(1, menu.next(0, Button.DOWN))
        assertEquals(1, menu.next(0, Button.UP))
    }

    @Test
    fun depositWhereBoxPicker() {
        val picker = screen<Screen.ListMenu>("pc_deposit_where")
        assertEquals(MenuKind.PC, picker.kind)
        assertEquals((0 until 18).map { "box:$it" } + listOf("option:deposit", "option:cancel"), picker.ids())
        assertTrue(picker.entries.all { it.selectable }) // no full box
        assertEquals(0, picker.cursorIndex())
        assertEquals(1, picker.next(0, Button.RIGHT))
        assertEquals(17, picker.next(0, Button.LEFT)) // verified live: wraps to box 18
        assertEquals(18, picker.next(0, Button.DOWN))
        assertNull(picker.next(0, Button.UP))
        val confirm = screen<Screen.ListMenu>("pc_deposit_where_confirm")
        assertEquals(18, confirm.cursorIndex())
        assertEquals(0, confirm.next(18, Button.UP)) // back to the highlighted box
        assertEquals(19, confirm.next(18, Button.DOWN))
        assertEquals(18, confirm.next(19, Button.UP))
    }

    @Test
    fun boxTitleMenu() {
        val menu = screen<Screen.ListMenu>("pc_title_menu")
        assertEquals(listOf("option:change_box", "option:wallpaper", "option:name", "option:cancel"), menu.ids().drop(18))
        assertEquals(0, menu.cursorIndex())
        assertEquals(20, menu.next(19, Button.DOWN))
    }

    @Test
    fun pcMessageWaitsForA() {
        assertEquals(Screen.PressToContinue(ContinueReason.MESSAGE), decode("pc_message"))
    }

    // endregion

    // region Poké Mart

    @Test
    fun shopGridListsEveryPageWithPrices() {
        val shop = screen<Screen.Shop>("shop_grid")
        assertEquals(72178L, shop.money)
        assertEquals(25, shop.entries.size) // 19 items on 4 pages of 6, then CANCEL
        assertEquals(listOf("item:4", "item:3", "item:2", "item:17", "item:26", "item:25", "item:24"), shop.ids().take(7))
        assertTrue(shop.entries[0].label.endsWith("₽200"))
        assertTrue(shop.entries[6].label.endsWith("₽2500"))
        assertEquals("item:77", shop.entries[18].id)
        assertTrue(shop.entries.subList(19, 24).none { it.selectable }) // padding of the last page
        assertEquals("option:cancel", shop.entries[24].id)
        assertEquals(0, shop.cursorIndex())
    }

    @Test
    fun shopTopologyTurnsPagesFromTheOuterColumns() {
        val shop = screen<Screen.Shop>("shop_grid")
        assertNull(shop.next(0, Button.LEFT)) // no previous page
        assertEquals(1, shop.next(0, Button.RIGHT))
        assertEquals(7, shop.next(1, Button.RIGHT)) // next page, same slot (verified live)
        assertEquals(4, shop.next(0, Button.UP)) // columns wrap vertically
        assertEquals(2, shop.next(0, Button.DOWN))
        assertEquals(24, shop.next(1, Button.UP)) // CANCEL above slot 1
        assertEquals(24, shop.next(5, Button.DOWN)) // and below slot 5
        assertEquals(0, shop.next(4, Button.DOWN))
        assertEquals(5, shop.next(24, Button.UP))
        assertEquals(1, shop.next(24, Button.DOWN))
        assertNull(shop.next(24, Button.LEFT))
        assertNull(shop.next(19, Button.RIGHT)) // last page: no next page
        assertEquals(12, shop.next(18, Button.LEFT))
    }

    @Test
    fun shopSecondPageCancelAndLastPage() {
        val page2 = screen<Screen.Shop>("shop_page2")
        assertEquals(7, page2.cursorIndex())
        assertEquals(6, page2.next(7, Button.LEFT))
        assertEquals(0, page2.next(6, Button.LEFT))
        val cancel = screen<Screen.Shop>("shop_cancel")
        assertEquals(24, cancel.cursorIndex())
        assertEquals(11, cancel.next(24, Button.UP)) // into the page shown (offset 6)
        assertEquals(7, cancel.next(24, Button.DOWN))
        val last = screen<Screen.Shop>("shop_last_page")
        assertEquals(19, last.cursorIndex()) // the cursor kept slot 1 on a 1-item page
        assertFalse(last.entries[19].selectable)
        assertNull(last.next(19, Button.RIGHT))
        assertEquals(18, last.next(19, Button.LEFT))
    }

    @Test
    fun shopQuantity() {
        assertEquals(Screen.Quantity(1, 1, 28), decode("shop_qty")) // min(99, money / 2500)
        assertEquals(Screen.Quantity(28, 1, 28), decode("shop_qty_max")) // DOWN from 1 wraps to the max
    }

    @Test
    fun shopConfirmation() {
        val yes = screen<Screen.YesNo>("shop_yesno")
        assertEquals(listOf("option:yes", "option:no"), yes.ids())
        assertEquals(0, yes.cursorIndex())
        assertTrue(yes.entries.none { it.dangerous })
        assertEquals(TouchPoint(232, 64), yes.entries[0].touch)
        assertEquals(TouchPoint(232, 96), yes.entries[1].touch)
        assertEquals(1, screen<Screen.YesNo>("shop_yesno_no").cursorIndex())
    }

    @Test
    fun shopPurchaseMessages() {
        assertIs<Screen.Animation>(decode("shop_paying"))
        assertIs<Screen.PressToContinue>(decode("shop_thanks")) // "Here you are!" waits at its page break in state 12
        assertIs<Screen.Animation>(decode("shop_scrolling"))
        assertIs<Screen.Animation>(decode("shop_printing"))
        assertIs<Screen.PressToContinue>(decode("shop_put_away"))
    }

    // endregion

    // region Save

    @Test
    fun savePrompts() {
        val ask = screen<Screen.YesNo>("save_ask")
        assertEquals(listOf("option:yes", "option:no"), ask.ids())
        assertEquals(0, ask.cursorIndex())
        assertEquals(TouchPoint(232, 96), ask.entries[0].touch)
        assertEquals(TouchPoint(232, 128), ask.entries[1].touch)
        assertEquals(1, screen<Screen.YesNo>("save_ask_no").cursorIndex())
        val overwrite = screen<Screen.YesNo>("save_overwrite")
        assertEquals(0, overwrite.cursorIndex())
        assertTrue(overwrite.question != ask.question)
        assertIs<Screen.Animation>(decode("save_printing"))
        assertIs<Screen.Animation>(decode("save_saving"))
    }

    // endregion

    // region Not ours

    @Test
    fun otherScreensAreLeftToTheOtherDecoders() {
        listOf(
            "save_startmenu", "mart_buysell", "startmenu1", "battle1", "nb1", "yesno1", "multi1", "mailbox1", "d1", "starter1",
        ).forEach { assertNull(decode(it), it) }
    }

    // endregion
}
