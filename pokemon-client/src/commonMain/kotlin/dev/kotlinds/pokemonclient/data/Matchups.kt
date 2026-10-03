package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef

/** How hard one of the actor's moves would hit one opponent, from the type chart alone. */
data class MoveMatchup(val move: String, val target: BattlerRef, val multiplier: Double) {
    /** "x2", "x0.5", "no effect"... */
    val label: String
        get() = when (multiplier) {
            0.0 -> "no effect"
            else -> "x" + if (multiplier % 1.0 == 0.0) multiplier.toInt().toString() else multiplier.toString()
        }
}

/**
 * Estimated effectiveness of the acting Pokémon's moves against each opponent, the way a player with a type chart
 * would see it (types only: abilities, items and weather are left out). Status moves are skipped. Shown from
 * [KnowledgeLevel.POKEDEX] on.
 */
object Matchups {

    fun estimate(battle: BattleState, data: GameData): List<MoveMatchup> {
        val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: BattlerRef.PLAYER_LEFT) } ?: return emptyList()
        val foes = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }
        return actor.moves.flatMap { known ->
            val info = data.move(known.move.id) ?: return@flatMap emptyList()
            if (info.category == MoveCategory.STATUS || info.power == 0) return@flatMap emptyList()
            foes.mapNotNull { foe ->
                val types = foe.types.mapNotNull(::typeOf)
                if (types.isEmpty()) null else MoveMatchup(known.move.name, foe.ref, data.typeChart.multiplier(info.type, types))
            }
        }
    }

    private fun typeOf(label: String): PokemonType? =
        PokemonType.entries.firstOrNull { it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true) }
}
