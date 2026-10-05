package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.BaseStats
import dev.kotlinds.pokemonclient.data.ExpCurves
import dev.kotlinds.pokemonclient.data.SpeciesInfo
import dev.kotlinds.pokemonclient.state.SpeciesId

/**
 * Plausibility of a decoded Pokémon: a reading taken while the game rewrites the structure (see
 * [HgssPokemon.decode]) must never be shown, cached or turned into events.
 *
 * The box blocks have a checksum; the party data (status, level, HP, stats) has none, so it is checked against the
 * box: the level against the experience (growth rate) and the stats against the base stats, IVs, EVs and nature
 * (CalcMonLevelAndStats, src/pokemon.c). Those checks need the species data of the ROM ([HgssData.gameData]); without
 * it only the bounds are checked.
 */
object HgssMonCheck {

    /** Why a reading was rejected. */
    sealed interface Problem {
        /** Short explanation for warnings. */
        val detail: String

        /** No reading of the box blocks matched the checksum. */
        data object ChecksumMismatch : Problem {
            override val detail = "checksum mismatch"
        }

        data class BadSpecies(val species: Int) : Problem {
            override val detail get() = "species $species"
        }

        /** Level out of bounds, or above what the experience gives ([levelFromExp] when known). */
        data class BadLevel(val level: Int, val levelFromExp: Int?) : Problem {
            override val detail get() = "level $level" + (levelFromExp?.let { " (experience says $it)" } ?: "")
        }

        data class BadHp(val hp: Int, val maxHp: Int) : Problem {
            override val detail get() = "hp $hp/$maxHp"
        }

        /** A stat that the base stats, IVs, EVs, nature and level can't give ([expected] when known). */
        data class BadStat(val stat: String, val value: Int, val expected: IntRange?) : Problem {
            override val detail get() = "$stat $value" + (expected?.let { " (expected ${it.first}..${it.last})" } ?: "")
        }

        /**
         * Moves no Pokémon can have: an id above the last move, a move after an empty slot, the same move twice, or more
         * PP than the move's maximum (a reading caught mid-rewrite whose checksum matched by accident, see
         * [HgssPokemon.decode]: "learned MOVE_57918, forgot Strength").
         */
        data class BadMoves(val moves: List<Int>, val pp: List<Int>) : Problem {
            override val detail get() = "moves ${moves.joinToString("/")} pp ${pp.joinToString("/")}"
        }

        /** Status word with unknown bits or several major conditions. */
        data class BadStatus(val raw: Long) : Problem {
            override val detail get() = "status 0x${raw.toString(16)}"
        }
    }

    /** Highest species id of Gen 4 (Arceus). */
    private const val MAX_SPECIES = 493

    /** Highest move id of Gen 4 (Shadow Force, NUM_MOVES in include/constants/moves.h). */
    private const val MAX_MOVE = 467

    /** Highest stat value a Gen 4 Pokémon can have (well above Blissey's 714 HP). */
    private const val MAX_STAT = 999

    /** Species whose HP is always 1 (Shedinja). */
    private const val SHEDINJA = 292

    /** True when [mon] can be shown as is. */
    fun isPlausible(mon: HgssPokemon.Decoded): Boolean = problems(mon).isEmpty()

    /** Everything wrong with [mon] (empty when it is plausible). */
    fun problems(mon: HgssPokemon.Decoded): List<Problem> = buildList {
        if (!mon.checksumOk) add(Problem.ChecksumMismatch)
        val species = mon.species
        if (species !in 1..MAX_SPECIES) {
            add(Problem.BadSpecies(species))
            return@buildList
        }
        movesProblem(mon)?.let(::add)
        if (mon.party == null) return@buildList
        if (!statusOk(mon.status)) add(Problem.BadStatus(mon.status))
        val info = HgssData.gameData?.species(SpeciesId(species))
        val level = mon.level
        val rate = info?.growthRate
        val levelFromExp = rate?.let { ExpCurves.levelForExp(it, mon.exp) }
        // Experience is given before the level goes up (the battle's exp gauge, src/battle/battle_command.c:6085):
        // the level can lag behind, never lead.
        if (level !in 1..ExpCurves.MAX_LEVEL || (!mon.isEgg && levelFromExp != null && level > levelFromExp)) {
            add(Problem.BadLevel(level, levelFromExp))
        }
        if (mon.maxHp !in 1..MAX_STAT || mon.hp !in 0..mon.maxHp) add(Problem.BadHp(mon.hp, mon.maxHp))
        val stats = listOf("atk" to mon.atk, "def" to mon.def, "speed" to mon.speed, "spAtk" to mon.spAtk, "spDef" to mon.spDef)
        stats.filter { (_, v) -> v !in 1..MAX_STAT }.forEach { (name, v) -> add(Problem.BadStat(name, v, null)) }
        if (isEmpty() && info != null && !mon.isEgg && mon.form == 0) addAll(statProblems(mon, info))
    }

