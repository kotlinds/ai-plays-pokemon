package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.Area
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the player remembers between two decisions.
 *
 * Models are stateless (every request starts from scratch), while a human remembers what they did,
 * where they have been and what people told them. This memory only keeps facts and the model's own
 * words, never advice: interpreting them is the model's job.
 *
 * Everything is read from the common typed [GameState] (the same for every game):
 * - recent actions and exactly what changed after each one ([facts] compared before / after), repeats grouped;
 * - how many times the same action was done in the same situation, and how long since any progress;
 * - places visited, dialogues read, the model's own note and last thoughts;
 * - the explored map: every tile seen so far on each map (the terrain of the common map view, [MapView.terrain]), with
 *   the tiles walked on.
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

    /** Times each action was taken in each situation (map, tile, screen, highlighted entry). */
    private val repeats = HashMap<String, Int>()

    /** Per map id: the symbol of every tile seen ([MapView.terrain]), and the tiles walked on. */
    private val explored = HashMap<Int, MutableMap<Pair<Int, Int>, Char>>()
    private val walked = HashMap<Int, MutableSet<Pair<Int, Int>>>()
    private val mapNames = HashMap<Int, MapName>()

    /** The model's own note (goal/plan), rewritten at will. */
    var note: String? = null
        private set

    /** Decisions since something new happened (new tile walked, new map, new dialogue, new screen). */
    var decisionsWithoutProgress: Int = 0
        private set

    /**
     * Records [action], taken in [before], and what it led to ([after]; [area] is the ROM's map of where the player is
     * then, when the game has one, for the explored map).
     */
    fun record(action: String, before: GameState, after: GameState, area: Area? = null, thought: String? = null, problem: String? = null) {
        val situation = situationKey(before)
        val count = repeats.merge("$situation|$action", 1, Int::plus)!!
        val changes = buildList {
            addAll(changes(before, after))
            problem?.let { add("the action stopped: $it") }
        }.joinToString("; ").ifEmpty { "nothing changed" }
        val last = actions.lastOrNull()
        if (last != null && last.action == action && last.changes == changes) last.times++
        else actions.addBounded(Step(action, changes, count), maxActions)

        var progress = observeMap(after.field, area)
        recordPlace(after.field)
        dialogue(after.screen)?.trim()?.takeIf { it.isNotEmpty() && it !in dialogues }?.let {
            dialogues.addBounded(it, maxDialogues)
            progress = true
        }
        if (before.screen.kind != after.screen.kind) progress = true
        thought?.takeIf { it.isNotBlank() }?.let { thoughts.addBounded(it, maxThoughts) }
        decisionsWithoutProgress = if (progress) 0 else decisionsWithoutProgress + 1
    }

    /**
     * Records the tiles seen from [field] without acting (e.g. the first observation): the terrain of the common map
     * view on [area]. Returns true if the player stands on a tile not walked before.
     */
    fun observeMap(field: FieldState?, area: Area?): Boolean {
        field ?: return false
        mapNames[field.mapId] = field.mapName
        area?.let { explored.getOrPut(field.mapId) { HashMap() }.putAll(MapView.terrain(it, field)) }
        return walked.getOrPut(field.mapId) { HashSet() }.add(field.x to field.y)
    }

    fun updateNote(value: String?) {
        if (!value.isNullOrBlank()) note = value
    }

    /** The memory part of the state sent to the model ([current]: where the player stands, for the explored map). */
    fun describe(includeExploredMap: Boolean, current: FieldState?): JsonObject = buildJsonObject {
        put("recent_actions", JsonArray(actions.map { step ->
            buildJsonObject {
                put("action", step.action)
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
        if (includeExploredMap) current?.let { field -> exploredMap(field)?.let { put("explored_map", it) } }
    }

    /** Everything seen on a map, centered on the player and bounded, with walked tiles marked. */
    private fun exploredMap(field: FieldState): JsonObject? {
        val mapId = field.mapId
        val playerX = field.x
        val playerY = field.y
        val player = MapView.player(field.facing)
        val tiles = explored[mapId]?.takeIf { it.isNotEmpty() } ?: return null
        val walkedTiles = walked[mapId].orEmpty()
        val minX = maxOf(tiles.keys.minOf { it.first }, playerX - MAX_EXPLORED_HALF_WIDTH)
        val maxX = minOf(tiles.keys.maxOf { it.first }, playerX + MAX_EXPLORED_HALF_WIDTH)
        val minY = maxOf(tiles.keys.minOf { it.second }, playerY - MAX_EXPLORED_HALF_HEIGHT)
        val maxY = minOf(tiles.keys.maxOf { it.second }, playerY + MAX_EXPLORED_HALF_HEIGHT)
        val used = sortedSetOf<Char>()
        val rows = (minY..maxY).map { y ->
            "y${y.toString().padStart(3)} " + (minX..maxX).joinToString("") { x ->
                when {
                    x == playerX && y == playerY -> player
                    (x to y) in walkedTiles -> WALKED
                    else -> tiles[x to y]?.also { used += it } ?: UNSEEN
                }.toString()
            }
        }
        val legend = used.mapNotNull { c -> MapView.legend(c)?.let { "$c $it" } }
        return buildJsonObject {
            put("map", (mapNames[mapId] ?: MapName(mapId)).toString())
            put(
                "how_to_read",
                "Everything you have seen on this map so far, north up, one character per tile, from x $minX (left) to x $maxX (right). " +
                    "$player you, $WALKED tiles you walked on, blank never seen" + legend.joinToString("") { ", $it" } + ".",
            )
            put("rows", JsonArray(rows.map(::JsonPrimitive)))
        }
    }

    /** Human-readable differences between two states. */
    private fun changes(before: GameState, after: GameState): List<String> = buildList {
        val from = before.field
        val to = after.field
        when {
            from == null || to == null -> Unit
            from.mapId != to.mapId -> add("you arrived in ${to.mapName} at (${to.x}, ${to.y})")
            from.x != to.x || from.y != to.y -> add("you moved from (${from.x}, ${from.y}) to (${to.x}, ${to.y})")
            from.facing != to.facing -> add("you turned to face ${to.facing?.name?.lowercase()} without moving")
            else -> add("you did not move")
        }
        if (before.screen.kind != after.screen.kind) add("screen changed from ${before.screen.kind} to ${after.screen.kind}")
        val old = facts(before)
        val new = facts(after)
        (old.keys + new.keys).sorted().forEach { key ->
            if (old[key] != new[key]) add("$key: ${old[key]?.short() ?: "none"} -> ${new[key]?.short() ?: "none"}")
        }
    }

    /**
     * Small facts of a state compared before / after each action to tell the model what its action did: the message
     * on screen, the highlighted entry, the HP in battle and of the team, money, badges, bag. Values are short strings
     * (display only: never matched against anything).
     */
    private fun facts(state: GameState): Map<String, String> = buildMap {
        dialogue(state.screen)?.let { put("dialogue", it.replace("\n", " / ")) }
        highlighted(state.screen)?.let { put("highlighted", it) }
        state.battle?.let { battle ->
            val ours = battle.battlers.firstOrNull { it.ref.isPlayerSide }
            val theirs = battle.battlers.firstOrNull { !it.ref.isPlayerSide }
            if (ours != null && theirs != null) {
                put("battle.hp", "${ours.nickname ?: ours.species.name} ${ours.hp}/${ours.maxHp} vs ${theirs.nickname ?: theirs.species.name} ${theirs.hp}/${theirs.maxHp}")
            }
        }
        if (state.party.isNotEmpty()) put("party.hp", state.party.joinToString(", ") { "${it.nickname ?: it.species.name} ${it.hp}/${it.maxHp}" })
        state.player?.let {
            put("money", it.money.toString())
            put("badges", it.badges.size.toString())
        }
        state.bag?.let { pockets ->
            val items = pockets.flatMap { it.items }
            put("bag", "${items.size} kinds, ${items.sumOf { it.quantity }} items")
        }
    }

    private fun dialogue(screen: Screen): String? = (screen as? Screen.Dialogue)?.text

    /** The id of the highlighted entry of a menu (language-independent), when one is. */
    private fun highlighted(screen: Screen): String? =
        ((screen as? Screen.Selectable)?.cursor as? Cursor.At)?.let { screen.entries.getOrNull(it.index)?.id }

    private fun String.short() = if (length > 80) take(77) + "..." else this

    private fun situationKey(state: GameState): String {
        val field = state.field
        return listOf(field?.mapId, field?.x, field?.y, state.screen.kind, highlighted(state.screen)).joinToString(",")
    }

    private fun recordPlace(field: FieldState?) {
        val name = field?.mapName?.toString() ?: return
        val last = places.lastOrNull()
        if (last != null && last.name == name) last.decisions++
        else places.addBounded(Place(name), maxPlaces)
    }

    private fun <T> ArrayDeque<T>.addBounded(element: T, max: Int) {
        addLast(element)
        while (size > max) removeFirst()
    }

    private class Step(val action: String, val changes: String, val timesInSituation: Int, var times: Int = 1)
    private class Place(val name: String, var decisions: Int = 1)

    private companion object {
        const val MAX_EXPLORED_HALF_WIDTH = 20
        const val MAX_EXPLORED_HALF_HEIGHT = 15
        const val WALKED = 'o'
        const val UNSEEN = ' '
    }
}
