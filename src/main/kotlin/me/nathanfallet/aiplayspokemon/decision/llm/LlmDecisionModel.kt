package me.nathanfallet.aiplayspokemon.decision.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel

/**
 * [DecisionModel] backed by a generative LLM (OpenAI, Anthropic, Gemini through OpenRouter, a local
 * Ollama model...), called through Koog.
 *
 * The LLM gets the same state, instructions and options as any other decision model, and answers in
 * the [LlmAnswerFormat] (reasoning, optional note and sequence, then the choice). An invalid answer
 * is sent back to the model once with the error, never guessed. An LLM gives no calibrated
 * probability: the choice gets 1, confidence is null.
 */
class LlmDecisionModel(
    private val provider: LlmProvider,
    private val modelId: String,
    apiKey: String?,
    private val thinking: Boolean = false,
) : DecisionModel {

    override val name = "${provider.label} · $modelId${if (thinking) " (thinking)" else ""}"

    private val executor: PromptExecutor = provider.executor(apiKey)
    private val model = provider.model(modelId)

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val state = "State:\n${json.encodeToString(JsonObject.serializer(), request.state)}"
        var inputTokens = 0
        var previous: Pair<String, String>? = null // (invalid answer, error) to correct on retry
        repeat(MAX_ATTEMPTS) {
            val decisionPrompt = prompt("decision", params = provider.params(thinking)) {
                system(LlmAnswerFormat.instructions(request))
                user(state)
                previous?.let { (answer, error) ->
                    assistant(answer)
                    user("Invalid answer: $error Reply again with only the JSON object.")
                }
            }
            val response = try {
                executor.execute(decisionPrompt, model)
            } catch (error: Exception) {
                throw classify(error)
            }
            val meta = response.metaInfo as? ResponseMetaInfo
            inputTokens += meta?.inputTokensCount ?: 0
            val text = response.textContent()
            try {
                val answer = LlmAnswerFormat.parse(text, request)
                return answer.toChoiceResult(request, model = meta?.modelId ?: modelId, inputTokens = inputTokens)
            } catch (invalid: LlmAnswerFormat.InvalidAnswer) {
                previous = text to (invalid.message ?: "invalid answer")
            }
        }
        throw DecisionException("$name gave no valid answer: ${previous?.second}", retryable = true)
    }

    internal companion object {
        const val MAX_ATTEMPTS = 2
        private val json = Json { explicitNulls = false }

        /** Authentication and request errors won't fix themselves: stop instead of retrying forever. */
        fun classify(error: Exception): Exception {
            if (error is DecisionException) return error
            val message = error.message.orEmpty()
            val fatal = listOf("401", "403", "invalid x-api-key", "invalid_api_key", "authentication", "404")
                .any { message.contains(it, ignoreCase = true) }
            return DecisionException(message.ifEmpty { error.toString() }, retryable = !fatal, cause = error)
        }
    }
}
