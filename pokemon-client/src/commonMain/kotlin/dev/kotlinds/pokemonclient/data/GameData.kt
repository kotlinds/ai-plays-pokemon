package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId

/**
 * The static data of a Pokémon game (the Pokédex-like knowledge: species, moves, items, type chart, TMs), read from
 * the game's own ROM.
 *
 * Ids are the source of truth: every lookup takes a typed id and every record carries typed values (enums for
 * types, categories, pockets). Names are display only: they come from the ROM's text, so they are in the ROM's
 * language (a French ROM gives French names) and must never be matched against to decide anything.
 *
 * Lookups return null for ids the game doesn't define. Implementations decode lazily and cache.
 */
interface GameData {

    /** Number of species records (the national dex plus the extra forms the game stores after it). */
    val speciesCount: Int

    /** Number of move records (move ids are `0 until moveCount`, 0 being "no move"). */
    val moveCount: Int

    /** Number of item ids (`0 until itemCount`, 0 being "no item"). */
    val itemCount: Int

    /** Base data of species [id]: types, base stats, abilities, catch rate, evolutions, TM / HM compatibility. */
    fun species(id: SpeciesId): SpeciesInfo?

    /** Moves species [id] learns by leveling up, in the game's order (by level). Empty when unknown. */
    fun learnset(id: SpeciesId): List<LevelMove>

    /** Battle data of move [id]: type, category, power, accuracy, PP, priority. */
    fun move(id: MoveId): MoveInfo?

    /** Data of item [id]: name, bag pocket, price. */
    fun item(id: ItemId): ItemInfo?

    /** Display name of ability [id], null when the game has none. */
    fun abilityName(id: AbilityId): String?

    /** Display name of [type] in the game's language. */
    fun typeName(type: PokemonType): String

    /** Display name of trainer class [id] (e.g. "Youngster"), null when unknown. */
    fun trainerClassName(id: Int): String?

    /** Effectiveness of every attacking type against every defending type. */
    val typeChart: TypeChart

    /** The move taught by [machine], null for a machine the game doesn't have. */
    fun machineMove(machine: MachineId): MoveId?

    /** The machine (TM / HM) that item [item] is, null when it is not a machine. */
    fun machineOf(item: ItemId): MachineId?

    /** Line [line] of text bank [bank] (control codes removed), null when out of range. */
    fun text(bank: TextBankId, line: Int): String?
}

/**
 * A text bank (message file) of the game, by index. Indices are the same for every language of a game: the bank
 * holds the same lines (in another language), so ids found here stay valid on a translated ROM.
 */
@kotlin.jvm.JvmInline
value class TextBankId(val value: Int)

/**
 * The 18 types of Generation 4, in the game's own order (`TYPE_*`, include/constants/pokemon.h): [gameIndex] is the
 * value stored in the ROM. [MYSTERY] is the "???" type (Curse).
 *
 * [label] is a stable English label for logs and agent APIs; for the name the game shows, use [GameData.typeName].
 */
enum class PokemonType(val gameIndex: Int, val label: String) {
    NORMAL(0, "Normal"),
    FIGHTING(1, "Fighting"),
    FLYING(2, "Flying"),
    POISON(3, "Poison"),
    GROUND(4, "Ground"),
    ROCK(5, "Rock"),
    BUG(6, "Bug"),
    GHOST(7, "Ghost"),
    STEEL(8, "Steel"),
    MYSTERY(9, "???"),
    FIRE(10, "Fire"),
    WATER(11, "Water"),
    GRASS(12, "Grass"),
    ELECTRIC(13, "Electric"),
    PSYCHIC(14, "Psychic"),
    ICE(15, "Ice"),
    DRAGON(16, "Dragon"),
    DARK(17, "Dark");

    companion object {
        /** The type stored as [index] in the ROM, null for an unknown value. */
        fun fromGameIndex(index: Int): PokemonType? = entries.getOrNull(index)
    }
}

/** Damage category of a move (Generation 4 physical / special split, `MoveTbl.category`). */
enum class MoveCategory(val label: String) {
    PHYSICAL("Physical"),
    SPECIAL("Special"),
    STATUS("Status");

    companion object {
        /** The category stored as [index] in the ROM (0 physical, 1 special, 2 status), null otherwise. */
        fun fromGameIndex(index: Int): MoveCategory? = entries.getOrNull(index)
    }
}

