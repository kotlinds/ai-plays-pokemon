package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.game.Location
import me.nathanfallet.aiplayspokemon.game.Memory
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame
import me.nathanfallet.aiplayspokemon.game.GameMode as AgentMode

/**
 * [PokemonGame] for HeartGold / SoulSilver: adapts the detailed [HgssReader] snapshot to what the
 * agent needs (a coarse mode, the player's location, and a compact JSON state for Jev).
 *
 * One class serves every HG/SS build; only the [version]'s address table differs.
 */
class HgssGame(private val version: HgssVersion) : PokemonGame {

    override val name = version.displayName

    override fun observe(memory: Memory): Observation {
        val state = HgssReader(memory, version).read()
            ?: return Observation(AgentMode.UNKNOWN, null, "Unreadable RAM", JsonObject(emptyMap()))
        val location = state.location?.let {
            Location(it.mapId, it.locationName?.let { town -> "$town (${it.mapName})" } ?: it.mapName, it.x, it.z)
        }
        return Observation(
            mode = agentMode(state.mode),
            location = location,
            summary = summary(state),
            state = toJevState(state),
        )
    }

    /** The reader distinguishes many modes; the agent only needs to know which actions make sense. */
    private fun agentMode(mode: GameMode): AgentMode = when (mode) {
        GameMode.LOADING, GameMode.INTRO_MOVIE, GameMode.TITLE_SCREEN,
        GameMode.MAIN_MENU, GameMode.NEW_GAME_INTRO -> AgentMode.INTRO

        GameMode.OVERWORLD -> AgentMode.OVERWORLD
        GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE -> AgentMode.DIALOGUE
        GameMode.START_MENU, GameMode.APP -> AgentMode.MENU
        GameMode.BATTLE -> AgentMode.BATTLE
        GameMode.UNKNOWN -> AgentMode.UNKNOWN
    }

    private fun summary(state: HgssState): String = buildString {
        append(state.mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        state.location?.let { append(" · ${it.locationName ?: it.mapName} (${it.x}, ${it.z}) facing ${it.facing}") }
        state.battle?.let { battle ->
            val ours = battle.player.firstOrNull()
            val theirs = battle.opponents.firstOrNull()
            if (ours != null && theirs != null) {
                append(" · ${ours.speciesName} ${ours.hp}/${ours.maxHp} vs ${theirs.speciesName} Lv${theirs.level} ${theirs.hp}/${theirs.maxHp}")
            }
        }
        state.dialogue?.text?.let { append(" · \"${it.replace('\n', ' ').take(60)}\"") }
    }

    /**
     * The state sent to Jev: the reader's snapshot minus what's noise for decisions (frame counter,
     * warnings, empty bag pockets), with the local map rendered as text rows and only the legend
     * entries actually present.
     */
    private fun toJevState(state: HgssState): JsonObject {
        val full = json.encodeToJsonElement(HgssState.serializer(), state).jsonObject
        return buildJsonObject {
            full.forEach { (key, value) ->
                when (key) {
                    "frame", "warnings" -> Unit
                    "bag" -> put(key, JsonArray(value.jsonArray.filter { pocket -> pocket.jsonObject["items"]?.jsonArray?.isNotEmpty() == true }))
                    "surroundings" -> put(key, compactSurroundings(value.jsonObject, state.surroundings?.grid))
                    else -> put(key, value)
                }
            }
        }
    }

    private fun compactSurroundings(surroundings: JsonObject, grid: LocalGrid?): JsonObject = buildJsonObject {
        surroundings.forEach { (key, value) -> if (key != "grid") put(key, value) }
        if (grid != null) put("map_around_player", buildJsonObject {
            put("note", "North is up, one character per tile. `@` is you. Top-left tile is x ${grid.originX}, z ${grid.originZ}.")
            put("rows", JsonArray(grid.rows.map(::JsonPrimitive)))
            val used = grid.rows.joinToString("").map { it.toString() }.toSet()
            put("legend", JsonObject(grid.legend.filterKeys { it in used }.mapValues { JsonPrimitive(it.value) }))
        })
    }

    private companion object {
        val json = Json { encodeDefaults = false; explicitNulls = false }
    }
}
