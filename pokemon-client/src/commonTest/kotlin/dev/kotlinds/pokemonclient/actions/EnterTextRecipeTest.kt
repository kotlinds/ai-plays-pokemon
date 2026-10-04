package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `enter_text` on a scripted naming keyboard laid out like the game's (UPPER / lower / Others pages, B deletes the
 * last character, OK ends): letter case and symbols switch pages, the maximum length is refused before any press,
 * and an empty text only erases then confirms (the game keeps its default name: checked live on a capture's
 * nickname, the Poliwag kept "POLIWAG").
 */
class EnterTextRecipeTest {

    private val pages = mapOf(
        "upper" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ ,.",
        "lower" to "abcdefghijklmnopqrstuvwxyz ,.",
        "others" to "0123456789!?-♂♀",
    )

    /** The keyboard as the game shows it: page buttons, the page's keys, then OK. */
    private class Keyboard(val pages: Map<String, String>, var buffer: String, val max: Int = 10) {
        var page = "upper"
        var done: String? = null

        fun screen(cursor: Int = 0): Screen.Keyboard {
            val entries = listOf("upper", "lower", "others").map { Entry("page:$it", it) } +
                pages.getValue(page).map { Entry("key:$it", it.toString()) } + Entry("option:ok", "OK")
            return Screen.Keyboard("pokemon", page, buffer, max, entries, Cursor.At(cursor), Topology.vertical(entries.size))
        }
    }

    private fun ui(keyboard: Keyboard): ScriptedUi {
        val ui = ScriptedUi(keyboard.screen())
        ui.onA = { screen, id ->
            val at = ((screen as Screen.Keyboard).cursor as Cursor.At).index
            when {
                id == null -> screen
                id.startsWith("page:") -> { keyboard.page = id.removePrefix("page:"); keyboard.screen(at) }
                id.startsWith("key:") -> { if (keyboard.buffer.length < keyboard.max) keyboard.buffer += id.removePrefix("key:"); keyboard.screen(at) }
                id == "option:ok" -> { keyboard.done = keyboard.buffer; OVERWORLD }
                else -> screen
            }
        }
        ui.onB = { screen ->
            val at = ((screen as? Screen.Keyboard)?.cursor as? Cursor.At)?.index ?: 0
            keyboard.buffer = keyboard.buffer.dropLast(1)
            keyboard.screen(at)
        }
        return ui
    }

    @Test
    fun mixedCaseAndSymbolsSwitchPagesAndEveryKeyIsReadBack() {
        val keyboard = Keyboard(pages, buffer = "POLIWAG")
        val ui = ui(keyboard)
        val done = assertIs<ActionOutcome.Done>(TextPlans.enterText.run(GameAction.EnterText("Po-li 2!?♀"), ui.context()))
        assertEquals("typed Po-li 2!?♀", done.detail)
        assertEquals("Po-li 2!?♀", keyboard.done)
        // The default name was erased first: seven B presses.
        assertEquals(7, ui.game.presses.count { it == Button.B })
    }

    @Test
    fun aTextLongerThanTheKeyboardAllowsIsRefusedBeforeAnyPress() {
        val keyboard = Keyboard(pages, buffer = "POLIWAG")
        val ui = ui(keyboard)
        val failed = assertIs<ActionOutcome.Failed>(TextPlans.enterText.run(GameAction.EnterText("ABCDEFGHIJK"), ui.context()))
        val error = assertIs<ActionError.InvalidParameter>(failed.error)
        assertEquals(listOf("at most 10 characters"), error.allowed)
        assertTrue(ui.game.presses.isEmpty())
        assertEquals("POLIWAG", keyboard.buffer)
    }

    @Test
    fun aCharacterTheKeyboardDoesntHaveIsATypedError() {
        val keyboard = Keyboard(pages, buffer = "")
        val ui = ui(keyboard)
        val failed = assertIs<ActionOutcome.Failed>(TextPlans.enterText.run(GameAction.EnterText("A#"), ui.context()))
        assertIs<ActionError.NotOnScreen>(failed.error)
        assertEquals(null, keyboard.done)
    }

    @Test
    fun anEmptyTextErasesAndConfirmsSoTheGameKeepsItsDefaultName() {
        val keyboard = Keyboard(pages, buffer = "POLIWAG")
        val ui = ui(keyboard)
        assertIs<ActionOutcome.Done>(TextPlans.enterText.run(GameAction.EnterText(""), ui.context()))
        assertEquals("", keyboard.done)
        assertEquals(7, ui.game.presses.count { it == Button.B })
    }
}