/** The bag pocket an item goes into (`POCKET_*`, include/constants/items.h; [gameIndex] is the ROM value). */
enum class ItemPocket(val gameIndex: Int) {
    ITEMS(0),
    MEDICINE(1),
    BALLS(2),
    TMS_HMS(3),
    BERRIES(4),
    MAIL(5),
    BATTLE_ITEMS(6),
    KEY_ITEMS(7);

    companion object {
        fun fromGameIndex(index: Int): ItemPocket? = entries.getOrNull(index)
    }
}

/** Experience curve of a species (`GROWTH_*`, include/constants/pokemon.h; [gameIndex] is the ROM value). */
enum class GrowthRate(val gameIndex: Int) {
    MEDIUM_FAST(0),
    ERRATIC(1),
    FLUCTUATING(2),
    MEDIUM_SLOW(3),
    FAST(4),
    SLOW(5);

    companion object {
        fun fromGameIndex(index: Int): GrowthRate? = entries.getOrNull(index)
    }
}

/** The six base stats of a species. */
data class BaseStats(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val speed: Int,
    val spAttack: Int,
    val spDefense: Int,
) {
    /** Sum of the six stats (base stat total). */
    val total: Int get() = hp + attack + defense + speed + spAttack + spDefense
}

/**
 * A TM or HM, by its machine number: 1..92 are TM01..TM92, 93..100 are HM01..HM08 (Generation 4).
 * The item of machine n is `ITEM_TM01 + n - 1`.
 */
@kotlin.jvm.JvmInline
value class MachineId(val number: Int) {
    /** True for the hidden machines (HM01..HM08). */
    val isHm: Boolean get() = number > TM_COUNT

    /** "TM01" .. "TM92", "HM01" .. "HM08". */
    val label: String get() = if (isHm) "HM" + (number - TM_COUNT).toString().padStart(2, '0') else "TM" + number.toString().padStart(2, '0')

    override fun toString(): String = label

    companion object {
        /** Number of TMs in Generation 4. */
        const val TM_COUNT = 92

        /** Number of HMs in Generation 4. */
        const val HM_COUNT = 8

        /** Every machine, TM01 first. */
        val all: List<MachineId> = (1..TM_COUNT + HM_COUNT).map { MachineId(it) }
    }
}

/** How an evolution is triggered (`EVO_*`, include/constants/pokemon.h, in ROM order). */
enum class EvolutionMethod {
    NONE,
    /** High friendship (param unused). */
    FRIENDSHIP,
    FRIENDSHIP_DAY,
    FRIENDSHIP_NIGHT,
    /** Reaching level `param`. */
    LEVEL,
    TRADE,
    /** Trade while holding item `param`. */
    TRADE_ITEM,
    /** Using item `param` (an evolution stone). */
    STONE,
    LEVEL_ATK_GT_DEF,
    LEVEL_ATK_EQ_DEF,
    LEVEL_ATK_LT_DEF,
    LEVEL_PID_LO,
    LEVEL_PID_HI,
    LEVEL_NINJASK,
    LEVEL_SHEDINJA,
    /** High beauty (param = minimum beauty). */
    BEAUTY,
    STONE_MALE,
    STONE_FEMALE,
    /** Level up during the day holding item `param`. */
    ITEM_DAY,
    /** Level up during the night holding item `param`. */
    ITEM_NIGHT,
    /** Level up knowing move `param`. */
    HAS_MOVE,
    /** Level up with species `param` in the party. */
    OTHER_PARTY_MON,
    LEVEL_MALE,
    LEVEL_FEMALE,
    /** Level up at Mt. Coronet (Sinnoh; impossible in HG/SS). */
    CORONET,
    /** Level up near the Eterna Forest moss rock (Sinnoh). */
    ETERNA,
    /** Level up near the Route 217 ice rock (Sinnoh). */
    ROUTE217;

    companion object {
        fun fromGameIndex(index: Int): EvolutionMethod? = entries.getOrNull(index)
    }
}

/** One way species evolves: [method] with its parameter [param] (a level, an item id, a move id... see the method). */
data class Evolution(val method: EvolutionMethod, val param: Int, val target: SpeciesId)

/** A move learned at [level] by leveling up. */
data class LevelMove(val level: Int, val move: MoveId)

/** Base data of one species. */
data class SpeciesInfo(
    val id: SpeciesId,
    /** Display name in the ROM's language. */
    val name: String,
    /** One or two types (a single-type species has one entry). */
    val types: List<PokemonType>,
    val baseStats: BaseStats,
    /** The one or two abilities a member of the species can have (no "none" entries). */
    val abilities: List<AbilityId>,
    /** Capture rate (0..255, higher is easier). */
    val catchRate: Int,
    /** Base experience yield when defeated. */
    val baseExperience: Int,
    val growthRate: GrowthRate?,
    val evolutions: List<Evolution>,
    /** The TMs / HMs the species can learn. */
    val machines: Set<MachineId>,
)

