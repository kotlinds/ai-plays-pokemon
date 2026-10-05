package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outcome of every `act` whose answer isn't known to have reached the agent yet (oldest first, at most [max]).
 *
 * A client can give up on a call (its timeout) while the chain still runs to its end here: the answer, and with it
 * what the chain did (`ok`, `performed`, `detail`, `error`, `not_done`), is lost. The events it carried are given again
 * by the event feed (`messages_repeated`), but not that outcome, and a step that says nothing (a `go_to`, a save whose
 * message was already read) would leave the agent guessing. So the outcomes are kept until an answer is confirmed
 * ([delivered]), and every answer meanwhile gives them back ([describe]): the agent learns, for instance, that its
 * `save_game` succeeded after its client gave up.
 */
class UnansweredCalls(private val max: Int = MAX) {
    private val calls = ArrayDeque<JsonObject>()

    /** An act answered with [outcome] for [actions] (keys): kept until an answer is known to have arrived. */
    fun answered(actions: List<String>, outcome: JsonObject) {
        calls.addLast(buildJsonObject {
            put("actions", JsonArray(actions.map(::JsonPrimitive)))
            outcome.forEach { (k, v) -> put(k, v) }
        })
        while (calls.size > max) calls.removeFirst()
    }

    /** The last answer reached the agent: everything kept was told (it was in that answer or an earlier one). */
    fun delivered() = calls.clear()

    /**
     * What the agent must read again: `previous_calls` (each act's outcome) and a note, or nothing when every answer
     * arrived. Called before the current call's own outcome is added: whatever is kept then was lost (the verdict on
     * the previous answer is given when a call arrives, see `DeliveryTracker`).
     */
    fun describe(): Map<String, kotlinx.serialization.json.JsonElement> =
        if (calls.isEmpty()) emptyMap() else mapOf("previous_calls" to JsonArray(calls.toList()), "previous_calls_note" to JsonPrimitive(NOTE))

    private companion object {
        /** Outcomes kept at most (a client timing out again and again). */
        const val MAX = 5
        const val NOTE = "the answer of these act calls didn't reach you (your client timed out?), but they ran to their end: " +
            "`previous_calls` says what each one did (ok, performed, detail, error, not_done)"
    }
}
