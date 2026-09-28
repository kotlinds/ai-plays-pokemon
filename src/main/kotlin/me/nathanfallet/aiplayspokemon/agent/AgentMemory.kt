package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.game.Observation

/**
 * Short-term memory: the last few buttons pressed and what visibly changed after each one.
 *
 * Jev is stateless (every request starts from scratch), while a human remembers what they just
 * did. This only records facts (position, map, mode before/after), never advice: interpreting them
 * (e.g. "I'm walking into a wall") is the model's job.
 */
class AgentMemory(private val size: Int = 12) {

    private val history = ArrayDeque<Step>()

    fun record(press: ButtonPress, before: Observation, after: Observation) {
        val changes = buildList {
            val from = before.location
            val to = after.location
            when {
                from == null || to == null -> Unit
                from.mapId != to.mapId -> add("map changed to ${to.mapName}, position (${to.x}, ${to.y})")
                from.x != to.x || from.y != to.y -> add("position (${from.x}, ${from.y}) -> (${to.x}, ${to.y})")
                else -> add("position unchanged (${to.x}, ${to.y})")
            }
            if (before.mode != after.mode) add("mode ${before.mode.name.lowercase()} -> ${after.mode.name.lowercase()}")
        }
        history.addLast(Step(press, changes.joinToString("; ").ifEmpty { "no change detected" }))
        while (history.size > size) history.removeFirst()
    }

    /** Oldest first, as sent to the model. */
    fun describe(): JsonArray = JsonArray(history.map { step ->
        buildJsonObject {
            put("button", step.press.id)
            put("what_changed", step.changes)
        }
    })

    fun clear() = history.clear()

    private data class Step(val press: ButtonPress, val changes: String)
}
