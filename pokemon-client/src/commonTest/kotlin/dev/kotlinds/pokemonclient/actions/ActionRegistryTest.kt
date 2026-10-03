package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionRegistryTest {

    private val registry = ActionRegistry.of()

    private val tackle = KnownMove(Named(MoveId(33), "Tackle"), 35, 35, "Normal")
    private val ember = KnownMove(Named(MoveId(52), "Ember"), 0, 25, "Fire")

    private fun battler(ref: BattlerRef, moves: List<KnownMove> = emptyList()) = BattlerState(
        ref, if (ref.isPlayerSide) MonId(1, 2) else null, Named(SpeciesId(155), "CYNDAQUIL"), null, 5, 20, 20, null, emptySet(), emptyMap(), listOf("Fire"), moves,
    )

    private fun battleState(screen: Screen, kind: BattleKind = BattleKind.WILD) = GameState(
        0, screen, null, emptyList(), null,
        BattleState(kind, false, BattlerRef.PLAYER_LEFT, listOf(battler(BattlerRef.PLAYER_LEFT, listOf(tackle, ember)), battler(BattlerRef.FOE_LEFT)), emptyList(), emptyList(), null),
        null,
    )

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT,
        listOf("fight" to "FIGHT", "bag" to "BAG", "run" to "RUN", "pokemon" to "POKéMON").map { (id, l) -> Entry("option:$id", l) },
        Cursor.At(0),
        Topology.of(mapOf(0 to mapOf(Button.LEFT to 1, Button.DOWN to 2, Button.RIGHT to 3), 1 to mapOf(Button.UP to 0, Button.RIGHT to 2), 2 to mapOf(Button.UP to 0, Button.LEFT to 1, Button.RIGHT to 3), 3 to mapOf(Button.UP to 0, Button.LEFT to 2))),
    )

    private val moves = Screen.MoveSelect(
        MoveContext.BATTLE, MonId(1, 2), null,
        listOf(Entry("move:33", "Tackle (Normal, 35/35 PP)"), Entry("move:52", "Ember (Fire, 0/25 PP)", selectable = false), Entry("empty:2", "-", false), Entry("empty:3", "-", false), Entry("option:cancel", "CANCEL")),
        Cursor.At(0), Topology.grid(4, 2), CancelBehavior.CLOSES,
    )

    @Test
    fun battleActionsAreListedOnlyInBattleWithUsableMoves() {
        val available = registry.available(battleState(command), ActionMode.ASSISTED).associateBy { it.name }
        assertEquals(listOf("move:33"), available.getValue("attack").choices.getValue("move").map { it.value })
        assertTrue("run" in available)
        val field = GameState(0, Screen.Overworld(awaiting = Awaiting.INPUT), null, emptyList(), null, null, null)
        assertTrue(registry.available(field, ActionMode.ASSISTED).none { it.name == "attack" || it.name == "run" })
    }

    @Test
    fun runIsUnavailableInTrainerBattlesWithATypedReason() {
        val unavailable = registry.unavailable(battleState(command, BattleKind.TRAINER), ActionMode.ASSISTED).single { it.name == "run" }
        assertEquals(UnavailableReason.TRAINER_BATTLE, unavailable.reason)
    }

    @Test
    fun pureModeOnlyOffersRawControls() {
        val names = registry.available(battleState(command), ActionMode.PURE).map { it.name }.toSet()
        assertEquals(setOf("press", "touch", "wait"), names)
    }

    @Test
    fun parsingRejectsUnknownTypesAndBadParameters() {
        val unknown = registry.parse(buildJsonObject { put("type", "teleport") }, ActionMode.ASSISTED)
        assertIs<ActionError.InvalidParameter>((unknown.exceptionOrNull() as ActionException).error)
        val badButton = registry.parse(buildJsonObject { put("type", "press"); put("button", "z") }, ActionMode.ASSISTED)
        assertIs<ActionError.InvalidParameter>((badButton.exceptionOrNull() as ActionException).error)
        val pureAttack = registry.parse(buildJsonObject { put("type", "attack"); put("move", "tackle") }, ActionMode.PURE)
        assertTrue(pureAttack.isFailure)
        val ok = registry.parse(buildJsonObject { put("type", "attack"); put("move", JsonPrimitive("Tackle")) }, ActionMode.ASSISTED)
        assertEquals(GameAction.Attack(MoveRef("Tackle")), ok.getOrThrow())
    }

    @Test
    fun attackGoesThroughFightThenTheMoveCheckingEachCursor() {
        val game = FakeGame(command, state = { battleState(it) })
        game.onPress = { button, screen ->
            when {
                screen is Screen.BattleCommand && button == Button.A -> moves
                screen is Screen.MoveSelect && button == Button.A -> Screen.Battle(Awaiting.INPUT)
                else -> screen
            }
        }
        val outcome = registry.execute(GameAction.Attack(MoveRef("tackle")), game.scope(), game)
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(listOf(Button.A, Button.A), game.presses)
    }

    @Test
    fun aMoveWithoutPpOrUnknownIsAnExplicitError() {
        val game = FakeGame(command, state = { battleState(it) })
        game.onPress = { button, screen -> if (screen is Screen.BattleCommand && button == Button.A) moves else screen }
        val outcome = registry.execute(GameAction.Attack(MoveRef("Flamethrower")), game.scope(), game)
        assertIs<ActionError.InvalidParameter>(assertIs<ActionOutcome.Failed>(outcome).error)
    }

    @Test
    fun executingAnUnavailableActionFailsBeforePressingAnything() {
        val game = FakeGame(command, state = { battleState(it, BattleKind.TRAINER) })
        val outcome = registry.execute(GameAction.Run, game.scope(), game)
        assertEquals(UnavailableReason.TRAINER_BATTLE, (assertIs<ActionOutcome.Failed>(outcome).error as ActionError.Unavailable).reason)
        assertTrue(game.presses.isEmpty())
    }

    @Test
    fun enumerationGivesCanonicalKeysForPickFromAListModels() {
        val keys = registry.enumerate(battleState(command), ActionMode.ASSISTED).keys
        assertTrue("attack(move:33)" in keys)
        assertTrue("run" in keys)
        assertTrue("press(a)" in keys)
    }

    @Test
    fun theSchemaHasOneObjectPerAction() {
        val schema = registry.jsonSchema(ActionMode.ASSISTED).toString()
        assertTrue("\"const\":\"attack\"" in schema)
        assertTrue("\"const\":\"press\"" in schema)
    }
}

class MatchesRefTest {
    @kotlin.test.Test
    fun idsNamesAccentsAndPunctuation() {
        kotlin.test.assertTrue(matchesRef("item:4", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("4", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("Poke Ball", "item", 4, "Poké Ball"))
        kotlin.test.assertTrue(matchesRef("POKE-BALL", "item", 4, "Poké Ball"))
        kotlin.test.assertFalse(matchesRef("Great Ball", "item", 4, "Poké Ball"))
    }
}
