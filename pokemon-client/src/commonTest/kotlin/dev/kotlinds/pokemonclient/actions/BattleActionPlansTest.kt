package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Battle recipes on a scripted game: item use in battle (pocket found from the bag's contents, outcome told by the
 * screens), attack checks made before any press, spread moves' single target, SHIFT confirmed after choosing a
 * Pokémon on the battle party grid, HM moves that can't be forgotten, and `switch` refused while trapped.
 */
class BattleActionPlansTest {

    private val thunderbolt = KnownMove(Named(MoveId(85), "Thunderbolt"), 15, 15, "Electric")
    private val icyWind = KnownMove(Named(MoveId(196), "Icy Wind"), 15, 15, "Ice")
    private val cut = KnownMove(Named(MoveId(15), "Cut"), 30, 30, "Normal")

    private fun mon(n: Int, hp: Int = 20) = PartyMon(
        id = MonId(n.toLong(), 1), slot = n - 1, species = Named(SpeciesId(n), "MON$n"), nickname = null, level = 10,
        hp = hp, maxHp = 20, status = null, types = emptyList(), heldItem = null, ability = null, moves = listOf(thunderbolt, icyWind),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    private fun battler(ref: BattlerRef, moves: List<KnownMove> = emptyList(), volatile: Set<VolatileStatus> = emptySet()) = BattlerState(
        ref, if (ref.isPlayerSide) MonId(1, 1) else null, Named(SpeciesId(1), "MON"), null, 10, 20, 20, null, volatile, emptyMap(), listOf("Normal"), moves,
    )

    private fun battle(volatile: Set<VolatileStatus> = emptySet(), double: Boolean = false) = BattleState(
        BattleKind.WILD, double, BattlerRef.PLAYER_LEFT,
        listOf(battler(BattlerRef.PLAYER_LEFT, listOf(thunderbolt, icyWind), volatile), battler(BattlerRef.FOE_LEFT)),
        emptyList(), listOf(MonId(1, 1), MonId(2, 1)), null,
    )

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT,
        listOf(Entry("option:fight", "FIGHT"), Entry("option:bag", "BAG"), Entry("option:run", "RUN"), Entry("option:pokemon", "POKéMON")),
        Cursor.At(0), Topology.grid(4, 2),
    )

    private val ether = BagItem(Named(ItemId(38), "Ether"), 1)

    /** A scripted battle: [onA] answers A on (screen, highlighted id); B goes back to the command menu. */
    private class Ui(val battle: () -> BattleState?, val party: List<PartyMon>, val bag: List<BagPocket>, start: Screen) {
        var onA: (Screen, String?) -> Screen = { s, _ -> s }
        var onB: (Screen) -> Screen = { it }
        val game = FakeGame(start, state = { GameState(0, it, null, party, bag, battle(), null) })

        init {
            game.onPress = { button, screen ->
                val selectable = screen as? Screen.Selectable
                val at = (selectable?.cursor as? Cursor.At)?.index
                when (button) {
                    Button.A -> onA(screen, at?.let { selectable.entries[it].id })
                    Button.B -> onB(screen)
                    else -> {
                        val next = at?.let { selectable.topology.next(it, button) }
                        if (selectable == null || next == null) screen else withCursor(selectable, next)
                    }
                }
            }
        }
    }

    private fun battleBag(pocket: String, items: List<String>) = Screen.Bag(
        pocket, listOf("HP/PP RESTORE", "STATUS HEALERS", "POKé BALLS", "BATTLE ITEMS"), 0, 1, true,
        items.map { Entry(it, it) } + Entry("option:cancel", "CANCEL"), Cursor.At(0), Topology.vertical(items.size + 1),
        pocketContents = mapOf("pocket:hp_pp_restore" to listOf(ItemId(38)), "pocket:status_healers" to listOf(ItemId(28)), "pocket:poke_balls" to emptyList(), "pocket:battle_items" to emptyList()),
    )

    private val bagMenu = battleBag("HP/PP RESTORE", listOf("pocket:hp_pp_restore", "pocket:status_healers", "pocket:poke_balls", "pocket:battle_items"))

