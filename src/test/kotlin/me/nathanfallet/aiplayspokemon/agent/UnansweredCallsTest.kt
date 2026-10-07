package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.state.EventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.mcp.DeliveryTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "The chain finished after the client gave up": what the act did is given back with the next answer, wired like the
 * MCP server ([DeliveryTracker] confirming the event feed and [UnansweredCalls] together, see `GameSession`).
 */
class UnansweredCallsTest {
    private var clock = 0L
    private val feed = EventFeed(EventLog(), autoConfirm = false)
    private val unanswered = UnansweredCalls()
    private val tracker = DeliveryTracker(confirm = { feed.confirm(); unanswered.delivered() }, now = { clock })

    private fun outcome(ok: Boolean, vararg performed: String, detail: String? = null) = buildJsonObject {
        put("ok", ok)
        put("performed", JsonArray(performed.map(::JsonPrimitive)))
        detail?.let { put("detail", it) }
    }

    /** One act call lasting [seconds] (cancelled by the client or not): returns what its answer gave back, then records its own outcome. */
    private fun act(seconds: Long, actions: List<String>, outcome: JsonObject, cancelled: Boolean = false): Map<String, kotlinx.serialization.json.JsonElement> {
        tracker.arrived(clock)
        val call = tracker.started("act")
        clock += seconds * 1000
        feed.take()
        val given = unanswered.describe()
        unanswered.answered(actions, outcome)
        tracker.answered(call, cancelled)
        clock += 1_000
        return given
    }

    @Test
    fun aSaveAfterALongGoToTheClientGaveUpOnIsToldWithTheNextAnswer() {
        assertTrue(act(2, listOf("go_to(4,0)"), outcome(true, "go_to(4,0)")).isEmpty())
        // go_to then save_game: 150 s, the client cancelled at 60 s; the chain still ran to its end.
        act(150, listOf("go_to(Seafoam Islands 1F)", "save_game"), outcome(true, "go_to(Seafoam Islands 1F)", "save_game", detail = "save_game: saved"), cancelled = true)
        val next = act(1, listOf("wait"), outcome(true, "wait"))
        val previous = (next["previous_calls"] as JsonArray).single().jsonObject
        assertTrue(previous["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("go_to(Seafoam Islands 1F)", "save_game"), (previous["performed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("save_game: saved", previous["detail"]!!.jsonPrimitive.content)
        assertTrue("previous_calls_note" in next)
        // That answer arrived: nothing is given again.
        assertTrue(act(1, listOf("wait"), outcome(true, "wait")).isEmpty())
    }

    @Test
    fun answersLostOneAfterTheOtherAreAllGivenBack() {
        act(70, listOf("save_game"), outcome(false, detail = null), cancelled = true)
        act(70, listOf("go_to(frontier)"), outcome(true, "go_to(frontier)"), cancelled = true)
        val next = act(1, listOf("wait"), outcome(true, "wait"))
        assertEquals(listOf("save_game", "go_to(frontier)"), (next["previous_calls"] as JsonArray).map { (it.jsonObject["actions"] as JsonArray).single().jsonPrimitive.content })
    }
}
