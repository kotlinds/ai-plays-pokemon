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
        if (attract != 0) add(VolatileStatus.Infatuated((0 until 4).firstOrNull { attract and (1 shl it) != 0 }?.let { battlerRef(it) }))
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
        if (s2 and (1 shl 23) != 0) add(VolatileStatus.Raging)
        // MOVE_EFFECT_FLAG_* (include/constants/battle.h:163).
        if (me and (0x3 shl 3) != 0) add(VolatileStatus.LockedOn)
        if (me and (1 shl 8) != 0) add(VolatileStatus.Minimized)
        if (me and (1 shl 9) != 0) add(VolatileStatus.Charged)
        if (me and (1 shl 13) != 0) add(VolatileStatus.Imprisoning)
        if (me and (1 shl 14) != 0) add(VolatileStatus.Grudge)
        if (me and (1 shl 24) != 0) add(VolatileStatus.AquaRing)
        if (me and (1 shl 25) != 0) add(VolatileStatus.HealBlocked)
        if (me and (1 shl 26) != 0) add(VolatileStatus.Embargoed)
        if (me and (1 shl 27) != 0) add(VolatileStatus.MagnetRise)
    }

    /**
     * The [BattlerRef] of a game battler id. Battler `i` has the touch-screen layout slot `i`
     * (`BATTLER_TYPE_*_SIDE_SLOT_n`, see `sTouchscreenRectTargetMenuButtons`, src/battle/battle_input.c:464): 0 the
     * player's left, 1 the opponent's RIGHT (its first Pokémon stands on the right as the player sees it), 2 the
     * player's right, 3 the opponent's left. Verified live in a double battle (Route 37 twins: battler 1 Marill is
     * drawn on the right, on both screens). In a single battle the only foe (battler 1) is called [BattlerRef.FOE_LEFT].
     */
    fun battlerRef(battlerId: Int, doubles: Boolean = false): BattlerRef = when (battlerId) {
        0 -> BattlerRef.PLAYER_LEFT
        1 -> if (doubles) BattlerRef.FOE_RIGHT else BattlerRef.FOE_LEFT
        2 -> BattlerRef.PLAYER_RIGHT
        else -> BattlerRef.FOE_LEFT
    }

    /**
     * True when the ability of a battler was announced on screen: one of the switch-in announcement flags of
     * `BattleMon` (+0x28: intimidate, trace, download, anticipation, forewarn, slow start, frisk, mold breaker,
     * pressure; each only set when the battler has that ability, overlay_12_0224E4FC.c:3196+), or Flash Fire
     * activated (bit 31 of the counters word, `UnkBattlemonSub.flashFire`).
     */
    fun abilityAnnounced(announceFlags: Long, counters: Long): Boolean =
        announceFlags and ANNOUNCED_ABILITY_BITS != 0L || (counters shr 31) and 1L == 1L

    /** Bits 1-6 and 8-10 of the `BattleMon` flags word (bit 0 is "sent out", bit 7 "slow start ended"). */
    private const val ANNOUNCED_ABILITY_BITS = 0x77EL
}
