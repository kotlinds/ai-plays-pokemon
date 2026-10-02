package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmAnswerFormatTest {

    private val request = ChoiceRequest(
        state = JsonObject(emptyMap()),
        instructions = "Choose.",
        options = mapOf("a" to "A", "b" to "B", "up" to "Up", "exit_0" to "Stairs"),
        allowSequence = true,
        allowNote = true,
    )

    @Test
    fun `reads choice, reasoning, note and sequence`() {
        val answer = LlmAnswerFormat.parse(
            """Sure! ```json
            {"reasoning": "I see a door north.", "note": "Leave the house", "choice": "up", "then": ["up", "exit_0", "nope"]}
            ```""",
            request,
        )
        assertEquals("up", answer.choice)
        assertEquals("I see a door north.", answer.reasoning)
        assertEquals("Leave the house", answer.note)
        assertEquals(listOf("up", "exit_0"), answer.then)
    }

    @Test
    fun `normalizes harmless variations of option ids`() {
        assertEquals("a", LlmAnswerFormat.parse("""{"reasoning": "", "choice": "A button"}""", request).choice)
        assertEquals("up", LlmAnswerFormat.parse("""{"reasoning": "", "choice": "D-pad Up"}""", request).choice)
    }

    /** Free text is never scanned for option names: "a door" must not become the A button. */
    @Test
    fun `rejects answers without a valid choice`() {
        assertFailsWith<LlmAnswerFormat.InvalidAnswer> { LlmAnswerFormat.parse("I will press a button to open a door.", request) }
        assertFailsWith<LlmAnswerFormat.InvalidAnswer> { LlmAnswerFormat.parse("""{"reasoning": "a door", "choice": "walk"}""", request) }
    }
}
