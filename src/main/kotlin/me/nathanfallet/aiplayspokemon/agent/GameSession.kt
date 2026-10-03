package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Matchups
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.Navigator
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.StateView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost

/**
 * The game as one agent sees it, independent of how the agent talks to us (MCP, our LLM loop, Jev): observe,
 * act through the typed action registry, and get everything that happened since the previous call.
 *
 * Every observation and action runs in a lease of the [ConsoleHost]: on the console thread, with the game advancing
 * only as the action needs. The [version] protects agents from acting on a screen they haven't seen: an action is
 * refused with STALE_STATE when the screen changed since the state the agent last received.
 */
class GameSession(
    private val host: ConsoleHost,
    private val game: PokemonGame,
    private val recorder: Recorder,
    private val mode: () -> ActionMode,
    val registry: ActionRegistry = ActionRegistry.of(),
    /** What may be shown beyond the screen (estimated effectiveness from [KnowledgeLevel.POKEDEX] on). */
    private val knowledge: () -> KnowledgeLevel = { KnowledgeLevel.POKEDEX },
) {
    private val _blindUses = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Raw presses, touches and screenshots made on screens the client doesn't decode yet, by screen hint: the most
     * used ones are the next screens to decode (design §9.3).
     */
    val blindUses: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _blindUses

    /** Counts one blind use when [screen] isn't decoded. */
    fun countBlind(screen: Screen) {
        val unknown = screen as? Screen.Unknown ?: return
        val key = unknown.hint ?: "unknown"
        _blindUses.value = _blindUses.value + (key to (_blindUses.value[key] ?: 0) + 1)
    }

    /** Counts a screenshot when the current screen isn't decoded. */
    suspend fun countScreenshot() {
        val screen = host.lease("observe", game.inputProbe) { game.state(memory()).screen }
        countBlind(screen)
    }

    /** The game's data (for `lookup`), when the ROM provides it. */
    val gameData get() = game.data

    /** Last event already returned to the agent. */
    private var eventCursor = recorder.log.lastSeq

    /** Version of the last state returned to the agent: actions default to it. */
    private var lastServedVersion: Long? = null

    /** Notes the agent wrote with the `note` action, returned with every full state. */
    val notes = ArrayDeque<String>()

    /**
     * Version of the state: the sequence number of the last screen change seen by the recorder. An agent's action
     * computed on version v is refused if the screen changed after v.
     */
    val version: Long get() = recorder.log.since(0).lastOrNull { it is GameEvent.ScreenChanged }?.seq ?: 0L

    /** Decodes the current state (on the console thread). */
    suspend fun state(): GameState = host.lease("observe", game.inputProbe) { game.state(memory()) }

    /** What the agent reads: events since its last call, the screen and state, the actions possible now. */
    suspend fun describe(full: Boolean = false): JsonObject {
        val (state, legacy) = host.lease("observe", game.inputProbe) {
            val memory = memory()
            game.state(memory) to game.observe(memory).state
        }
        return describe(state, legacy, full)
    }

    /**
     * Executes [action] (refused if [expectedVersion], by default the version of the last state returned to the
     * agent, is older than the current screen), then waits until the game expects input and returns what happened
     * and the new state.
     */
    suspend fun act(action: GameAction, expectedVersion: Long? = lastServedVersion): JsonObject {
        if (action is GameAction.Note) {
            notes.addLast(action.text)
            while (notes.size > MAX_NOTES) notes.removeFirst()
            return describe()
        }
        val current = version
        if (expectedVersion != null && expectedVersion < current) {
            return failure(action, ActionError.StaleState(expectedVersion, current), describe())
        }
        if (host.driver.value == ConsoleHost.Driver.Human) return failure(action, ActionError.HumanDriving, describe())
        val outcome = try {
            host.lease(action.key, game.inputProbe) {
                if (action is GameAction.Press || action is GameAction.Touch) countBlind(game.state(memory()).screen)
                registry.execute(action, this, game).also { Navigator(this, game).settle(maxFrames = SETTLE_FRAMES) }
            }
        } catch (_: ActionInterruptedException) {
            ActionOutcome.Failed(ActionError.Interrupted(dev.kotlinds.pokemonclient.actions.InterruptionCause.HUMAN, action.key))
        }
        val after = describe()
        return when (outcome) {
            is ActionOutcome.Done -> buildJsonObject {
                put("ok", true)
                put("performed", action.key)
                outcome.detail?.let { put("detail", it) }
                after.forEach { (k, v) -> put(k, v) }
            }
            is ActionOutcome.Failed -> failure(action, outcome.error, after)
        }
    }

    private fun failure(action: GameAction, error: ActionError, state: JsonObject) = buildJsonObject {
        put("ok", false)
        put("action", action.key)
        put("error", buildJsonObject {
            put("code", error.code)
            put("message", error.message)
        })
        state.forEach { (k, v) -> put(k, v) }
    }

    private fun describe(state: GameState, legacy: JsonObject, full: Boolean): JsonObject = buildJsonObject {
        val events = recorder.log.since(eventCursor)
        eventCursor = recorder.log.lastSeq
        val messages = events.filterIsInstance<GameEvent.TextShown>()
        if (messages.isNotEmpty()) {
            putJsonArray("messages_since_last_call") {
                messages.forEach { add(JsonPrimitive("[${it.source.name.lowercase()}] " + (it.speaker?.let { s -> "$s: " } ?: "") + it.text.replace('\n', ' '))) }
            }
        }
        val other = events.mapNotNull(::describeEvent).distinct()
        if (other.isNotEmpty()) put("events_since_last_call", JsonArray(other.map(::JsonPrimitive)))
        StateView.state(state).forEach { (k, v) -> put(k, v) }
        val data = game.data
        val battle = state.battle
        if (battle != null && data != null && knowledge().allows(KnowledgeLevel.POKEDEX)) {
            val matchups = Matchups.estimate(battle, data)
            if (matchups.isNotEmpty()) {
                put("effectiveness", JsonArray(matchups.map { JsonPrimitive("${it.move} → ${it.target.wire}: ${it.label}") }))
            }
        }
        // The text map of the surroundings: the common MapView on the ROM's maps (the older HGSS map without a ROM).
        if (state.screen is Screen.Overworld) {
            val field = state.field
            val area = field?.let { game.world?.areaOf(it.mapId) }
            if (field != null && area != null) MapView.render(area, field, game::zoneName).forEach { (k, v) -> put(k, v) }
            else MAP_KEYS.forEach { key -> legacy[key]?.let { put(key, it) } }
        }
        val story = state.story
        if (story != null && knowledge().allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) {
            story.goal?.let { put("story_goal", it.description) }
            if (story.blockers.isNotEmpty()) put("blocked_by", JsonArray(story.blockers.map { JsonPrimitive("${it.target}: ${it.reason}") }))
        }
        val mode = mode()
        put("actions", buildJsonArray {
            registry.available(state, mode).forEach { a ->
                add(buildJsonObject {
                    put("type", a.name)
                    if (full) put("description", a.description)
                    a.choices.forEach { (param, choices) ->
                        put(param, JsonArray(choices.map { JsonPrimitive(if (it.label == it.value) it.value else "${it.value} = ${it.label}") }))
                    }
                })
            }
        })
        val unavailable = registry.unavailable(state, mode)
        if (unavailable.isNotEmpty()) {
            put("unavailable", JsonArray(unavailable.map { JsonPrimitive("${it.name}: ${it.detail}" + (it.hint?.let { h -> " ($h)" } ?: "")) }))
        }
        if (full && notes.isNotEmpty()) put("your_notes", JsonArray(notes.map(::JsonPrimitive)))
        put("version", version.also { lastServedVersion = it })
    }

    private fun describeEvent(event: GameEvent): String? = when (event) {
        is GameEvent.PokemonObtained -> "obtained ${event.species} (${event.mon})"
        is GameEvent.Evolved -> "${event.from} evolved into ${event.to}"
        is GameEvent.LevelUp -> "${event.mon} reached level ${event.level}"
        is GameEvent.ItemReceived -> "received ${event.item} x${event.quantity}"
        is GameEvent.BadgeReceived -> "received the ${event.badge} badge"
        is GameEvent.HumanInput -> "the human pressed buttons"
        else -> null
    }

    private companion object {
        val MAP_KEYS = listOf("map", "legend", "adjacent", "exits", "people", "objects", "nearby_areas")
        const val SETTLE_FRAMES = 600
        const val MAX_NOTES = 20
    }
}