    private fun battleGrid(party: List<PartyMon>) = Screen.PartyGrid(
        PartyPurpose.BATTLE_USE_ITEM, party.map { Entry(it.id.toString(), it.displayName) } + Entry("option:cancel", "CANCEL"),
        Cursor.At(0), Topology.vertical(party.size + 1), CancelBehavior.CLOSES,
    )

    private val restoreWhichMove = Screen.ListMenu(
        MenuKind.OTHER, listOf(Entry("move:85", "Thunderbolt (Electric, 3/15 PP)"), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"), Entry("option:cancel", "CANCEL")),
        Cursor.At(0), Topology.vertical(3),
    )

    @Test
    fun useItemInBattleOpensThePocketHoldingTheItemAndPicksTheMove() {
        val party = listOf(mon(1), mon(2))
        val chosen = mutableListOf<String>()
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            id?.let { chosen += it }
            when {
                screen is Screen.BattleCommand && id == "option:bag" -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag && id == "item:38" -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu && id == "option:use" -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                screen is Screen.ListMenu -> Screen.Dialogue(TextSource.BATTLE, null, "PP was restored.", Awaiting.INPUT)
                // The bag closes: the turn plays.
                screen is Screen.Dialogue -> Screen.Battle(Awaiting.ANIMATION)
                else -> screen
            }
        }
        val outcome = PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("Ether"), MonId(2, 1), MoveRef("Icy Wind")), ui.game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(listOf("option:bag", "pocket:hp_pp_restore", "item:38", "option:use", "mon:00000002.00000001", "move:196"), chosen)
    }

    @Test
    fun useItemInBattleWithoutEffectGoesBackToTheCommandMenu() {
        val party = listOf(mon(1))
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand && id == "option:bag" -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag && id == "item:38" -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                screen is Screen.ListMenu -> Screen.Dialogue(TextSource.BATTLE, null, "It won't have any effect.", Awaiting.INPUT)
                // Refused: back to the party screen.
                screen is Screen.Dialogue -> battleGrid(party)
                else -> screen
            }
        }
        ui.onB = { command }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("item:38"), MonId(1, 1), MoveRef("move:85")), ui.game.context()))
        assertEquals(UnavailableReason.NO_EFFECT, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertIs<Screen.BattleCommand>(ui.game.screen)
    }

    @Test
    fun useItemListsTheMovesWhenAPpItemHasNoMove() {
        val party = listOf(mon(1))
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                else -> screen
            }
        }
        ui.onB = { command }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("Ether"), MonId(1, 1)), ui.game.context()))
        val error = assertIs<ActionError.InvalidParameter>(failed.error)
        assertEquals("move", error.parameter)
        assertTrue(error.allowed.any { "move:85" in it })
        assertIs<Screen.BattleCommand>(ui.game.screen, "the item screens are closed again")
    }

    @Test
    fun useItemParsesAListOfUses() {
        val json = Json.parseToJsonElement("""{"type":"use_item","items":[{"item":"Potion","target":"mon:00000001.00000001"},{"item":"Max Ether","target":"mon:00000002.00000001","move":"Sacred Fire"}]}""").jsonObject
        val action = assertIs<GameAction.UseItem>(ActionRegistry.of().parse(json, ActionMode.ASSISTED).getOrThrow())
        assertEquals(2, action.uses.size)
        assertEquals(MoveRef("Sacred Fire"), action.uses[1].move)
        assertEquals(MonId(2, 1), action.uses[1].target)
        val single = Json.parseToJsonElement("""{"type":"use_item","item":"Max Ether","target":"mon:00000001.00000001","move":"move:221"}""").jsonObject
        assertEquals(MoveRef("move:221"), assertIs<GameAction.UseItem>(ActionRegistry.of().parse(single, ActionMode.ASSISTED).getOrThrow()).move)
    }

    @Test
    fun attackWithAMoveTheMonDoesntKnowFailsBeforeAnyPress() {
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), command)
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Ice Shard")), ui.game.context()))
        assertIs<ActionError.InvalidParameter>(failed.error)
        assertTrue(ui.game.presses.isEmpty(), "the move screen wasn't opened")
    }

    @Test
    fun spreadMovesConfirmTheirOnlyTargetWithoutBeingGivenOne() {
        val ui = Ui({ battle(double = true) }, listOf(mon(1)), emptyList(), command)
        var confirmed: String? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand -> Screen.MoveSelect(
                    MoveContext.BATTLE, MonId(1, 1), null,
                    listOf(Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"), Entry("option:cancel", "CANCEL")),
                    Cursor.At(0), Topology.vertical(3), CancelBehavior.CLOSES,
                )
                screen is Screen.MoveSelect -> Screen.TargetSelect(listOf(Entry("target:all", "MAREEP + MARILL"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.TargetSelect -> { confirmed = id; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Icy Wind")), ui.game.context()))
        assertEquals("target:all", confirmed)
    }

    @Test
    fun choosingAPokemonOnTheReplacementGridAlsoConfirmsShift() {
        val party = listOf(mon(1, hp = 0), mon(2))
        val grid = Screen.PartyGrid(
            PartyPurpose.BATTLE_REPLACE_FAINTED,
            listOf(Entry(party[0].id.toString(), "MON1 FAINTED", selectable = false), Entry(party[1].id.toString(), "MON2"), Entry("option:cancel", "CANCEL", selectable = false)),
            Cursor.At(1), Topology.vertical(3), CancelBehavior.NONE,
        )
        val ui = Ui({ battle() }, party, emptyList(), grid)
        var shifted = false
        ui.onA = { screen, id ->
            when {
                screen is Screen.PartyGrid -> Screen.ContextMenu(MonId(2, 1), listOf(Entry("option:shift", "SHIFT"), Entry("option:summary", "SUMMARY"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(3))
                screen is Screen.ContextMenu && id == "option:shift" -> { shifted = true; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BasicPlans.choose.run(GameAction.Choose(party[1].id.toString()), ui.game.context()))
        assertTrue(shifted)
    }

    @Test
    fun forgettingAnHmIsATypedError() {
        val list = Screen.MoveSelect(
            MoveContext.FORGET_IN_BATTLE, MonId(1, 1), Named(MoveId(53), "Flamethrower"),
            listOf(
                Entry("move:15", "Cut (Normal, 30/30 PP)", selectable = false), Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"),
                Entry("move:53", "Flamethrower (Fire) (new: don't learn it)"), Entry("option:cancel", "CANCEL"),
            ),
            Cursor.At(1), Topology.vertical(4), CancelBehavior.CLOSES,
        )
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), list)
        val failed = assertIs<ActionOutcome.Failed>(BattlePlans.learnMove.run(GameAction.LearnMove(MoveRef("Cut")), ui.game.context()))
        val error = assertIs<ActionError.HmCannotForget>(failed.error)
        assertEquals("HM_CANNOT_FORGET", error.code)
        assertEquals("Cut", error.move)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun switchIsUnavailableWhileTrapped() {
        val party = listOf(mon(1), mon(2))
        val trapped = GameState(0, command, null, party, emptyList(), battle(setOf(VolatileStatus.Trapped)), null)
        val availability = CommonActions.switch.spec.availability(trapped)
        assertEquals(UnavailableReason.TRAPPED, assertIs<Availability.Unavailable>(availability).reason)
        val free = GameState(0, command, null, party, emptyList(), battle(), null)
        assertIs<Availability.Available>(CommonActions.switch.spec.availability(free))
    }

    private companion object {
        fun withCursor(screen: Screen.Selectable, index: Int): Screen = when (screen) {
            is Screen.ListMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.PartyGrid -> screen.copy(cursor = Cursor.At(index))
            is Screen.ContextMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.Bag -> screen.copy(cursor = Cursor.At(index))
            is Screen.BattleCommand -> screen.copy(cursor = Cursor.At(index))
            is Screen.MoveSelect -> screen.copy(cursor = Cursor.At(index))
            is Screen.TargetSelect -> screen.copy(cursor = Cursor.At(index))
            else -> screen
        }
    }
}
