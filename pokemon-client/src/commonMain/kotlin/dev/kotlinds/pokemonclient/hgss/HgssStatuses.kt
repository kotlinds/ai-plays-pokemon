package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.MajorStatus
import dev.kotlinds.pokemonclient.state.VolatileStatus

/**
 * Decodes the Gen 4 status words of a Pokémon (include/constants/battle.h), as stored in `Pokemon` and
 * `BattleMon` (see notes-partie-claude-scripts/battle-statuses-spec.md for the full spec).
 */
object HgssStatuses {

    /** Major status (STATUS_* bits): sleep turns in bits 0-2, poison 3, burn 4, freeze 5, paralysis 6, toxic 7. */
    fun major(status: Long): MajorStatus? {
        val s = status.toInt()
        return when {
            s and 0x7 != 0 -> MajorStatus.Asleep(s and 0x7)
            s and 0x80 != 0 -> MajorStatus.BadlyPoisoned((s shr 8) and 0xF)
            s and 0x8 != 0 -> MajorStatus.Poisoned
            s and 0x10 != 0 -> MajorStatus.Burned
            s and 0x20 != 0 -> MajorStatus.Frozen
            s and 0x40 != 0 -> MajorStatus.Paralyzed
            else -> null
        }
    }

    /**
     * Volatile conditions from `status2` (STATUS2_*), `moveEffectFlags` (MOVE_EFFECT_FLAG_*) and the counters
     * word of `BattleMon.unk88` (disable / encore / taunt / perish song turns).
     */
    fun volatile(status2: Long, moveEffects: Long, counters: Long): Set<VolatileStatus> = buildSet {
        val s2 = status2.toInt()
        val me = moveEffects.toInt()
        val c = counters.toInt()
        val confusion = s2 and 0x7
        if (confusion != 0) add(VolatileStatus.Confused(confusion))
        if (s2 and (1 shl 3) != 0) add(VolatileStatus.Flinched)
        if (s2 and (0x7 shl 4) != 0) add(VolatileStatus.Uproar)
        if (s2 and (0x3 shl 8) != 0) add(VolatileStatus.Bide)
        if (s2 and (0x3 shl 10) != 0) add(VolatileStatus.Rampaging)
        if (s2 and (1 shl 12) != 0) add(VolatileStatus.LockedIntoMove)
        val bind = (s2 shr 13) and 0x7
        if (bind != 0) add(VolatileStatus.Bound(bind))
        val attract = (s2 shr 16) and 0xF
        if (attract != 0) add(VolatileStatus.Infatuated(BattlerRef.entries.firstOrNull { attract and (1 shl it.battlerId) != 0 }))
        if (s2 and (1 shl 20) != 0) add(VolatileStatus.FocusEnergy)
        if (s2 and (1 shl 21) != 0) add(VolatileStatus.Transformed)
        if (s2 and (1 shl 22) != 0) add(VolatileStatus.Recharging)
        if (s2 and (1 shl 24) != 0) add(VolatileStatus.Substitute)
        if (s2 and (1 shl 25) != 0) add(VolatileStatus.DestinyBond)
        if (s2 and (1 shl 27) != 0) add(VolatileStatus.Nightmare)
        if (s2 and (1 shl 28) != 0) add(VolatileStatus.Cursed)
        if (s2 and (1 shl 29) != 0) add(VolatileStatus.Foresight)
        if (s2 and (1 shl 31) != 0) add(VolatileStatus.Torment)
        // Trapped: Mean Look / Block / Spider Web, a binding move, or rooted by Ingrain (overlay_12_0224E4FC.c:2782).
        if (s2 and (1 shl 26) != 0 || bind != 0 || me and (1 shl 10) != 0) add(VolatileStatus.Trapped)
        if (me and (1 shl 2) != 0) add(VolatileStatus.LeechSeeded)
        if (me and (1 shl 10) != 0) add(VolatileStatus.Rooted)
        if (me and (0x3 shl 11) != 0) add(VolatileStatus.Yawned)
        if (me and ((1 shl 6) or (1 shl 7) or (1 shl 18) or (1 shl 29)) != 0) add(VolatileStatus.SemiInvulnerable)
        if (me and (1 shl 5) != 0) add(VolatileStatus.PerishSong((c shr 13) and 0x3))
        if (c and 0x7 != 0) add(VolatileStatus.Disabled)
        if ((c shr 3) and 0x7 != 0) add(VolatileStatus.Encored)
        if ((c shr 8) and 0x7 != 0) add(VolatileStatus.Taunted)
    }

    /** Battler ids used by the game: 0 player (left), 1 foe (left), 2 player (right), 3 foe (right). */
    private val BattlerRef.battlerId: Int
        get() = when (this) {
            BattlerRef.PLAYER_LEFT -> 0
            BattlerRef.FOE_LEFT -> 1
            BattlerRef.PLAYER_RIGHT -> 2
            BattlerRef.FOE_RIGHT -> 3
        }

    /** The [BattlerRef] of a game battler id. */
    fun battlerRef(battlerId: Int): BattlerRef = when (battlerId) {
        0 -> BattlerRef.PLAYER_LEFT
        1 -> BattlerRef.FOE_LEFT
        2 -> BattlerRef.PLAYER_RIGHT
        else -> BattlerRef.FOE_RIGHT
    }
}
