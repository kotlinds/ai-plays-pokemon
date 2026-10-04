package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.grid
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.menu
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `switch` from each screen it starts from (command menu, "Will you switch?", the forced replacement after a K.O.)
 * and `keep_battling` (read the EXP messages up to the question, or no question at all), on scripted screens shaped
 * like the ones met live against Elite Four Will (fixtures in HgssLiveChecksFixtureTest).
 */
class BattleSwitchRecipesTest {

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT,
        listOf(Entry("option:fight", "FIGHT"), Entry("option:bag", "BAG"), Entry("option:run", "RUN"), Entry("option:pokemon", "POKéMON")),
        Cursor.At(0), Topology.grid(4, 2),
    )

    private val switchOrKeep = Screen.ListMenu(
        MenuKind.BATTLE_SWITCH_OR_KEEP, listOf(Entry("option:switch", "SWITCH"), Entry("option:keep", "KEEP BATTLING")),
        Cursor.At(0), Topology.vertical(2), CancelBehavior.CONFIRMS_LAST,
    )

    /** A trainer battle where SHIFT on a Pokémon sends it in (the command menu comes back a moment later). */
    private fun battleUi(start: Screen): Pair<ScriptedUi, () -> MonId?> {
        val ui = ScriptedUi(start, party = listOf(mon(1), mon(2), mon(3)))
        ui.battle = BattleState(BattleKind.TRAINER, false, BattlerRef.PLAYER_LEFT, emptyList(), listOf("Will"), ui.party.map { it.id }, null)
        var sentIn: MonId? = null
        var back: Long? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand && id == "option:pokemon" -> grid(ui.party, PartyPurpose.BATTLE_SWITCH)
                screen is Screen.ListMenu && id == "option:switch" -> grid(ui.party, PartyPurpose.BATTLE_SWITCH)
                screen is Screen.ListMenu && id == "option:keep" -> { back = ui.frame + 30; Screen.Battle(Awaiting.ANIMATION) }
                screen is Screen.PartyGrid && id != null && id.startsWith("mon:") -> menu("option:shift", "option:summary", "option:cancel", owner = MonId(id.substringAfter(':').substringBefore('.').toLong(16), 1))
                screen is Screen.ContextMenu && id == "option:shift" -> {
                    sentIn = screen.owner
                    back = ui.frame + 30
                    Screen.Battle(Awaiting.ANIMATION)
                }
                screen is Screen.Dialogue -> next.removeFirstOrNull() ?: screen
                else -> screen
            }
        }
        ui.game.onFrame = { frame, screen -> if (screen is Screen.Battle && back != null && frame >= back!!) command else screen }
        return ui to { sentIn }
    }

    /** What A shows next on a battle message (set per test). */
    private val next = ArrayDeque<Screen>()

    @Test
    fun switchFromTheCommandMenuGoesThroughPokemonThenShift() {
        val (ui, sentIn) = battleUi(command)
        assertIs<ActionOutcome.Done>(BattlePlans.switch.run(GameAction.Switch(MonId(3, 1)), ui.context()))
        assertEquals(MonId(3, 1), sentIn())
    }

    @Test
    fun switchAnswersWillYouSwitchWithSwitch() {
        val (ui, sentIn) = battleUi(switchOrKeep)
        assertIs<ActionOutcome.Done>(BattlePlans.switch.run(GameAction.Switch(MonId(2, 1)), ui.context()))
        assertEquals(MonId(2, 1), sentIn())
    }

    @Test
    fun theReplacementAfterAKnockOutIsChosenOnTheGridWithoutCancel() {
        val (ui, sentIn) = battleUi(command)
        val fainted = mon(1, hp = 0)
        ui.party = listOf(fainted, mon(2), mon(3))
        ui.game.screen = Screen.PartyGrid(
            PartyPurpose.BATTLE_REPLACE_FAINTED,
            listOf(Entry(fainted.id.toString(), "MON1 FAINTED", selectable = false), Entry("mon:00000002.00000001", "MON2"), Entry("mon:00000003.00000001", "MON3"), Entry("option:cancel", "CANCEL", selectable = false)),
            Cursor.At(0), Topology.vertical(4), CancelBehavior.NONE,
        )
        assertIs<ActionOutcome.Done>(BattlePlans.switch.run(GameAction.Switch(MonId(2, 1)), ui.context()))
        assertEquals(MonId(2, 1), sentIn())
    }

    @Test
    fun keepBattlingReadsTheMessagesUpToTheQuestionThenKeeps() {
        val (ui, sentIn) = battleUi(Screen.Dialogue(TextSource.BATTLE, null, "HO-OH gained 384 Exp. Points!", Awaiting.INPUT))
        next += Screen.Dialogue(TextSource.BATTLE, null, "Will is about to use JYNX. Will you change Pokémon?", Awaiting.INPUT)
        next += switchOrKeep
        assertIs<ActionOutcome.Done>(BasicPlans.keepBattling.run(GameAction.KeepBattling, ui.context()))
        assertEquals(null, sentIn())
        assertIs<Screen.BattleCommand>(ui.game.screen)
    }

    @Test
    fun withoutAQuestionKeepBattlingSaysTheFoeSentItsNextPokemon() {
        // Battle style SET (or a wild battle): no question, the command menu comes back.
        val (ui, _) = battleUi(Screen.Dialogue(TextSource.BATTLE, null, "The foe's XATU fainted!", Awaiting.INPUT))
        next += command
        val done = assertIs<ActionOutcome.Done>(BasicPlans.keepBattling.run(GameAction.KeepBattling, ui.context()))
        assertTrue("foe sent its next" in done.detail.orEmpty(), done.detail)
    }
}
