package me.nathanfallet.aiplayspokemon.decision

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * A decision model: given a situation, picks one option from a closed set, with a probability for
 * each option. This is the "brain" of the player.
 *
 * The agent only depends on this interface, not on a specific model or API. Implementations:
 * - [me.nathanfallet.aiplayspokemon.decision.jev.JevDecisionModel]: Jev, TypeSafe's System One
 *   model, the one this project is about (fast, calibrated probabilities for every option);
 * - [me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel]: any LLM Koog can talk to
 *   (OpenAI, Anthropic, OpenRouter, local Ollama...), to compare how different AIs play.
 */
interface DecisionModel {
    /** Shown in the UI, e.g. "Jev (jev-latest)". */
    val name: String

    suspend fun choose(request: ChoiceRequest): ChoiceResult
}

/** "Given [state], following [instructions], which of [options] (id → description)?" */
data class ChoiceRequest(
    val state: JsonObject,
    val instructions: String,
    val options: Map<String, String>,
)

data class ChoiceResult(
    /** The chosen option id (one of the request's options). */
    val choice: String,
    /** Probability of each option (they sum to 1); models that can't estimate them put 1 on the choice. */
    val probabilities: Map<String, Double>,
    /** How certain the model is (0..1), or null when the model can't tell. */
    val confidence: Double?,
    /** The exact model that answered, e.g. "jev-1.13.0". */
    val model: String,
    val inputTokens: Int,
    /** A short explanation of the choice, for models that give one (LLMs); shown in the UI. */
    val thought: String? = null,
)

/**
 * A failed decision. [retryable] is false when trying again can't help (bad API key, invalid
 * request...), which stops the player instead of retrying forever.
 */
open class DecisionException(message: String, val retryable: Boolean, cause: Throwable? = null) : IOException(message, cause)

/** The decision models the app can use, selectable in the UI. */
enum class DecisionBackend(val label: String) {
    JEV("Jev"),
    LLM("LLM"),
}
