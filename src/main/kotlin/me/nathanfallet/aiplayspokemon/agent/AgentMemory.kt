package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.game.Observation

/**
 * What the player remembers between two decisions.
 *
 * Models are stateless (every request starts from scratch), while a human remembers what they did,
 * where they have been and what people told them. This memory only keeps facts and the model's own
 * words, never advice: interpreting them (e.g. "I've been pressing A for a while and nothing
 * happens") is the model's job.
 *
 * - [recent presses][describe]: the last buttons and what changed, repeated presses grouped;
 * - places: the last maps visited and how many presses were spent in each;
 * - dialogues: the last texts read;
 * - thoughts: the model's own last explanations (LLMs), so it can follow its plan.
 */
class AgentMemory(
    private val maxPresses: Int = 30,
    private val maxPlaces: Int = 8,
    private val maxDialogues: Int = 6,
    private val maxThoughts: Int = 5,
) {
    private val presses = ArrayDeque<Press>()
    private val places = ArrayDeque<Place>()
    private val dialogues = ArrayDeque<String>()
    private val thoughts = ArrayDeque<String>()

    fun record(press: ButtonPress, before: Observation, after: Observation, thought: String? = null) {
        recordPress(press, changes(before, after))
        recordPlace(after)
        after.dialogue?.trim()?.takeIf { it.isNotEmpty() && it != dialogues.lastOrNull() }?.let { dialogues.addBounded(it, maxDialogues) }
        thought?.takeIf { it.isNotBlank() }?.let { thoughts.addBounded(it, maxThoughts) }
    }

    /** The memory part of the state sent to the model. */
    fun describe(): JsonObject = buildJsonObject {
        put("recent_presses", JsonArray(presses.map { press ->
            buildJsonObject {
                put("button", press.button.id)
                if (press.times > 1) put("times", press.times)
                put("what_changed", press.changes)
            }
        }))
        put("places_visited", JsonArray(places.map { place ->
            buildJsonObject {
                put("place", place.name)
                put("presses_spent", place.presses)
            }
        }))
        if (dialogues.isNotEmpty()) put("dialogues_read", JsonArray(dialogues.map(::JsonPrimitive)))
        if (thoughts.isNotEmpty()) put("your_recent_thoughts", JsonArray(thoughts.map(::JsonPrimitive)))
    }

    fun clear() {
        presses.clear()
        places.clear()
        dialogues.clear()
        thoughts.clear()
    }

    private fun changes(before: Observation, after: Observation): String = buildList {
        val from = before.location
        val to = after.location
        when {
            from == null || to == null -> Unit
            from.mapId != to.mapId -> add("map changed to ${to.mapName}, position (${to.x}, ${to.y})")
            from.x != to.x || from.y != to.y -> add("position (${from.x}, ${from.y}) -> (${to.x}, ${to.y})")
            else -> add("position unchanged (${to.x}, ${to.y})")
        }
        if (before.mode != after.mode) add("mode ${before.mode.name.lowercase()} -> ${after.mode.name.lowercase()}")
    }.joinToString("; ").ifEmpty { "no change detected" }

    /** Consecutive identical presses with the same outcome are grouped ("a", 8 times, nothing changed). */
    private fun recordPress(button: ButtonPress, changes: String) {
        val last = presses.lastOrNull()
        if (last != null && last.button == button && last.changes == changes) last.times++
        else presses.addBounded(Press(button, changes), maxPresses)
    }

    private fun recordPlace(after: Observation) {
        val name = after.location?.mapName ?: return
        val last = places.lastOrNull()
        if (last != null && last.name == name) last.presses++
        else places.addBounded(Place(name), maxPlaces)
    }

    private fun <T> ArrayDeque<T>.addBounded(element: T, max: Int) {
        addLast(element)
        while (size > max) removeFirst()
    }

    private class Press(val button: ButtonPress, val changes: String, var times: Int = 1)

    private class Place(val name: String, var presses: Int = 1)
}
