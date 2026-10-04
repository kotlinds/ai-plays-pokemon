package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionChainsTest {

    private val mon = MonId.parse("mon:00000001.00000002")!!
    private fun use(item: String) = GameAction.UseItem(ItemRef(item), mon)

    @Test
    fun consecutiveFieldItemUsesShareOneBagSession() {
        val chain = listOf(use("Revive"), use("Full Restore"), GameAction.SaveGame, use("Potion"))
        val merged = ActionChains.coalesce(chain, inBattle = false)
        assertEquals(3, merged.size)
        assertEquals(listOf("Revive", "Full Restore"), (merged[0] as GameAction.UseItem).uses.map { it.item.raw })
        assertEquals(GameAction.SaveGame, merged[1])
        assertEquals(listOf("Potion"), (merged[2] as GameAction.UseItem).uses.map { it.item.raw })
    }

    @Test
    fun inBattleEveryItemUseStaysItsOwnTurn() {
        val chain = listOf(use("Revive"), use("Full Restore"))
        assertEquals(chain, ActionChains.coalesce(chain, inBattle = true))
    }

    private fun state(screen: Screen, field: Boolean = true) = GameState(
        0, screen, null, emptyList(), null, null,
        if (field) FieldState(1, "Town", 10, 10, 0, Direction.SOUTH, MovementMode.WALK, false, emptyList()) else null,
    )

    private val nothingToRead = ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "No menu is open")

    @Test
    fun aSpareAdvanceDialogueBackInTheFieldIsSkipped() {
        val walking = state(Screen.Overworld(awaiting = Awaiting.INPUT))
        assertTrue(ActionChains.isSpareAdvance(GameAction.AdvanceDialogue, nothingToRead, walking))
    }

    @Test
    fun anAdvanceDialogueRefusedElsewhereStillStopsTheChain() {
        val battle = state(Screen.Battle(Awaiting.INPUT), field = false)
        assertFalse(ActionChains.isSpareAdvance(GameAction.AdvanceDialogue, nothingToRead, battle))
        val walking = state(Screen.Overworld(awaiting = Awaiting.INPUT))
        assertFalse(ActionChains.isSpareAdvance(GameAction.SaveGame, nothingToRead, walking))
        assertFalse(ActionChains.isSpareAdvance(GameAction.AdvanceDialogue, ActionError.Timeout("x"), walking))
    }

    @Test
    fun aMisspelledParameterIsRefusedWithTheValidOnes() {
        val registry = ActionRegistry.of()
        val json = buildJsonObject {
            put("type", JsonPrimitive("step"))
            put("direction", JsonPrimitive("north"))
            put("count", JsonPrimitive(3))
        }
        val error = (registry.parse(json, ActionMode.ASSISTED).exceptionOrNull() as ActionException).error as ActionError.InvalidParameter
        assertEquals("count", error.value)
        assertTrue("tiles" in error.allowed)
    }
}
