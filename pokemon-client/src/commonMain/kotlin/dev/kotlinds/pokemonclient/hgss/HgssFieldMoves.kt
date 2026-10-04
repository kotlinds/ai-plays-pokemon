package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule

/**
 * The field moves of HeartGold / SoulSilver: the move id (include/constants/moves.h) and the badge each one needs
 * outside battle (`FieldMove_Check*` in src/field_move.c, and `CheckBadge` in the std scripts of scr_seq_0146.s).
 * Badges are checked by id ([dev.kotlinds.pokemonclient.state.PlayerInfo.badgeIds]); the names are for messages.
 */
internal object HgssFieldMoves {

    fun rule(move: FieldMoveKind): FieldMoveRule = when (move) {
        FieldMoveKind.CUT -> FieldMoveRule(MoveId(MOVE_CUT), "Hive", BADGE_HIVE)
        FieldMoveKind.SURF -> FieldMoveRule(MoveId(MOVE_SURF), "Fog", BADGE_FOG)
        FieldMoveKind.STRENGTH -> FieldMoveRule(MoveId(MOVE_STRENGTH), "Plain", BADGE_PLAIN)
        FieldMoveKind.ROCK_SMASH -> FieldMoveRule(MoveId(MOVE_ROCK_SMASH), "Zephyr", BADGE_ZEPHYR)
        FieldMoveKind.WATERFALL -> FieldMoveRule(MoveId(MOVE_WATERFALL), "Rising", BADGE_RISING)
        FieldMoveKind.WHIRLPOOL -> FieldMoveRule(MoveId(MOVE_WHIRLPOOL), "Glacier", BADGE_GLACIER)
        FieldMoveKind.ROCK_CLIMB -> FieldMoveRule(MoveId(MOVE_ROCK_CLIMB), "Earth", BADGE_EARTH)
    }

    /** Badge ids (`BADGE_*`: bit n of the Johto byte, 8 + n for Kanto, include/constants/badges.h). */
    private const val BADGE_ZEPHYR = 0
    private const val BADGE_HIVE = 1
    private const val BADGE_PLAIN = 2
    private const val BADGE_FOG = 3
    private const val BADGE_STORM = 4
    private const val BADGE_MINERAL = 5
    private const val BADGE_GLACIER = 6
    private const val BADGE_RISING = 7
    private const val BADGE_EARTH = 15

    private const val MOVE_CUT = 15
    private const val MOVE_SURF = 57
    private const val MOVE_STRENGTH = 70
    private const val MOVE_WATERFALL = 127
    private const val MOVE_ROCK_SMASH = 249
    private const val MOVE_WHIRLPOOL = 250
    private const val MOVE_ROCK_CLIMB = 431
}
