package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.kotlinds.pokemonclient.Observation
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Measures a run so configurations can be compared: when each milestone was reached (new places,
 * story progress read from the game), in decisions, button presses, time, tokens and cost.
 * Each run is saved as JSON in `<data>/runs/` after every milestone.
 */
class RunStats(private val directory: Path?, private val configuration: String) {

    private val startedAt = System.currentTimeMillis()
    private val fileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json"
    private val seen = HashSet<String>()
    private var startMap: Int? = null

    var milestones: List<Milestone> = emptyList()
        private set

    /** Remembers where the run starts (the first observation, before any action). */
    fun start(observation: Observation) {
        if (startMap == null) startMap = observation.location?.mapId
        observation.progress.forEach { seen += "progress $it" } // only count progress made during the run
    }

    /** Checks an observation for new milestones; returns the ones just reached. */
    fun check(observation: Observation, decisions: Int, presses: Int, inputTokens: Long, costUsd: Double): List<Milestone> {
        val reached = buildList {
            observation.location?.let { location ->
                if (startMap == null) startMap = location.mapId
                if (location.mapId != startMap && seen.add("left start")) add("Left the starting place")
                if (seen.add("map ${location.mapId}") && location.mapId != startMap) add("Reached ${location.mapName}")
            }
            observation.progress.forEach { if (seen.add("progress $it")) add(it) }
        }.map { name ->
            Milestone(name, decisions, presses, (System.currentTimeMillis() - startedAt) / 1000, inputTokens, costUsd)
        }
        if (reached.isNotEmpty()) {
            milestones = milestones + reached
            save(decisions, presses, inputTokens, costUsd)
        }
        return reached
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
