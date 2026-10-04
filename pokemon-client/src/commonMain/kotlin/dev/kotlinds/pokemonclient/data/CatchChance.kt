package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.MajorStatus

/** The estimated chance of catching the wild Pokémon with one ball. */
data class BallChance(
    val ball: BagItem,
    /** Probability of a capture with one throw, 0..1. */
    val chance: Double,
    /** What the estimate assumes when the ball's bonus depends on something not known here (Dive, Dusk, Repeat...). */
    val assumption: String? = null,
) {
    /** "Great Ball x7: 34%". */
    val label: String
        get() = "${ball.item.name} x${ball.quantity}: ${percent(chance)}" + (assumption?.let { " ($it)" } ?: "")

    private fun percent(p: Double): String = when {
        p >= 1.0 -> "100%"
        p >= 0.995 -> ">99%"
        p < 0.005 -> "<1%"
        else -> "${(p * 100).toInt()}%"
    }
}

/** The catch rate of the wild Pokémon and the chance per ball in the bag. */
data class CatchEstimate(val foe: BattlerState, val catchRate: Int, val balls: List<BallChance>)

/**
 * Generation 4 capture formula, exactly as HeartGold computes it (`BattleSystem_CalculateBallShakes`,
 * src/battle/battle_command.c, integer arithmetic in the game's order):
 *
 * 1. rate = species catch rate (0..255), times the apricorn bonus (Fast ×4 if base speed ≥ 100, Level ×2/×4/×8,
 *    Lure ×3 when fishing, Moon ×4 on the Moon Stone families, Love ×8), capped at 255;
 * 2. ball = bonus ×10: Poké 10, Great 15, Ultra 20, Safari 15, Net 30 on Water / Bug, Nest 40 − level (≥ 10),
 *    Timer turns + 10 (≤ 40), Quick 40 on the first turn, Dive 35 under water, Dusk 35 at night / in caves,
 *    Repeat 30 when already caught, Sport 15, others 10;
 * 3. a = ((rate × ball / 10) × (3·maxHP − 2·HP)) / (3·maxHP);
 * 4. status: asleep / frozen a × 2, poisoned / burned / paralysed a × 1.5;
 * 5. a ≥ 255 (or a Master Ball): caught. Else b = 0xFFFF0 / √√(0xFF0000 / a) (integer square roots), and each of the
 *    4 checks passes when a random u16 < b: chance = (b / 65536)^4.
 *
 * Bonuses that depend on what this view can't know (fishing, night, cave, water, already caught) are left at ×1 and
 * said so ([BallChance.assumption]).
 */
object CatchChance {

