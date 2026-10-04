package dev.kotlinds.pokemonclient.state

/** A battle in progress. */
data class BattleState(
    val kind: BattleKind,
    val isDouble: Boolean,
    /** Who has to choose an action right now (in doubles, each of the player's Pokémon chooses in turn). */
    val actor: BattlerRef?,
    val battlers: List<BattlerState>,
    /** Opposing trainers (empty in wild battles). */
    val trainers: List<String>,
    /**
     * The party as the battle sees it: in battle, the Pokémon sent out swaps places with the one it replaces,
     * so the party screen shows this order, not the saved one. Slot 0 is the active Pokémon.
     */
    val partyOrder: List<MonId>,
    /** The battle message on screen, if any. */
    val message: String?,
    /** Turns played so far (0 on the first turn), when known: Quick Ball / Timer Ball depend on it. */
    val turn: Int? = null,
)

/** Kinds of battles. */
enum class BattleKind { WILD, TRAINER, SCRIPTED }

/** A battle position: the player's side (left / right in doubles) or the opponent's. */
enum class BattlerRef(val wire: String, val isPlayerSide: Boolean) {
    PLAYER_LEFT("ally_left", true),
    PLAYER_RIGHT("ally_right", true),
    FOE_LEFT("foe_left", false),
    FOE_RIGHT("foe_right", false),
}

/** One Pokémon on the field. */
data class BattlerState(
    val ref: BattlerRef,
    /** Stable id when known (always for the player's side). */
    val mon: MonId?,
    val species: Named<SpeciesId>,
    val nickname: String?,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: MajorStatus?,
    val volatile: Set<VolatileStatus>,
    /** Stat stages from -6 to +6 (only the non-zero ones). */
    val statStages: Map<BattleStat, Int>,
    val types: List<String>,
    val moves: List<KnownMove>,
    /**
     * The Pokémon's actual ability, as the game knows it. For an opponent it is hidden knowledge: views only show
     * it once revealed ([abilityRevealed], or a battle message naming it, see `BattleKnowledge`).
     */
    val ability: Named<AbilityId>? = null,
    /** The item it holds right now (hidden knowledge for an opponent, like [ability]). */
    val heldItem: Named<ItemId>? = null,
    /**
     * True once the game announced the ability on screen (Pressure, Intimidate, Mold Breaker, Frisk... on switch-in,
     * Flash Fire once activated).
     */
    val abilityRevealed: Boolean = false,
    /** Capture rate of the species (0..255, higher is easier), for a wild foe. */
    val catchRate: Int? = null,
)

/** Stats that can be raised or lowered during a battle. */
enum class BattleStat { ATTACK, DEFENSE, SPEED, SP_ATTACK, SP_DEFENSE, ACCURACY, EVASION }

/**
 * Battle-only conditions that disappear when the Pokémon switches out or the battle ends (Gen 4 `status2`
 * bits and move-effect flags; see notes-partie-claude-scripts/battle-statuses-spec.md).
 */
sealed interface VolatileStatus {
    data class Confused(val turns: Int) : VolatileStatus
    data class Infatuated(val with: BattlerRef?) : VolatileStatus
    data class Bound(val turns: Int) : VolatileStatus
    /** Can't switch out or flee (Mean Look, Block, Spider Web...): `switch` is unavailable. */
    data object Trapped : VolatileStatus
    data object Cursed : VolatileStatus
    data object Nightmare : VolatileStatus
    data object Flinched : VolatileStatus
    data object FocusEnergy : VolatileStatus
    data object Substitute : VolatileStatus
    data object Recharging : VolatileStatus
    data object LockedIntoMove : VolatileStatus
    data object Transformed : VolatileStatus
    data object Foresight : VolatileStatus
    data object DestinyBond : VolatileStatus
    data object Torment : VolatileStatus
    data object Rampaging : VolatileStatus
    data object Uproar : VolatileStatus
    data object Bide : VolatileStatus
    data object LeechSeeded : VolatileStatus
    data object Rooted : VolatileStatus
    data object Yawned : VolatileStatus
    data class PerishSong(val turns: Int) : VolatileStatus
    /** In the air / underground / underwater for a two-turn move: most moves miss. */
    data object SemiInvulnerable : VolatileStatus
    data object Taunted : VolatileStatus
    data object Encored : VolatileStatus
    data object Disabled : VolatileStatus
}