    /**
     * The moves of [mon] when no Pokémon can know them (see [Problem.BadMoves]): ids in 1..[MAX_MOVE], packed from the
     * first slot (a Pokémon always knows at least one move), all different, PP within the maximum with the PP Ups.
     */
    private fun movesProblem(mon: HgssPokemon.Decoded): Problem.BadMoves? {
        val moves = (0 until 4).map { mon.move(it) }
        val pp = (0 until 4).map { mon.movePp(it) }
        val known = moves.takeWhile { it != 0 }
        val ok = known.isNotEmpty() && moves.drop(known.size).all { it == 0 } && known.all { it in 1..MAX_MOVE } &&
            known.toSet().size == known.size &&
            known.indices.all { i -> HgssData.moveData[known[i]] == null || pp[i] <= HgssPokemon.maxPp(known[i], mon.movePpUps(i)) }
        return if (ok) null else Problem.BadMoves(moves, pp)
    }

    /** Sleep turns (bits 0-2) or one of poison / burn / freeze / paralysis / bad poison, plus the toxic counter. */
    private fun statusOk(status: Long): Boolean {
        if (status and 0xFFFFF000L != 0L) return false
        val majors = listOf(0x07L, 0x08L, 0x10L, 0x20L, 0x40L, 0x80L).count { status and it != 0L }
        return majors <= 1
    }

    /**
     * The stats must be what CalcMonStats gives at this level (or the one before: the battle raises the level a
     * moment before it recalculates the stats), with the EVs of now or fewer (stats are only recalculated on level
     * ups and a few items, not when EVs are gained).
     */
    private fun statProblems(mon: HgssPokemon.Decoded, info: SpeciesInfo): List<Problem> {
        val base = info.baseStats
        val ivs = IntArray(6) { ((mon.ivWord shr (5 * it)) and 0x1F).toInt() }
        val evs = IntArray(6) { HgssPokemon.u8(mon.blockA, 0x10 + it) }
        val nature = (mon.personality % 25).toInt()
        val level = mon.level
        val low = (level - 1).coerceAtLeast(1)
        fun range(index: Int): IntRange {
            val b = base.at(index)
            return if (index == 0) {
                if (mon.species == SHEDINJA) 1..1 else hpStat(b, ivs[0], 0, low)..hpStat(b, ivs[0], evs[0], level)
            } else {
                stat(b, ivs[index], 0, low, nature, index)..stat(b, ivs[index], evs[index], level, nature, index)
            }
        }
        // Order of the IVs / EVs / stats in the structures: HP, Attack, Defense, Speed, Sp. Atk, Sp. Def.
        val values = listOf("maxHp" to mon.maxHp, "atk" to mon.atk, "def" to mon.def, "speed" to mon.speed, "spAtk" to mon.spAtk, "spDef" to mon.spDef)
        return values.mapIndexedNotNull { i, (name, value) -> range(i).takeIf { value !in it }?.let { Problem.BadStat(name, value, it) } }
    }

    private fun BaseStats.at(index: Int) = when (index) {
        0 -> hp
        1 -> attack
        2 -> defense
        3 -> speed
        4 -> spAttack
        else -> spDefense
    }

    private fun hpStat(base: Int, iv: Int, ev: Int, level: Int) = (2 * base + iv + ev / 4) * level / 100 + level + 10

    /**
     * A stat other than HP, with the nature (ModifyStatByNature): nature n raises stat n / 5 and lowers stat n % 5,
     * in the order Attack, Defense, Speed, Sp. Atk, Sp. Def ([index] 1..5 here).
     */
    private fun stat(base: Int, iv: Int, ev: Int, level: Int, nature: Int, index: Int): Int {
        val raw = (2 * base + iv + ev / 4) * level / 100 + 5
        val up = nature / 5 + 1
        val down = nature % 5 + 1
        return when {
            up == down -> raw
            index == up -> raw * 110 / 100
            index == down -> raw * 90 / 100
            else -> raw
        }
    }
}
