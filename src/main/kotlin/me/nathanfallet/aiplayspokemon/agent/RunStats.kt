package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.kotlinds.pokemonclient.state.GameState
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Measures a run so configurations can be compared: when each milestone was reached, in decisions, button presses,
 * time, tokens and cost. Each run is saved as JSON in `<data>/runs/` after every milestone.
 *
 * Milestones come from the common typed [GameState] (the same for every game): new places, the steps of the main
 * story completed ([dev.kotlinds.pokemonclient.state.StoryState.completed]), the number of badges, the party size.
 */
class RunStats(private val directory: Path?, private val configuration: String) {

    private val startedAt = System.currentTimeMillis()
    private val fileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json"
    private val seen = HashSet<String>()
    private var startMap: Int? = null

    var milestones: List<Milestone> = emptyList()
        private set

    /** Remembers where the run starts (the first state, before any action). */
    fun start(state: GameState) {
        if (startMap == null) startMap = state.field?.mapId
        progress(state).forEach { (key, _) -> seen += key } // only count progress made during the run
    }

    /** Checks a state for new milestones; returns the ones just reached. */
    fun check(state: GameState, decisions: Int, presses: Int, inputTokens: Long, costUsd: Double): List<Milestone> {
        val reached = buildList {
            state.field?.let { field ->
                if (startMap == null) startMap = field.mapId
                if (field.mapId != startMap && seen.add("left start")) add("Left the starting place")
                if (seen.add("map ${field.mapId}") && field.mapId != startMap) add("Reached ${field.mapName}")
            }
            progress(state).forEach { (key, name) -> if (seen.add(key)) add(name) }
        }.map { name ->
            Milestone(name, decisions, presses, (System.currentTimeMillis() - startedAt) / 1000, inputTokens, costUsd)
        }
        if (reached.isNotEmpty()) {
            milestones = milestones + reached
            save(decisions, presses, inputTokens, costUsd)
        }
        return reached
    }

    /**
     * The progress a state shows, as (stable key, milestone name): the story steps completed (by step id), the number
     * of badges, the party size. Names are for the run log only.
     */
    private fun progress(state: GameState): List<Pair<String, String>> = buildList {
        state.story?.completed?.forEach { add("story ${it.id}" to "Done: ${it.description}") }
        state.player?.badges?.size?.takeIf { it > 0 }?.let { add("badges $it" to "Badges: $it") }
        if (state.party.isNotEmpty()) add("party ${state.party.size}" to "Party: ${state.party.size} Pokémon")
    }

    private fun save(decisions: Int, presses: Int, inputTokens: Long, costUsd: Double) {
        val directory = directory ?: return
        runCatching {
            Files.createDirectories(directory)
            val run = RunLog(configuration, startedAt, decisions, presses, inputTokens, costUsd, milestones)
            Files.writeString(directory.resolve(fileName), json.encodeToString(RunLog.serializer(), run))
        }
    }

    @Serializable
    data class Milestone(
        val name: String,
        val decisions: Int,
        val presses: Int,
        val seconds: Long,
        val inputTokens: Long,
        val costUsd: Double,
    )

    @Serializable
    private data class RunLog(
        val configuration: String,
        val startedAt: Long,
        val decisions: Int,
        val presses: Int,
        val inputTokens: Long,
        val costUsd: Double,
        val milestones: List<Milestone>,
    )

    private companion object {
        val json = Json { prettyPrint = true }
    }
}
