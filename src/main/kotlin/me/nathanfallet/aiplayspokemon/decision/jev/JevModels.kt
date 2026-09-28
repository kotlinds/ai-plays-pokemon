package me.nathanfallet.aiplayspokemon.decision.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/*
 * Request/response models of TypeSafe's System One API (the API that serves Jev).
 * Reference: https://docs.typesafe.ai/api
 *
 * The idea: we send a `state` (any JSON describing the situation) and a map of typed `questions`.
 * Jev answers each question with a typed answer (a choice among our options, a score, or a yes/no
 * probability), never free text. Our code stays in control; Jev only makes the narrow decision.
 */

@Serializable
data class SystemOneRequest(
    val model: String,
    /** The situation to evaluate: a string, or structured JSON (what we use: the game state). */
    val state: JsonElement,
    /** Questions keyed by an id we choose; answers come back under the same ids. */
    val questions: Map<String, Question>,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface Question {
    /** "Which of these options?" Returns the chosen option and the probability of every option. */
    @Serializable
    @SerialName("choice")
    data class Choice(
        val instructions: JsonElement,
        /** Option name -> description of when to pick it (up to 255 options). */
        val criteria: Map<String, String?>,
    ) : Question

    /** "Where on this ordered scale?" Returns a probability-weighted level. */
    @Serializable
    @SerialName("score")
    data class Score(
        val instructions: JsonElement,
        /** Ordered level descriptions (2 to 10). */
        val criteria: List<String>,
    ) : Question

    /** "Is this true?" Returns the probability of yes. */
    @Serializable
    @SerialName("noul")
    data class Noul(
        val instructions: JsonElement,
    ) : Question
}

@Serializable
data class SystemOneResponse(
    /** The exact model that answered, e.g. "jev-1.13.0". */
    val model: String,
    val answers: Map<String, Answer>,
    val usage: Usage? = null,
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface Answer {
    @Serializable
    @SerialName("choice")
    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        /** How certain Jev is (0..1), derived from the probability distribution. */
        val confidence: Double,
    ) : Answer

    @Serializable
    @SerialName("score")
    data class Score(
        val score: Double,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : Answer

    @Serializable
    @SerialName("noul")
    data class Noul(
        /** Probability that the answer is yes (0..1). */
        val noul: Double,
    ) : Answer
}