    /** The estimate for the single wild foe of [battle] with every ball of [balls], or null outside a wild battle. */
    fun estimate(battle: BattleState, balls: List<BagItem>, data: GameData): CatchEstimate? {
        if (battle.kind != BattleKind.WILD) return null
        val foe = battle.battlers.singleOrNull { !it.ref.isPlayerSide && it.hp > 0 } ?: return null
        val species = data.species(foe.species.id) ?: return null
        val attacker = battle.battlers.firstOrNull { it.ref.isPlayerSide && it.hp > 0 }
        val turn = battle.turn
        return CatchEstimate(foe, species.catchRate, balls.filter { it.quantity > 0 }.map { stack ->
            val ball = stack.item.id.value
            var assumption: String? = null
            var rate = species.catchRate
            var bonus = 10
            when (ball) {
                MASTER_BALL -> return@map BallChance(stack, 1.0)
                ULTRA_BALL -> bonus = 20
                GREAT_BALL, SAFARI_BALL -> bonus = 15
                POKE_BALL -> bonus = 10
                NET_BALL -> if (species.types.any { it == PokemonType.WATER || it == PokemonType.BUG }) bonus = 30
                NEST_BALL -> if (foe.level < 40) bonus = (40 - foe.level).coerceAtLeast(10)
                TIMER_BALL -> if (turn != null) bonus = (turn + 10).coerceAtMost(40) else assumption = "turn count unknown: x1"
                QUICK_BALL -> if (turn == 0) bonus = 40 else if (turn == null) assumption = "x4 on the first turn only"
                DIVE_BALL -> assumption = "x3.5 only under water"
                DUSK_BALL -> assumption = "x3.5 at night or in a cave"
                REPEAT_BALL -> assumption = "x3 if already caught"
                SPORT_BALL -> bonus = 15
                FAST_BALL -> if (species.baseStats.speed >= 100) rate *= 4
                LEVEL_BALL -> attacker?.let { me ->
                    rate *= when {
                        me.level <= foe.level -> 1
                        me.level / 2 <= foe.level -> 2
                        me.level / 4 <= foe.level -> 4
                        else -> 8
                    }
                }
                LURE_BALL -> assumption = "x3 only when fishing"
                MOON_BALL -> if (foe.species.id.value in MOON_BALL_SPECIES) rate *= 4
                LOVE_BALL -> assumption = "x8 on the same species of the other gender"
                HEAVY_BALL -> assumption = "depends on the weight"
            }
            if (ball > SAFARI_BALL) rate = rate.coerceIn(1, 255)
            BallChance(stack, chance(rate, bonus, foe.hp, foe.maxHp, foe.status), assumption)
        })
    }

    /**
     * Probability of a capture with a modified catch [rate] (0..255) and a ball [bonus] ×10, on a Pokémon at
     * [hp] / [maxHp] with [status] (the formula above, steps 3 to 5).
     */
    fun chance(rate: Int, bonus: Int, hp: Int, maxHp: Int, status: MajorStatus?): Double {
        if (maxHp <= 0) return 0.0
        val maxHp3 = maxHp.toLong() * 3
        val lost = maxHp3 - hp.toLong() * 2
        var a = ((rate.toLong() * bonus / 10) * lost) / maxHp3
        when (status) {
            is MajorStatus.Asleep, MajorStatus.Frozen -> a *= 2
            MajorStatus.Poisoned, is MajorStatus.BadlyPoisoned, MajorStatus.Burned, MajorStatus.Paralyzed -> a = a * 15 / 10
            null -> Unit
        }
        if (a >= 255) return 1.0
        if (a <= 0) return 0.0
        val b = 0xFFFF0L / isqrt(isqrt(0xFF0000L / a))
        val p = (b.coerceAtMost(65536L)).toDouble() / 65536.0
        return p * p * p * p
    }

    /** Integer square root (the DS's hardware square root, rounding down). */
    private fun isqrt(value: Long): Long {
        if (value <= 0) return 0
        var r = kotlin.math.sqrt(value.toDouble()).toLong()
        while (r * r > value) r--
        while ((r + 1) * (r + 1) <= value) r++
        return r
    }

    // Ball item ids of Generation 4 (include/constants/items.h).
    private const val MASTER_BALL = 1
    private const val ULTRA_BALL = 2
    private const val GREAT_BALL = 3
    private const val POKE_BALL = 4
    private const val SAFARI_BALL = 5
    private const val NET_BALL = 6
    private const val DIVE_BALL = 7
    private const val NEST_BALL = 8
    private const val REPEAT_BALL = 9
    private const val TIMER_BALL = 10
    private const val DUSK_BALL = 13
    private const val QUICK_BALL = 15
    private const val FAST_BALL = 492
    private const val LEVEL_BALL = 493
    private const val LURE_BALL = 494
    private const val HEAVY_BALL = 495
    private const val LOVE_BALL = 496
    private const val MOON_BALL = 498
    private const val SPORT_BALL = 499

    /** `sMoonBallPokemon`: the Nidoran, Clefairy, Jigglypuff and Skitty families. */
    private val MOON_BALL_SPECIES = setOf(29, 30, 31, 32, 33, 34, 35, 36, 39, 40, 173, 174, 300, 301)
}
