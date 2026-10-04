package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.dialogue
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.item
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.menu
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.world
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.yesNo
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BoxMon
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PcBoxContents
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Pokémon Center and Poké Mart recipes on scripted screens (all checked live at the Indigo Plateau and in
 * Ecruteak): `heal` at the nurse, `buy` from the shop list, and one `pc` session (deposit, then withdraw) checked on
 * the party and the boxes. The player already stands in front of the counter / the PC.
 */
class ServicesRecipesTest {

    // region heal

    @Test
    fun healTalksToTheNurseAnswersYesAndChecksTheParty() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.NURSE)
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, hp = 5), mon(2, hp = 0)), world = world(3, 3))
        ui.field = field(1, 1, Direction.NORTH, listOf(nurse))
        ui.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> dialogue("Welcome to the Pokémon Center.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.startsWith("Welcome") -> yesNo("Would you like to rest your Pokémon?")
                screen is Screen.YesNo && id == "option:yes" -> {
                    ui.party = ui.party.map { it.copy(hp = it.maxHp) }
                    dialogue("We've restored your Pokémon to full health.", TextSource.FIELD)
                }
                screen is Screen.Dialogue -> OVERWORLD
                else -> screen
            }
        }
        val done = assertIs<ActionOutcome.Done>(FieldPlans.heal.run(GameAction.Heal, ui.context()))
        assertEquals("party healed", done.detail)
        assertTrue(ui.party.all { it.hp == it.maxHp })
    }

    @Test
    fun healWithoutANurseIsRefusedAtOnce() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, hp = 5)), world = world(3, 3))
        ui.field = field(1, 1, Direction.NORTH)
        val failed = assertIs<ActionOutcome.Failed>(FieldPlans.heal.run(GameAction.Heal, ui.context()))
        assertEquals(UnavailableReason.WRONG_SCREEN, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty())
    }

    // endregion

    // region buy

    /** The shop list (Ultra Ball ₽1200); a quantity screen moved by UP / DOWN (±1) and RIGHT / LEFT (±10). */
    private fun shopUi(money: Long): ScriptedUi {
        val list = Screen.Shop(money, listOf(Entry("item:2", "Ultra Ball ₽1200"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        val ui = ScriptedUi(list, party = listOf(mon(1)), bag = listOf(BagPocket("balls", listOf(item(2, "Ultra Ball", 1)))))
        ui.money = money
        var quantity = 0
        ui.onDpad = { button, screen ->
            (screen as? Screen.Quantity)?.let {
                val value = when (button) {
                    Button.UP -> it.value + 1
                    Button.DOWN -> it.value - 1
                    Button.RIGHT -> it.value + 10
                    Button.LEFT -> it.value - 10
                    else -> it.value
                }.coerceIn(it.min, it.max)
                it.copy(value = value)
            }
        }
        ui.onA = { screen, id ->
            when {
                screen is Screen.Shop && id == "item:2" -> Screen.Quantity(1, 1, 99)
                screen is Screen.Quantity -> { quantity = screen.value; yesNo("$quantity Ultra Ball(s)? That will be ₽${quantity * 1200}. OK?") }
                screen is Screen.YesNo && id == "option:yes" -> {
                    ui.money -= quantity * 1200L
                    ui.bag = listOf(BagPocket("balls", listOf(item(2, "Ultra Ball", 1 + quantity))))
                    dialogue("Here you are! Thank you!", TextSource.FIELD)
                }
                screen is Screen.Dialogue -> list.copy(money = ui.money)
                else -> screen
            }
        }
        return ui
    }

    @Test
    fun buySetsTheQuantityConfirmsAndChecksTheBagAndTheMoney() {
        val ui = shopUi(money = 20000)
        val done = assertIs<ActionOutcome.Done>(ShopPlans.buy.run(GameAction.Buy(listOf(Purchase(ItemRef("Ultra Ball"), 12))), ui.context()))
        assertEquals("bought 12 Ultra Ball (₽14400), ₽5600 left", done.detail)
        // ±10 first, then ±1: RIGHT, UP (1 → 11 → 12).
        assertEquals(listOf(Button.RIGHT, Button.UP), ui.game.presses.filter { it == Button.RIGHT || it == Button.UP })
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun aPurchaseTheMoneyCantPayIsRefusedBeforeChoosingAnything() {
        val ui = shopUi(money = 2000)
        val failed = assertIs<ActionOutcome.Failed>(ShopPlans.buy.run(GameAction.Buy(listOf(Purchase(ItemRef("item:2"), 2))), ui.context()))
        assertEquals(UnavailableReason.NOT_ENOUGH_MONEY, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(Button.A !in ui.game.presses)
        assertEquals(2000, ui.money)
    }

    // endregion

    // region pc

    private val box1 = "BOX 1"

    /**
     * The PC in front of the player (Pc tile north): top menu → storage menu (DEPOSIT, WITHDRAW, MOVE, MOVE ITEMS,
     * SEE YA!) → boxes. B on a box asks "Continue Box operations?" (NO leaves); B on the menus goes back.
     */
    private fun pcUi(party: List<PartyMon>, stored: List<BoxMon>): ScriptedUi {
        val ui = ScriptedUi(OVERWORLD, party = party, world = world(3, 3, mapOf((1 to 0) to TileInfo(true, TileKind.Pc))))
        ui.field = field(1, 1, Direction.NORTH)
        fun storage(mons: List<BoxMon>) = PcStorage(0, listOf(PcBoxContents(0, box1, mons, 30), PcBoxContents(1, "BOX 2", emptyList(), 30)))
        ui.storage = storage(stored)
        fun multichoice(vararg ids: String) = Screen.ListMenu(MenuKind.MULTICHOICE, ids.map { Entry(it, it) }, Cursor.At(0), Topology.vertical(ids.size), CancelBehavior.CONFIRMS_LAST)
        val top = multichoice("option:0", "option:1", "option:2")
        val storageMenu = multichoice("option:0", "option:1", "option:2", "option:3", "option:4")
        fun box(mode: PcMode): Screen.PcBox {
            val mons = if (mode == PcMode.DEPOSIT) ui.party.map { it.id } else ui.storage!!.boxes[0].mons.map { it.id }
            val entries = mons.map { Entry(it.toString(), it.toString()) } + Entry("option:next_box", "▶", touch = dev.kotlinds.pokemonclient.console.TouchPoint(200, 20))
            return Screen.PcBox(0, box1, entries, Cursor.At(0), Topology.vertical(entries.size), mode = mode)
        }
        var mode = PcMode.DEPOSIT
        var picked: MonId? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> dialogue("ACE booted up the PC.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.contains("booted") -> top
                screen is Screen.ListMenu && screen.entries.size == 3 && id == "option:0" -> dialogue("Accessed the Pokémon Storage System.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.contains("Storage") -> storageMenu
                screen is Screen.ListMenu && screen.entries.size == 5 && id == "option:0" -> box(PcMode.DEPOSIT.also { mode = it })
                screen is Screen.ListMenu && screen.entries.size == 5 && id == "option:1" -> box(PcMode.WITHDRAW.also { mode = it })
                screen is Screen.PcBox && id != null && id.startsWith("mon:") -> {
                    picked = MonId(id.substringAfter(':').substringBefore('.').toLong(16), id.substringAfter('.').toLong(16))
                    if (mode == PcMode.DEPOSIT) menu("option:deposit", "option:summary", "option:marking", "option:release", "option:cancel")
                    else menu("option:withdraw", "option:summary", "option:marking", "option:release", "option:cancel")
                }
                screen is Screen.ContextMenu && id == "option:deposit" -> Screen.ListMenu(
                    MenuKind.OTHER, listOf(Entry("box:0", box1), Entry("box:1", "BOX 2"), Entry("option:deposit", "DEPOSIT"), Entry("option:cancel", "CANCEL")),
                    Cursor.At(0), Topology.vertical(4),
                )
                screen is Screen.ListMenu && screen.kind == MenuKind.OTHER && id == "option:deposit" -> {
                    val mon = ui.party.single { it.id == picked }
                    ui.party = ui.party - mon
                    ui.storage = storage(ui.storage!!.boxes[0].mons + BoxMon(mon.id, 0, ui.storage!!.boxes[0].mons.size, mon.species, null, mon.level, null, false))
                    box(PcMode.DEPOSIT)
                }
                screen is Screen.ContextMenu && id == "option:withdraw" -> {
                    val stored = ui.storage!!.find(picked!!)!!
                    ui.storage = storage(ui.storage!!.boxes[0].mons - stored)
                    ui.party = ui.party + mon(stored.id.personality.toInt())
                    box(PcMode.WITHDRAW)
                }
                screen is Screen.YesNo && id == "option:no" -> storageMenu
                screen is Screen.YesNo && id == "option:yes" -> box(mode)
                else -> screen
            }
        }
        ui.onB = { screen ->
            when {
                screen is Screen.PcBox -> yesNo("Continue Box operations?")
                screen is Screen.ListMenu && screen.entries.size == 5 -> top
                else -> OVERWORLD
            }
        }
        return ui
    }

    @Test
    fun onePcSessionDepositsThenWithdrawsAndChecksBoth() {
        val stored = BoxMon(MonId(7, 1), 0, 0, dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(7), "MON7"), null, 20, null, false)
        val ui = pcUi(listOf(mon(1), mon(2)), listOf(stored))
        val ops = listOf(PcOperation.Deposit(MonId(2, 1)), PcOperation.Withdraw(MonId(7, 1)))
        val done = assertIs<ActionOutcome.Done>(PcPlans.pc.run(GameAction.Pc(ops), ui.context()))
        assertEquals("deposited MON2 in BOX 1 (2/30); withdrew MON7 (party: 2/6)", done.detail)
        assertEquals(listOf(MonId(1, 1), MonId(7, 1)), ui.party.map { it.id })
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun impossibleSessionsAreRefusedBeforeThePcIsTouched() {
        val stored = BoxMon(MonId(7, 1), 0, 0, dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(7), "MON7"), null, 20, null, false)
        // The last Pokémon can't be deposited.
        val alone = pcUi(listOf(mon(1)), listOf(stored))
        val last = assertIs<ActionOutcome.Failed>(PcPlans.pc.run(GameAction.Pc(listOf(PcOperation.Deposit(MonId(1, 1)))), alone.context()))
        assertEquals(UnavailableReason.LAST_POKEMON, assertIs<ActionError.Unavailable>(last.error).reason)
        assertTrue(alone.game.presses.isEmpty())
        // A full party can't withdraw (unless a deposit comes first in the same session).
        val full = pcUi((1..6).map { mon(it) }, listOf(stored))
        val failed = assertIs<ActionOutcome.Failed>(PcPlans.pc.run(GameAction.Pc(listOf(PcOperation.Withdraw(MonId(7, 1)))), full.context()))
        assertEquals(UnavailableReason.PARTY_FULL, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(full.game.presses.isEmpty())
    }

    // endregion
}