/** Battle data of one move. */
data class MoveInfo(
    val id: MoveId,
    /** Display name in the ROM's language. */
    val name: String,
    val type: PokemonType,
    val category: MoveCategory,
    /** Base power, 0 for moves without a fixed power (status moves, OHKO, variable damage). */
    val power: Int,
    /** Accuracy in percent, 0 for moves that never miss (or don't check accuracy). */
    val accuracy: Int,
    /** Base PP (without PP Ups). */
    val pp: Int,
    /** Priority bracket (-7..+5; 0 for most moves, +1 for Quick Attack). */
    val priority: Int,
    /** Chance in percent of the secondary effect, 0 when none. */
    val effectChance: Int,
    /**
     * True for moves whose damage ignores type effectiveness (Seismic Toss, Night Shade, Dragon Rage, SonicBoom, Super
     * Fang, Psywave, Endeavor, Counter, Mirror Coat, Metal Burst, Bide, one-hit KO moves): only a type immunity
     * (no effect) applies, never x2 / x0.5.
     */
    val fixedDamage: Boolean = false,
    /** Who the move hits (one target, both foes, everyone but the user...). */
    val target: MoveTarget = MoveTarget.SELECTED,
)

/**
 * Who a move hits, from the game's move range (`RANGE_*` flags, include/constants/moves.h). In single battles every
 * move hits the one foe (or the user); the difference matters in double battles.
 */
enum class MoveTarget {
    /** One target chosen by the player (any adjacent battler). */
    SELECTED,

    /** One target picked by the move itself (Curse depends on the user's type, Counter on who hit last...). */
    DEPENDS,

    /** A random foe (Thrash, Outrage...). */
    RANDOM_FOE,

    /** Both foes at once (Icy Wind, Rock Slide, Blizzard...). */
    ALL_FOES,

    /** Every other battler, the ally included (Earthquake, Surf, Discharge, Explosion...). */
    ALL_OTHERS,

    /** The user only (Swords Dance, Recover...). */
    USER,

    /** The user's side of the field (Reflect, Light Screen...). */
    USER_SIDE,

    /** The whole field (weather, Trick Room...). */
    FIELD,

    /** The foes' side of the field (Spikes...). */
    FOES_SIDE,

    /** The ally only (Helping Hand). */
    ALLY,

    /** The user or its ally (Acupressure). */
    USER_OR_ALLY,

    /** One foe in front (Me First). */
    FRONT;

    /** True when the move hits several battlers at once (its power is reduced in doubles, it may hit the ally). */
    val isSpread: Boolean get() = this == ALL_FOES || this == ALL_OTHERS
}

/** Data of one item. */
data class ItemInfo(
    val id: ItemId,
    /** Display name in the ROM's language. */
    val name: String,
    /** The bag pocket it goes into. */
    val pocket: ItemPocket?,
    /** Buying price in Poké Dollars, 0 when it can't be bought / sold. */
    val price: Int,
)

/** The multiplier of one attacking type against one defending type. */
enum class Effectiveness(val multiplier: Double) {
    NO_EFFECT(0.0),
    NOT_VERY_EFFECTIVE(0.5),
    NORMAL(1.0),
    SUPER_EFFECTIVE(2.0),
}

/**
 * The type chart: the multiplier of each attacking type against each defending type.
 *
 * [ignoredByForesight] lists the pairs the game skips when the target is identified by Foresight / Odor Sleuth or
 * the attacker has Scrappy (Normal and Fighting against Ghost).
 */
class TypeChart(
    private val matrix: Map<Pair<PokemonType, PokemonType>, Effectiveness>,
    val ignoredByForesight: Set<Pair<PokemonType, PokemonType>>,
) {
    /** Effectiveness of [attacking] against a single [defending] type. */
    fun effectiveness(attacking: PokemonType, defending: PokemonType): Effectiveness =
        matrix[attacking to defending] ?: Effectiveness.NORMAL

    /** Combined multiplier of [attacking] against a Pokémon of types [defending] (product over its types). */
    fun multiplier(attacking: PokemonType, defending: List<PokemonType>): Double =
        defending.distinct().fold(1.0) { acc, t -> acc * effectiveness(attacking, t).multiplier }

    /** The pairs that are not [Effectiveness.NORMAL]. */
    val matchups: Map<Pair<PokemonType, PokemonType>, Effectiveness> get() = matrix
}
