package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MoveId

/**
 * What a game asks before a field move can be used outside battle: a Pokémon of the party knowing [move], and the
 * badge [badge] (as [dev.kotlinds.pokemonclient.state.PlayerInfo.badges] lists it). Games give one per move they
 * have ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]); HGSS checks them in `src/field_move.c`.
 */
data class FieldMoveRule(val move: MoveId, val badge: String)

/** Whether the party can use a field move right now, and when not, what is missing. */
sealed interface FieldMoveAccess {
    /** A Pokémon knows the move ([monName], party slot [slot]) and the badge is owned. */
    data class Usable(val slot: Int, val monName: String) : FieldMoveAccess

    /** No Pokémon of the party knows the move. */
    data object NoPokemon : FieldMoveAccess

    /** A Pokémon knows it, but the badge [badge] isn't owned yet. */
    data class NoBadge(val badge: String) : FieldMoveAccess

    /** The game doesn't have this field move (or its rule isn't known). */
    data object Unknown : FieldMoveAccess
}

/** Field moves usable now and why the others aren't, read from the party and the badges. */
object FieldMoves {

    /** The access to every [FieldMoveKind] in [state], with the game's [rule]s. */
    fun access(state: GameState, rule: (FieldMoveKind) -> FieldMoveRule?): Map<FieldMoveKind, FieldMoveAccess> =
        FieldMoveKind.entries.associateWith { kind -> access(state, rule(kind)) }

    /** The access to one field move with [rule] (null: the game doesn't know it). */
    fun access(state: GameState, rule: FieldMoveRule?): FieldMoveAccess {
        rule ?: return FieldMoveAccess.Unknown
        // The game takes the first Pokémon knowing the move (GetPartySlotWithMove): eggs never count.
        val mon = state.party.firstOrNull { mon -> !mon.isEgg && mon.moves.any { it.move.id == rule.move } }
            ?: return FieldMoveAccess.NoPokemon
        val badges = state.player?.badges ?: return FieldMoveAccess.NoBadge(rule.badge)
        if (rule.badge !in badges) return FieldMoveAccess.NoBadge(rule.badge)
        return FieldMoveAccess.Usable(mon.slot, mon.displayName)
    }

    /** The moves of [access] that can be used now. */
    fun usable(access: Map<FieldMoveKind, FieldMoveAccess>): Set<FieldMoveKind> =
        access.filterValues { it is FieldMoveAccess.Usable }.keys
}
