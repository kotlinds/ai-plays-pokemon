package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.game.Location
import me.nathanfallet.aiplayspokemon.game.Memory
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame
import me.nathanfallet.aiplayspokemon.game.GameMode as AgentMode

/**
 * [PokemonGame] for HeartGold / SoulSilver: adapts the detailed [HgssReader] snapshot to what the
 * agent needs (a coarse mode and the player's location for our code, and a description of the
 * screen for the AI, see [HgssScreenDescriber]).
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
            state = HgssScreenDescriber.describe(state),
            dialogue = state.dialogue?.text,
        )
    }

    /** The reader distinguishes many modes; our code only needs the broad situation. */
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
}
