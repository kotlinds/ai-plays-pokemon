package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Matchups
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionChains
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ActionSettings
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.Navigator
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.Sightings
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
    /**
     * When true, what a response told the agent is only forgotten once [confirmDelivered] is called: a response lost
     * on the way (client timeout) is given again with the next one. Our own loop can't lose responses (false).
     */
    confirmDelivery: Boolean = false,
    /**
     * Whether the walks solve movement puzzles by themselves (boulders, ice blocks, platforms, lifts:
     * [ActionSettings.solvePuzzles]); when false the agent operates them itself.
     */
    private val solvePuzzles: () -> Boolean = { true },
) {
    /** What the application lets the actions do by themselves: the settings of this session, read at each action. */
    private fun actionSettings() = ActionSettings(
        solvePuzzles = solvePuzzles(),
        revealHidden = knowledge().allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH),
    )

    /** What the player has seen (teleport tiles once on screen), for agents without a walkthrough. */
    private val sightings = Sightings()

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
        val screen = host.observe { memory -> game.state(memory).screen }
        countBlind(screen)
    }

    /** The game's data (for `lookup`), when the ROM provides it. */
    val gameData get() = game.data

    /** What the agent has seen of the opponents this battle (revealed abilities and held items). */
    private val battleKnowledge = dev.kotlinds.pokemonclient.data.BattleKnowledge()

    /** The events the agent hasn't received yet (kept until a response carrying them is delivered). */
    private val feed = EventFeed(recorder.log, autoConfirm = !confirmDelivery)

    /** The last response reached the agent: what it carried won't be repeated (see `confirmDelivery`). */
    fun confirmDelivered() = feed.confirm()

    /** Version of the last state returned to the agent: actions default to it. */
    private var lastServedVersion: Long? = null

    /** Notes the agent wrote with the `note` action, returned with every full state. */
    val notes = ArrayDeque<String>()

    /** The moves of the player noticed so far: part of [version]. */
    private val stateVersion = StateVersion()

    /**
     * Version of the state: the sequence number of the last screen change seen by the recorder, plus the player's
     * moves noticed ([StateVersion]: walking keeps the same screen kind, but the map the agent saw is then outdated).
     * An agent's action computed on version v is refused if the screen changed or the player moved after v.
     */
    val version: Long get() = stateVersion.of(recorder.log.since(0).lastOrNull { it is GameEvent.ScreenChanged }?.seq ?: 0L)

    /** Decodes the current state (on the console thread). */
    suspend fun state(): GameState = host.observe { memory -> game.state(memory) }

    /** What the agent reads: events since its last call, the screen and state, the actions possible now. */
    suspend fun describe(full: Boolean = false): JsonObject {
        // Observing runs no frame: it works while the game is paused.
        val (state, legacy) = host.observe { memory -> game.state(memory) to game.observe(memory).state }
        return describe(state, legacy, full)
    }

    /** Keeps a note of the agent (goal, plan), returned with every full state. */
    fun addNote(text: String) {
        notes.addLast(text)
        while (notes.size > MAX_NOTES) notes.removeFirst()
    }

    /**
     * Executes [action] (refused if [expectedVersion], by default the version of the last state returned to the
     * agent, is older than the current screen), then waits until the game expects input and returns what happened
     * and the new state.
     */
    suspend fun act(action: GameAction, expectedVersion: Long? = lastServedVersion): JsonObject = act(listOf(action), expectedVersion)

    /**
     * Executes [actions] one after the other (the first one checked against [expectedVersion]), stopping at the
     * first failure, or before starting a step once [budgetMillis] have passed (so the agent's call doesn't time out).
     * One response for the whole sequence: `performed` lists every step done, and the messages / events cover all
     * of them (nothing said by the game between two steps is lost).
     */
    suspend fun act(requested: List<GameAction>, expectedVersion: Long? = lastServedVersion, budgetMillis: Long? = null, compact: Boolean = false): JsonObject {
        require(requested.isNotEmpty()) { "no action" }
        // Field item uses in a row: one bag session instead of closing and reopening the bag for each item.
        val actions = ActionChains.coalesce(requested, inBattle = host.observe { memory -> game.state(memory).battle != null })
        val started = System.currentTimeMillis()
        val performed = mutableListOf<String>()
        val details = mutableListOf<String>()
        var failed: Pair<GameAction, ActionError>? = null
        var skipped = emptyList<GameAction>()
        for ((index, action) in actions.withIndex()) {
            if (index > 0 && budgetMillis != null && System.currentTimeMillis() - started > budgetMillis) {
                skipped = actions.drop(index)
                break
            }
            when (val outcome = execute(action, if (index == 0) expectedVersion else null)) {
                is ActionOutcome.Done -> {
                    performed += action.key
                    outcome.detail?.let { details += if (actions.size > 1) "${action.key}: $it" else it }
                }
                is ActionOutcome.Failed -> {
                    // A spare advance_dialogue (the dialogue ended sooner than expected): nothing to read, go on.
                    if (index > 0 && ActionChains.isSpareAdvance(action, outcome.error, host.observe { memory -> game.state(memory) })) {
                        details += "${action.key}: skipped, no dialogue left"
                        continue
                    }
                    failed = action to outcome.error
                    skipped = actions.drop(index + 1)
                    break
                }
            }
        }
        val (afterState, legacy) = host.observe { memory -> game.state(memory) to game.observe(memory).state }
        val after = describe(afterState, legacy, full = false, compact = compact)
        return buildJsonObject {
            put("ok", failed == null)
            putJsonArray("performed") { performed.forEach { add(JsonPrimitive(it)) } }
            if (details.isNotEmpty()) put("detail", details.joinToString("; "))
            failed?.let { (action, error) ->
                put("action", action.key)
                put("error", buildJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                })
            }
            if (skipped.isNotEmpty()) {
                put("not_done", JsonArray(skipped.map { JsonPrimitive(it.key) }))
                if (failed == null) put("not_done_reason", "the call had already run ${(System.currentTimeMillis() - started) / 1000} s: check the state, then go on")
            }
            after.forEach { (k, v) -> put(k, v) }
        }
    }

    /** Carries out one action: checks (version, human, pause), then runs it in a console lease and lets the game settle. */
    private suspend fun execute(action: GameAction, expectedVersion: Long?): ActionOutcome {
        if (action is GameAction.Note) {
            addNote(action.text)
            return ActionOutcome.Done()
        }
        // Where the player stands now (a human may have walked since the last answer): part of the version.
        stateVersion.observe(host.observe { memory -> game.state(memory).field })
        val current = version
        if (expectedVersion != null && expectedVersion < current) return ActionOutcome.Failed(ActionError.StaleState(expectedVersion, current))
        // Only a human really pressing keys (or the Pause button) stops an agent; the free run doesn't.
        ActGate.refusal(humanPlaying = host.humanActivity.isPlaying(), userPaused = host.isUserPaused)
            ?.let { return ActionOutcome.Failed(it) }
        return try {
            host.lease(action.key, game.inputProbe) {
                if (action is GameAction.Press || action is GameAction.Touch) countBlind(game.state(memory()).screen)
                // The game settles after the action, but one step never runs much past STEP_FRAMES in all (the agent's
                // call must answer before its client gives up): a long action leaves less time to settle.
                registry.execute(action, this, game, actionSettings()).also {
                    Navigator(this, game).settle(maxFrames = (STEP_FRAMES - framesUsed).coerceIn(MIN_SETTLE_FRAMES.toLong(), SETTLE_FRAMES.toLong()).toInt())
                }
            }
        } catch (_: ActionInterruptedException) {
            ActionOutcome.Failed(ActionError.Interrupted(dev.kotlinds.pokemonclient.actions.InterruptionCause.HUMAN, action.key))
        }
    }

    /** The team and position last sent, for [compact] answers that only repeat what changed. */
    private val compactView = CompactView()

    /**
     * The state for the agent. [compact] (default for `act` answers) leaves out what the agent already has: the team
     * when unchanged, the map when the player hasn't moved, money / badges, and the actions' valid values (names only;
     * `get_state` gives them).
     */
    private fun describe(state: GameState, legacy: JsonObject, full: Boolean, compact: Boolean = false): JsonObject = buildJsonObject {
        stateVersion.observe(state.field)
        val batch = feed.take()
        val events = batch.events
        if (batch.repeated) put("messages_repeated", "your previous call seems not to have received its answer (timeout?): what happened since the call before it is given again")
        val messages = events.filterIsInstance<GameEvent.TextShown>()
        if (messages.isNotEmpty()) {
            putJsonArray("messages_since_last_call") {
                messages.forEach { add(JsonPrimitive("[${it.source.name.lowercase()}] " + (it.speaker?.let { s -> "$s: " } ?: "") + it.text.replace('\n', ' '))) }
            }
        }
        val other = events.mapNotNull(::describeEvent).distinct()
        if (other.isNotEmpty()) put("events_since_last_call", JsonArray(other.map(::JsonPrimitive)))
        val walkthrough = knowledge().allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)
        sightings.observe(state.field)
        val view = StateView.state(state, showHidden = walkthrough, sightings = sightings)
        val (viewEntries, moved) = compactView.take(view, compact)
        viewEntries.forEach { (k, v) -> put(k, v) }
        // get_state lists the bag (one line per pocket); act answers leave it out.
        if (!compact) state.bag?.takeIf { it.isNotEmpty() }?.let { put("bag", StateView.bag(it)) }
        if (full) {
            state.storage?.let { put("boxes", StateView.storage(it)) }
            state.options?.let { put("options", StateView.options(it)) }
        }
        val data = game.data
        val battle = state.battle
        battleKnowledge.observe(state, messages.filter { it.source == dev.kotlinds.pokemonclient.state.TextSource.BATTLE }.map { it.text }, data)
        if (battle != null && data != null && knowledge().allows(KnowledgeLevel.POKEDEX)) {
            val matchups = Matchups.estimate(battle, data, battleKnowledge)
            if (matchups.isNotEmpty()) {
                put("effectiveness", JsonArray(matchups.map { JsonPrimitive("${it.move} → ${it.target.wire}: ${it.label}") }))
            }
            battleKnowledge.describe(battle, data).takeIf { it.isNotEmpty() }?.let { put("opponents_known", JsonArray(it.map(::JsonPrimitive))) }
            val balls = state.bag.orEmpty().firstOrNull { it.name == "balls" }?.items.orEmpty()
            dev.kotlinds.pokemonclient.data.CatchChance.estimate(battle, balls, data)?.let { estimate ->
                put("catch", buildJsonObject {
                    put("catch_rate", estimate.catchRate)
                    put("chance_per_ball", JsonArray(estimate.balls.map { JsonPrimitive(it.label) }))
                })
            }
        }
        // The text map of the surroundings: the common MapView on the ROM's maps (the older HGSS map without a ROM).
        if (state.screen is Screen.Overworld) {
            val field = state.field
            val area = field?.let { game.world?.areaOf(it.mapId) }
            if (compact && !moved) put("map", "unchanged (you haven't moved)")
            else if (field != null && area != null) MapView.render(area, field, game::zoneName, world = game.world, showHidden = walkthrough).forEach { (k, v) -> put(k, v) }
            else MAP_KEYS.forEach { key -> legacy[key]?.let { put(key, it) } }
        }
        // Movement puzzles left to the agent: say so where it matters (a puzzle here, or the full state).
        val here = state.field
        if (!solvePuzzles() && here != null && (full || here.puzzle != null)) put("movement_puzzles", PUZZLES_LEFT_TO_AGENT)
        val story = state.story
        if (story != null && knowledge().allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) {
            story.goal?.let { put("story_goal", it.description) }
            if (story.blockers.isNotEmpty()) put("blocked_by", JsonArray(story.blockers.map { JsonPrimitive("${it.target}: ${it.reason}") }))
        }
        val mode = mode()
        if (compact) {
            put("actions", JsonArray(registry.available(state, mode).map { JsonPrimitive(it.name) }))
            put("version", version.also { lastServedVersion = it })
            return@buildJsonObject
        }
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
        is GameEvent.LevelUp -> "${event.name?.let { "$it (${event.mon})" } ?: event.mon} reached level ${event.level}"
        is GameEvent.ItemReceived -> "received ${event.item} x${event.quantity}"
        is GameEvent.ShopBonus -> "the clerk added ${event.item} x${event.quantity} as a bonus"
        is GameEvent.BadgeReceived -> "received the ${event.badge} badge"
        is GameEvent.HumanInput -> "the human pressed buttons"
        is GameEvent.Caught -> "caught ${event.name}" + (if (event.name != event.species) " (${event.species})" else "") +
            (event.level?.let { " Lv$it" } ?: "") + " (${event.mon})" + (event.boxName?.let { ", sent to $it (party full)" } ?: ", in the party")
        is GameEvent.SentToBox -> "${event.name} (${event.mon}) was sent to the PC: ${event.boxName}"
        is GameEvent.LearnedMove -> "${event.name} (${event.mon}) learned ${event.move}" + (event.forgot?.let { ", forgot $it" } ?: "")
        else -> null
    }

    private companion object {
        const val PUZZLES_LEFT_TO_AGENT = "left to you: go_to and interact only walk (they never push a boulder or an ice block, " +
            "never step on a platform trigger or a lift unless it is the destination you gave); a way that needs one fails with " +
            "PUZZLE_LEFT_TO_AGENT naming it. Operate them yourself: step into a boulder after using Strength on it (interact), " +
            "slide into an ice block, go_to / step onto a trigger or a lift; push moves a boulder into its hole"
        val MAP_KEYS = listOf("map", "legend", "adjacent", "exits", "people", "objects", "nearby_areas")
        const val SETTLE_FRAMES = 1800

        /** About 30 s of game (real time): what one step may take, action and settling together. */
        const val STEP_FRAMES = 1800L

        /** Always let the game settle a little after an action (a menu closing, the next screen fading in). */
        const val MIN_SETTLE_FRAMES = 120
        const val MAX_NOTES = 20
    }
}
