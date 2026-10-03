package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Recipes run against a scripted game: menus whose cursor follows the D-pad along their topology, and A / B
 * transitions written per test. Checks the recipes' paths, their checks of the result and their typed failures.
 */
class PlansTest {

    private fun mon(n: Int, item: Named<ItemId>? = null, hp: Int = 20) = PartyMon(
        id = MonId(n.toLong(), 1), slot = n - 1, species = Named(SpeciesId(n), "MON$n"), nickname = null, level = 10,
        hp = hp, maxHp = 20, status = null, types = emptyList(), heldItem = item, ability = null, moves = emptyList(),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    /** A scripted game with a party and a bag; [transitions] answers A / B on (screen, highlighted entry id). */
    private class Ui(var party: List<PartyMon>, var bag: List<BagPocket> = emptyList()) {
        var onA: (Screen, String?) -> Screen = { s, _ -> s }
        var onB: (Screen) -> Screen = { Screen.Overworld(null, Awaiting.INPUT) }
        val game = FakeGame(Screen.Overworld(null, Awaiting.INPUT), state = { GameState(0, it, null, party, bag, null, null) })

        init {
            game.onPress = { button, screen -> press(button, screen) }
        }

        private fun press(button: Button, screen: Screen): Screen {
            val selectable = screen as? Screen.Selectable
            val at = (selectable?.cursor as? Cursor.At)?.index
            return when (button) {
                Button.A -> onA(screen, at?.let { selectable.entries[it].id })
                Button.B -> onB(screen)
                Button.X -> if (screen is Screen.Overworld) START else screen
                else -> {
                    if (selectable == null || at == null) return screen
                    val next = selectable.topology.next(at, button) ?: return screen
                    withCursor(selectable, next)
                }
            }
        }
    }

    @Test
    fun reorderPartySwitchesThroughTheMenusAndChecksTheNewOrder() {
        val ui = Ui(listOf(mon(1), mon(2), mon(3)))
        var moving: String? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.ListMenu && id == "option:pokemon" -> grid(ui.party)
                screen is Screen.PartyGrid && moving == null -> { moving = id; menu("option:summary", "option:switch", "option:item") }
                screen is Screen.ContextMenu && id == "option:switch" -> grid(ui.party, PartyPurpose.SWITCH)
                screen is Screen.PartyGrid -> {
                    val a = ui.party.indexOfFirst { it.id.toString() == moving }
                    val b = ui.party.indexOfFirst { it.id.toString() == id }
                    ui.party = ui.party.toMutableList().also { it[a] = ui.party[b]; it[b] = ui.party[a] }
                    grid(ui.party)
                }
                else -> screen
            }
        }
        val outcome = PartyBagPlans.reorderParty.run(GameAction.ReorderParty(MonId(3, 1), 1), ui.game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(listOf(3L, 2L, 1L), ui.party.map { it.id.personality })
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun reorderPartyRefusesAPositionOutsideTheParty() {
        val ui = Ui(listOf(mon(1), mon(2)))
        val outcome = assertIs<ActionOutcome.Failed>(PartyBagPlans.reorderParty.run(GameAction.ReorderParty(MonId(2, 1), 5), ui.game.context()))
        assertIs<ActionError.InvalidParameter>(outcome.error)
        assertTrue(ui.game.presses.isEmpty(), "nothing pressed for an invalid request")
    }

    @Test
    fun takeItemIsCheckedOnTheHeldItem() {
        val potion = Named(ItemId(17), "Potion")
        val ui = Ui(listOf(mon(1, item = potion)))
        ui.onA = { screen, id ->
            when {
                screen is Screen.ListMenu && id == "option:pokemon" -> grid(ui.party)
                screen is Screen.PartyGrid -> menu("option:summary", "option:switch", "option:item")
                id == "option:item" -> menu("option:give", "option:take", "option:quit")
                id == "option:take" -> { ui.party = listOf(mon(1)); grid(ui.party) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(PartyBagPlans.takeItem.run(GameAction.TakeItem(MonId(1, 1)), ui.game.context()))
        assertEquals(null, ui.party.single().heldItem)
    }

    @Test
    fun useItemWithoutEffectIsAnExplicitFailure() {
        val potion = BagItem(Named(ItemId(17), "Potion"), 2)
        val ui = Ui(listOf(mon(1)), listOf(BagPocket("medicine", listOf(potion))))
        ui.onA = { screen, id ->
            when {
                screen is Screen.ListMenu && id == "option:bag" -> bag(listOf("item:17"))
                screen is Screen.Bag && id == "item:17" -> menu("option:use", "option:give", "option:cancel")
                id == "option:use" -> grid(ui.party, PartyPurpose.USE_ITEM)
                // "It won't have any effect.": the Potion stays in the bag.
                screen is Screen.PartyGrid -> Screen.Dialogue(dev.kotlinds.pokemonclient.state.TextSource.MENU, null, "...", Awaiting.INPUT)
                screen is Screen.Dialogue -> Screen.Overworld(null, Awaiting.INPUT)
                else -> screen
            }
        }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("Potion"), MonId(1, 1)), ui.game.context()))
        assertEquals(UnavailableReason.NO_EFFECT, assertIs<ActionError.Unavailable>(failed.error).reason)
    }

    @Test
    fun saveGameAnswersBothQuestionsAndAcceptsEndingOnTheStartMenu() {
        val ui = Ui(listOf(mon(1)))
        var asked = 0
        ui.onA = { screen, id ->
            when {
                screen is Screen.ListMenu && id == "option:save" -> yesNo("Save?")
                screen is Screen.YesNo && asked++ == 0 -> yesNo("Overwrite?")
                // Saved: the game gives the start menu back (it was opened with X).
                screen is Screen.YesNo -> START
                else -> screen
            }
        }
        ui.onB = { Screen.Overworld(null, Awaiting.INPUT) }
        assertIs<ActionOutcome.Done>(FieldPlans.saveGame.run(GameAction.SaveGame, ui.game.context()))
        assertEquals(2, asked)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    private companion object {
        val START = Screen.ListMenu(
            MenuKind.START_MENU,
            listOf("pokedex", "pokemon", "bag", "save").map { Entry("option:$it", it.uppercase()) },
            Cursor.At(0), Topology.vertical(4),
        )

        fun withCursor(screen: Screen.Selectable, index: Int): Screen = when (screen) {
            is Screen.ListMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.PartyGrid -> screen.copy(cursor = Cursor.At(index))
            is Screen.ContextMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.YesNo -> screen.copy(cursor = Cursor.At(index))
            is Screen.Bag -> screen.copy(cursor = Cursor.At(index))
            else -> screen
        }

        fun grid(party: List<PartyMon>, purpose: PartyPurpose = PartyPurpose.FIELD) = Screen.PartyGrid(
            purpose, party.map { Entry(it.id.toString(), it.displayName) } + Entry("option:cancel", "CANCEL"),
            Cursor.At(0), Topology.grid(party.size + 1, 2), dev.kotlinds.pokemonclient.state.CancelBehavior.CLOSES,
        )

        fun menu(vararg ids: String) = Screen.ContextMenu(null, ids.map { Entry(it, it) }, Cursor.At(0), Topology.vertical(ids.size))

        fun yesNo(question: String) = Screen.YesNo(question, listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(0), Topology.vertical(2))

        fun bag(items: List<String>) = Screen.Bag(
            "medicine", listOf("items", "medicine"), 0, 1, false,
            items.map { Entry(it, it) } + Entry("option:cancel", "CANCEL"), Cursor.At(0), Topology.vertical(items.size + 1),
        )
    }
}
