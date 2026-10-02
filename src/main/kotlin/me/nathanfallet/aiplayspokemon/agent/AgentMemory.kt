package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.agent.actions.AgentAction
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.Tile

/**
 * What the player remembers between two decisions.
 *
 * Models are stateless (every request starts from scratch), while a human remembers what they did,
 * where they have been and what people told them. This memory only keeps facts and the model's own
 * words, never advice: interpreting them is the model's job.
 *
 * - recent actions and exactly what changed after each one (from [Observation.facts]), repeats grouped;
 * - how many times the same action was done in the same situation, and how long since any progress;
 * - places visited, dialogues read, the model's own note and last thoughts;
 * - the explored map: every tile seen so far on each map, with the tiles walked on.
 */
class AgentMemory(
    private val maxActions: Int = 30,
    private val maxPlaces: Int = 8,
    private val maxDialogues: Int = 6,
    private val maxThoughts: Int = 3,
) {
    private val actions = ArrayDeque<Step>()
    private val places = ArrayDeque<Place>()
    private val dialogues = ArrayDeque<String>()
    private val thoughts = ArrayDeque<String>()

    /** Times each action was taken in each situation (map, tile, mode, screen). */
    private val repeats = HashMap<String, Int>()

    /** Per map id: every tile seen, and the tiles walked on. */
    private val explored = HashMap<Int, MutableMap<Pair<Int, Int>, Tile>>()
    private val walked = HashMap<Int, MutableSet<Pair<Int, Int>>>()
    private val mapNames = HashMap<Int, String>()

    /** The model's own note (goal/plan), rewritten at will. */
    var note: String? = null
        private set

    /** Decisions since something new happened (new tile walked, new map, new dialogue, new screen). */
    var decisionsWithoutProgress: Int = 0
        private set

    fun record(action: AgentAction, before: Observation, after: Observation, thought: String? = null, problem: String? = null) {
        val situation = situationKey(before)
        val count = repeats.merge("$situation|${action.id}", 1, Int::plus)!!
        val changes = buildList {
            addAll(changes(before, after))
            problem?.let { add("the action stopped: $it") }
        }.joinToString("; ").ifEmpty { "nothing changed" }
        val last = actions.lastOrNull()
        if (last != null && last.action == action && last.changes == changes) last.times++
        else actions.addBounded(Step(action, changes, count), maxActions)

        var progress = observeMap(after)
        recordPlace(after)
        after.dialogue?.trim()?.takeIf { it.isNotEmpty() && it !in dialogues }?.let {
            dialogues.addBounded(it, maxDialogues)
            progress = true
        }
        if (before.facts["screen"] != after.facts["screen"]) progress = true
        thought?.takeIf { it.isNotBlank() }?.let { thoughts.addBounded(it, maxThoughts) }
        decisionsWithoutProgress = if (progress) 0 else decisionsWithoutProgress + 1
    }

    /** Records the tiles seen without acting (e.g. the first observation). Returns true if new tiles were walked. */
    fun observeMap(observation: Observation): Boolean {
        val location = observation.location ?: return false
        mapNames[location.mapId] = location.mapName
        observation.map?.let { map ->
            val tiles = explored.getOrPut(location.mapId) { HashMap() }
            for (row in 0 until map.height) for (column in 0 until map.width) {
                val tile = map.tiles[row][column]
                if (tile != Tile.UNKNOWN) tiles[(map.originX + column) to (map.originY + row)] = if (tile == Tile.OCCUPIED) Tile.WALKABLE else tile
            }
        }
        return walked.getOrPut(location.mapId) { HashSet() }.add(location.x to location.y)
    }

    fun updateNote(value: String?) {
        if (!value.isNullOrBlank()) note = value
    }

    /** The memory part of the state sent to the model. */
    fun describe(includeExploredMap: Boolean, current: Observation?): JsonObject = buildJsonObject {
        put("recent_actions", JsonArray(actions.map { step ->
            buildJsonObject {
                put("action", step.action.id)
                if (step.times > 1) put("times_in_a_row", step.times)
                if (step.timesInSituation > 1) put("times_done_in_this_exact_situation", step.timesInSituation)
                put("what_changed", step.changes)
            }
        }))
        put("decisions_without_progress", decisionsWithoutProgress)
        put("places_visited", JsonArray(places.map { place ->
            buildJsonObject {
                put("place", place.name)
                put("decisions_spent", place.decisions)
            }
        }))
        if (dialogues.isNotEmpty()) put("dialogues_read", JsonArray(dialogues.map(::JsonPrimitive)))
        note?.let { put("your_note", it) }
        if (thoughts.isNotEmpty()) put("your_last_thoughts", JsonArray(thoughts.map(::JsonPrimitive)))
        if (includeExploredMap) current?.location?.let { location -> exploredMap(location.mapId, location.x, location.y)?.let { put("explored_map", it) } }
    }

    fun clear() {
        actions.clear(); places.clear(); dialogues.clear(); thoughts.clear(); repeats.clear()
        explored.clear(); walked.clear(); note = null; decisionsWithoutProgress = 0
    }

    /** Everything seen on a map, centered on the player and bounded, with walked tiles marked. */
    private fun exploredMap(mapId: Int, playerX: Int, playerY: Int): JsonObject? {
        val tiles = explored[mapId]?.takeIf { it.isNotEmpty() } ?: return null
        val walkedTiles = walked[mapId].orEmpty()
        val minX = maxOf(tiles.keys.minOf { it.first }, playerX - MAX_EXPLORED_HALF_WIDTH)
        val maxX = minOf(tiles.keys.maxOf { it.first }, playerX + MAX_EXPLORED_HALF_WIDTH)
        val minY = maxOf(tiles.keys.minOf { it.second }, playerY - MAX_EXPLORED_HALF_HEIGHT)
        val maxY = minOf(tiles.keys.maxOf { it.second }, playerY + MAX_EXPLORED_HALF_HEIGHT)
        val rows = (minY..maxY).map { y ->
            "y${y.toString().padStart(3)} " + (minX..maxX).joinToString("") { x ->
                when {
                    x == playerX && y == playerY -> "@"
                    (x to y) in walkedTiles -> "o"
                    else -> symbol(tiles[x to y])
                }
            }
        }
        return buildJsonObject {
            put("map", mapNames[mapId] ?: "map $mapId")
            put("how_to_read", "Everything you have seen on this map so far, north up, one character per tile, from x $minX (left) to x $maxX (right). @ you, o tiles you walked on, . walkable, \" tall grass, W exit, # blocked, ~ water, ? never seen.")
            put("rows", JsonArray(rows.map(::JsonPrimitive)))
        }
    }

    private fun symbol(tile: Tile?): String = when (tile) {
        null, Tile.UNKNOWN -> "?"
        Tile.WALKABLE -> "."
        Tile.TALL_GRASS -> "\""
        Tile.WARP -> "W"
        Tile.WATER -> "~"
        Tile.LEDGE_SOUTH -> "v"
        Tile.LEDGE_NORTH -> "^"
        Tile.LEDGE_WEST -> "<"
        Tile.LEDGE_EAST -> ">"
        Tile.BLOCKED, Tile.OCCUPIED -> "#"
    }

    /** Human-readable differences between two observations. */
    private fun changes(before: Observation, after: Observation): List<String> = buildList {
        val from = before.location
        val to = after.location
        when {
            from == null || to == null -> Unit
            from.mapId != to.mapId -> add("you arrived in ${to.mapName} at (${to.x}, ${to.y})")
            from.x != to.x || from.y != to.y -> add("you moved from (${from.x}, ${from.y}) to (${to.x}, ${to.y})")
            from.facing != to.facing -> add("you turned to face ${to.facing?.name?.lowercase()} without moving")
            else -> add("you did not move")
        }
        if (before.mode != after.mode) add("screen changed from ${before.mode.name.lowercase()} to ${after.mode.name.lowercase()}")
        val ignored = setOf("position", "facing", "mode", "map")
        (before.facts.keys + after.facts.keys).filter { it !in ignored }.sorted().forEach { key ->
            val old = before.facts[key]
            val new = after.facts[key]
            if (old != new) add("$key: ${old?.short() ?: "none"} -> ${new?.short() ?: "none"}")
        }
    }

    private fun String.short() = if (length > 80) take(77) + "..." else this

    private fun situationKey(observation: Observation): String {
        val location = observation.location
        return listOf(location?.mapId, location?.x, location?.y, observation.mode, observation.facts["screen"], observation.facts["menu.cursor"])
            .joinToString(",")
    }

    private fun recordPlace(after: Observation) {
        val name = after.location?.mapName ?: return
        val last = places.lastOrNull()
        if (last != null && last.name == name) last.decisions++
        else places.addBounded(Place(name), maxPlaces)
    }

    private fun <T> ArrayDeque<T>.addBounded(element: T, max: Int) {
        addLast(element)
        while (size > max) removeFirst()
    }

    private class Step(val action: AgentAction, val changes: String, val timesInSituation: Int, var times: Int = 1)
    private class Place(val name: String, var decisions: Int = 1)

    private companion object {
        const val MAX_EXPLORED_HALF_WIDTH = 20
        const val MAX_EXPLORED_HALF_HEIGHT = 15
    }
}
