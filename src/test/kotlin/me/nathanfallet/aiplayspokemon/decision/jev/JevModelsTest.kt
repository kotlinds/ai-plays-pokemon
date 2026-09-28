package me.nathanfallet.aiplayspokemon.decision.jev

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks our models against the JSON shapes documented at https://docs.typesafe.ai/api. */
class JevModelsTest {

    private val json = JevClient.json

    @Test
    fun `choice question is encoded with its type discriminator`() {
        val request = SystemOneRequest(
            model = "jev-latest",
            state = buildJsonObject { put("hp", 12) },
            questions = mapOf(
                "action" to Question.Choice(
                    instructions = JsonPrimitive("What next?"),
                    criteria = mapOf("attack" to "Use a move", "run" to null),
                ),
            ),
        )
        assertEquals(
            """{"model":"jev-latest","state":{"hp":12},"questions":{"action":{"type":"choice",""" +
                """"instructions":"What next?","criteria":{"attack":"Use a move","run":null}}}}""",
            json.encodeToString(SystemOneRequest.serializer(), request),
        )
    }

    @Test
    fun `documented response example is decoded`() {
        val response = json.decodeFromString(
            SystemOneResponse.serializer(),
            """
            {
              "model": "jev-1.13.0",
              "answers": {
                "department": {
                  "type": "choice",
                  "choice": "billing",
                  "probabilities": { "billing": 0.88, "technical": 0.12, "sales": 0.0 },
                  "confidence": 0.81
                },
                "is_urgent": { "type": "noul", "noul": 0.95 }
              },
              "usage": { "input_tokens": 318, "output_tokens": 34 }
            }
            """.trimIndent(),
        )
        assertEquals("jev-1.13.0", response.model)
        assertEquals(Answer.Choice("billing", mapOf("billing" to 0.88, "technical" to 0.12, "sales" to 0.0), 0.81), response.answers["department"])
        assertEquals(Answer.Noul(0.95), response.answers["is_urgent"])
        assertEquals(318, response.usage?.inputTokens)
    }
}
