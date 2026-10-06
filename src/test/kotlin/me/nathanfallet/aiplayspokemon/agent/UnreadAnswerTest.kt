package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.view.AgentView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * The next turn's state of our own loop ([AgentSession.prepare]) is always a new reading of the game, carrying what the
 * last act's answer told ([UnreadAnswer]): its outcome and the game's messages and events, once.
 */
class UnreadAnswerTest {

    private fun messages(vararg texts: String) = JsonArray(texts.map(::JsonPrimitive))

    private val outcome = buildJsonObject {
        put("ok", true)
        put("performed", messages("go_to(warp:0)"))
        put("not_done", messages("advance_dialogue"))
        put("not_done_code", "NOT_OFFERED")
    }

    private val answerView = buildJsonObject {
        put(AgentView.MESSAGES, messages("You found a POTION!"))
        put("screen", "overworld")
        put("version", 7)
    }

    /** A new reading: the game moved on (a message since, another screen, a newer version). */
    private val fresh = buildJsonObject {
        put(AgentView.MESSAGES, messages("Hello!"))
        put(AgentView.EVENTS, messages("screen changed"))
        put("screen", "dialogue")
        put("version", 9)
    }

    @Test
    fun theNewReadingCarriesTheAnswersOutcomeAndMessagesFirst() {
        val unread = UnreadAnswer().apply { keep(outcome, answerView) }
        val state = unread.into(fresh)
        // The state is the new reading's (never the answer's older one)...
        assertEquals("dialogue", state["screen"]!!.jsonPrimitive.content)
        assertEquals("9", state["version"]!!.jsonPrimitive.content)
        // ...with the answer's outcome, and its messages before the new ones.
        assertEquals(true, state["ok"]!!.jsonPrimitive.boolean)
        assertEquals("NOT_OFFERED", state["not_done_code"]!!.jsonPrimitive.content)
        assertEquals(listOf("You found a POTION!", "Hello!"), state[AgentView.MESSAGES]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("screen changed"), state[AgentView.EVENTS]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theAnswerIsCarriedOnceThenForgotten() {
        val unread = UnreadAnswer().apply { keep(outcome, answerView) }
        unread.into(fresh)
        // The next turn reads the game alone: nothing piles up from turn to turn.
        assertSame(fresh, unread.into(fresh))
        assertFalse("ok" in unread.into(fresh))
    }

    @Test
    fun withoutAnAnswerTheReadingIsUnchanged() {
        assertSame(fresh, UnreadAnswer().into(fresh))
    }

    @Test
    fun messagesOfTheAnswerAreKeptWhenTheReadingHasNone() {
        val unread = UnreadAnswer().apply { keep(outcome, answerView) }
        val state = unread.into(JsonObject(mapOf("screen" to JsonPrimitive("overworld"))))
        assertEquals(listOf("You found a POTION!"), state[AgentView.MESSAGES]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
