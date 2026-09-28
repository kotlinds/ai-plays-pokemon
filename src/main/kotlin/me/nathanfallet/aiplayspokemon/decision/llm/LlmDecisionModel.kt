package me.nathanfallet.aiplayspokemon.decision.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel

/**
 * [DecisionModel] backed by a generative LLM (OpenAI, Anthropic, Gemini through OpenRouter, a local
 * Ollama model...), called through Koog.
 *
 * The LLM gets the same state, instructions and options as Jev. Since it generates text instead of
 * picking an option, we ask for a tiny JSON object `{"thought": "...", "choice": "<option id>"}`:
 * the thought is shown in the UI (it's fun to compare how models reason), the choice is validated
 * against the options. An LLM gives no calibrated probability: the choice gets 1, confidence is null.
 */
class LlmDecisionModel(
    private val provider: LlmProvider,
    private val modelId: String,
    apiKey: String?,
) : DecisionModel {

    override val name = "${provider.label} · $modelId"

    private val executor: PromptExecutor = provider.executor(apiKey)
    private val model = provider.model(modelId)

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val options = request.options.entries.joinToString("\n") { (id, description) -> "- $id: $description" }
        val decisionPrompt = prompt("decision") {
            system(
                """
                |${request.instructions}
                |
                |Options (answer with exactly one id):
                |$options
                |
                |Reply with only a JSON object, no other text:
                |{"thought": "<one short sentence about what you see and intend>", "choice": "<option id>"}
                """.trimMargin()
            )
            user("State:\n${json.encodeToString(JsonObject.serializer(), request.state)}")
        }

        val response = executor.execute(decisionPrompt, model)
        val text = response.textContent()
        val choice = parseChoice(text, request.options.keys)
            ?: error("$name didn't answer with a valid option: ${text.take(200)}")
        return ChoiceResult(
            choice = choice,
            probabilities = request.options.keys.associateWith { if (it == choice) 1.0 else 0.0 },
            confidence = null,
            model = (response.metaInfo as? ResponseMetaInfo)?.modelId ?: modelId,
            inputTokens = (response.metaInfo as? ResponseMetaInfo)?.inputTokensCount ?: 0,
            thought = parseThought(text),
        )
    }

    internal companion object {
        private val json = Json { explicitNulls = false }
        private val CHOICE = Regex(""""choice"\s*:\s*"([^"]+)"""")
        private val THOUGHT = Regex(""""thought"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        fun parseThought(text: String): String? = THOUGHT.find(text)?.groupValues?.get(1)

        /**
         * Reads the chosen option from the reply: the `choice` field when present, otherwise the
         * first option id mentioned (models don't always follow the format perfectly).
         */
        fun parseChoice(text: String, options: Set<String>): String? {
            CHOICE.find(text)?.groupValues?.get(1)?.trim()?.lowercase()?.let { if (it in options) return it }
            return options.firstOrNull { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        }
    }
}
