package me.nathanfallet.aiplayspokemon.decision.jev

import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel

/** [DecisionModel] backed by Jev: each decision is one request with a single Choice question. */
class JevDecisionModel(private val client: JevClient) : DecisionModel {

    override val name = "Jev (${client.model})"

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val response = client.ask(
            SystemOneRequest(
                model = client.model,
                state = request.state,
                questions = mapOf(
                    QUESTION_ID to Question.Choice(
                        instructions = JsonPrimitive(request.instructions),
                        criteria = request.options,
                    ),
                ),
            ),
        )
        val answer = response.answers[QUESTION_ID] as? Answer.Choice
            ?: error("Jev didn't answer the choice question")
        return ChoiceResult(
            choice = answer.choice,
            probabilities = answer.probabilities,
            confidence = answer.confidence,
            model = response.model,
            inputTokens = response.usage?.inputTokens ?: 0,
        )
    }

    private companion object {
        /** Our id for the question; Jev never sees it, it only keys the answer. */
        const val QUESTION_ID = "decision"
    }
}
