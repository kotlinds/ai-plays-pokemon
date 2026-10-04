package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.view.StateView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The actions that only existed as types (sell, release, drag, open_menu), `wait` until a change, the bag view. */
class MoreActionsTest {

    private val registry = ActionRegistry.of()

    private fun parse(json: String) = registry.parse(Json.parseToJsonElement(json).jsonObject, ActionMode.ASSISTED)

    @Test
    fun parsesTheNewActions() {
        assertEquals(GameAction.Sell(ItemRef("item:3"), 3), parse("""{"type":"sell","item":"item:3","quantity":3}""").getOrThrow())
        assertEquals(GameAction.Sell(ItemRef("Potion"), 1), parse("""{"type":"sell","item":"Potion"}""").getOrThrow())
        val mon = MonId.parse("mon:c50a0956.76f3a6fb")!!
        assertEquals(GameAction.Release(mon, true), parse("""{"type":"release","pokemon":"$mon","confirm":true}""").getOrThrow())
        assertEquals(GameAction.Release(mon, false), parse("""{"type":"release","pokemon":"$mon"}""").getOrThrow())
        assertEquals(GameAction.OpenMenu("option:bag"), parse("""{"type":"open_menu","entry":"bag"}""").getOrThrow())
        assertEquals(
            GameAction.Drag(TouchPoint(48, 112), TouchPoint(104, 88), 20),
            parse("""{"type":"drag","x":48,"y":112,"to_x":104,"to_y":88,"frames":20}""").getOrThrow(),
        )
        assertTrue(parse("""{"type":"drag","x":48,"y":300,"to_x":1,"to_y":1}""").isFailure, "y is a bottom-screen pixel")
        assertEquals(GameAction.Wait(60, untilChange = true), parse("""{"type":"wait","until":"change","frames":60}""").getOrThrow())
        assertTrue(parse("""{"type":"wait","until":"later"}""").isFailure)
    }

    @Test
    fun releaseNeedsAnExplicitConfirmation() {
        val game = FakeGame(Screen.Overworld(awaiting = Awaiting.INPUT))
        val outcome = PcPlans.release.run(GameAction.Release(MonId.parse("mon:c50a0956.76f3a6fb")!!, confirm = false), game.context())
        val error = assertIs<ActionError.InvalidParameter>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals("confirm", error.parameter)
        assertTrue(game.presses.isEmpty())
    }

    @Test
    fun aDragHoldsTheStylusAllTheWayToTheEnd() {
        val game = FakeGame(Screen.Unknown("alph_puzzle", Awaiting.INPUT))
        val outcome = MoreActions.drag.plan.run(GameAction.Drag(TouchPoint(48, 112), TouchPoint(104, 88), 10), game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(1, game.touches.size, "one continuous touch, never lifted on the way")
        assertEquals(TouchPoint(48, 112), game.touchFrames.first())
        assertEquals(TouchPoint(104, 88), game.touchFrames.last())
        // Small moves: never more than the per-frame share of the distance.
        game.touchFrames.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(b.x - a.x) <= 6 && kotlin.math.abs(b.y - a.y) <= 3, "$a → $b") }
    }

    @Test
    fun waitUntilChangeStopsOnTheFirstChange() {
        val game = FakeGame(Screen.Dialogue(TextSource.FIELD, null, "Hi", Awaiting.INPUT))
        game.onFrame = { frame, screen -> if (frame == 40L) Screen.Overworld(awaiting = Awaiting.INPUT) else screen }
        val scope = game.scope()
        val outcome = BasicPlans.wait.run(GameAction.Wait(untilChange = true), PlanContext(scope, game))
        assertEquals(ActionOutcome.Done(), outcome)
        assertTrue(scope.framesUsed < 60, "stopped right after the change, not after the whole wait (${scope.framesUsed})")
    }

    @Test
    fun waitUntilChangeSaysWhenNothingChanged() {
        val game = FakeGame(Screen.Unknown("alph_puzzle", Awaiting.INPUT))
        val outcome = assertIs<ActionOutcome.Done>(BasicPlans.wait.run(GameAction.Wait(30, untilChange = true), game.context()))
        assertTrue(outcome.detail!!.startsWith("nothing changed"))
    }

    @Test
    fun theBagViewIsOneLinePerPocket() {
        val bag = StateView.bag(
            listOf(
                BagPocket("medicine", listOf(BagItem(Named(ItemId(17), "Potion"), 3), BagItem(Named(ItemId(26), "Super Potion"), 1))),
                BagPocket("mail", emptyList()),
            ),
        )
        assertEquals(setOf("medicine"), bag.keys)
        assertEquals("item:17 Potion x3, item:26 Super Potion x1", bag["medicine"]!!.jsonPrimitive.content)
    }
}
