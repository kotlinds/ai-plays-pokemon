package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmDecisionModelTest {

    private val buttons = setOf("a", "b", "up", "down")

    @Test
    fun `reads the choice field`() {
        assertEquals("up", LlmDecisionModel.parseChoice("""{"thought": "stairs are north", "choice": "up"}""", buttons))
        assertEquals("a", LlmDecisionModel.parseChoice("""```json\n{"choice":"A"}\n```""", buttons))
        assertEquals("stairs are north", LlmDecisionModel.parseThought("""{"thought": "stairs are north", "choice": "up"}"""))
    }

    @Test
    fun `falls back to the first option mentioned`() {
        assertEquals("down", LlmDecisionModel.parseChoice("I will press down to leave the room.", buttons))
        assertNull(LlmDecisionModel.parseChoice("I don't know.", buttons))
    }

    /** Real call through Koog, only when a local Ollama with the default model is running. */
    @Test
    fun `plays through a local Ollama when available`() = runTest {
        val available = runCatching { URI("http://localhost:11434/api/tags").toURL().readText() }
            .getOrNull()?.contains(LlmProvider.OLLAMA.defaultModel) == true
        if (!available) return@runTest

        val model = LlmDecisionModel(LlmProvider.OLLAMA, LlmProvider.OLLAMA.defaultModel, apiKey = null)
        val result = model.choose(
            ChoiceRequest(
                state = buildJsonObject { put("dialogue", "Press A to continue.") },
                instructions = "Choose the button to press.",
                options = mapOf("a" to "A button", "b" to "B button"),
            ),
        )
        assertContains(setOf("a", "b"), result.choice)
    }
}
