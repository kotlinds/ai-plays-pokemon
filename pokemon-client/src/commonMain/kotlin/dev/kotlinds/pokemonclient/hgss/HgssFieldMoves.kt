package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule

/**
 * The field moves of HeartGold / SoulSilver: the move id (include/constants/moves.h) and the badge each one needs
 * outside battle (`FieldMove_Check*` in src/field_move.c, and `CheckBadge` in the std scripts of scr_seq_0146.s).
 * Badges are named as [HgssReader] lists them in [dev.kotlinds.pokemonclient.state.PlayerInfo.badges].
 */
internal object HgssFieldMoves {

    fun rule(move: FieldMoveKind): FieldMoveRule = when (move) {
        FieldMoveKind.CUT -> FieldMoveRule(MoveId(MOVE_CUT), "Hive")
        FieldMoveKind.SURF -> FieldMoveRule(MoveId(MOVE_SURF), "Fog")
        FieldMoveKind.STRENGTH -> FieldMoveRule(MoveId(MOVE_STRENGTH), "Plain")
        FieldMoveKind.ROCK_SMASH -> FieldMoveRule(MoveId(MOVE_ROCK_SMASH), "Zephyr")
        FieldMoveKind.WATERFALL -> FieldMoveRule(MoveId(MOVE_WATERFALL), "Rising")
        FieldMoveKind.WHIRLPOOL -> FieldMoveRule(MoveId(MOVE_WHIRLPOOL), "Glacier")
        FieldMoveKind.ROCK_CLIMB -> FieldMoveRule(MoveId(MOVE_ROCK_CLIMB), "Earth")
    }

    private const val MOVE_CUT = 15
    private const val MOVE_SURF = 57
    private const val MOVE_STRENGTH = 70
    private const val MOVE_WATERFALL = 127
    private const val MOVE_ROCK_SMASH = 249
    private const val MOVE_WHIRLPOOL = 250
    private const val MOVE_ROCK_CLIMB = 431
}
