package dev.kotlinds.pokemonclient.state

/** One Pokémon of the party (or of a PC box). */
data class PartyMon(
    val id: MonId,
    /** Position in the saved party order (0-5); in battle the screen order may differ, see [BattleState.partyOrder]. */
    val slot: Int,
    val species: Named<SpeciesId>,
    /** Nickname when it differs from the species name. */
    val nickname: String?,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: MajorStatus?,
    val types: List<String>,
    val heldItem: Named<ItemId>?,
    val ability: String?,
    val moves: List<KnownMove>,
    val stats: Map<Stat, Int>,
    val exp: Long,
    /** Experience still needed to reach the next level (0 at level 100), null until the growth rates are read from the ROM. */
    val expToNextLevel: Long?,
    val isEgg: Boolean,
) {
    val fainted: Boolean get() = hp == 0 && !isEgg

    /** The name shown in game: the nickname if any, else the species (or "Egg"). */
    val displayName: String get() = if (isEgg) "Egg" else nickname ?: species.name
}

/** A move a Pokémon knows, with its remaining PP. */
data class KnownMove(
    val move: Named<MoveId>,
    val pp: Int,
    val maxPp: Int,
    val type: String? = null,
)

/** The persistent ("major") status conditions, shown next to a Pokémon. */
sealed interface MajorStatus {
    data class Asleep(val turns: Int) : MajorStatus
    data object Poisoned : MajorStatus
    data class BadlyPoisoned(val counter: Int) : MajorStatus
    data object Burned : MajorStatus
    data object Frozen : MajorStatus
    data object Paralyzed : MajorStatus
}

/** The six stats. */
enum class Stat { HP, ATTACK, DEFENSE, SPEED, SP_ATTACK, SP_DEFENSE }
