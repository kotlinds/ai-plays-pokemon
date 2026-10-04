package dev.kotlinds.pokemonclient.state

/**
 * The Pokémon storage system (PC boxes), read from the save data in RAM like the party: known everywhere, not only
 * while the PC is open.
 */
data class PcStorage(
    /** The box the PC opens on (0-based). */
    val currentBox: Int,
    /** Every box, in order, with what it holds. */
    val boxes: List<PcBoxContents>,
) {
    /** Every stored Pokémon, box by box. */
    val mons: List<BoxMon> get() = boxes.flatMap { it.mons }

    /** The stored Pokémon with id [id], or null. */
    fun find(id: MonId): BoxMon? = boxes.firstNotNullOfOrNull { box -> box.mons.firstOrNull { it.id == id } }
}

/** One PC box: its name as the player set it and the Pokémon in it (empty slots left out). */
data class PcBoxContents(
    /** 0-based box number. */
    val index: Int,
    val name: String,
    val mons: List<BoxMon>,
    /** Slots in a box (30 in Generation 4). */
    val capacity: Int,
) {
    val isFull: Boolean get() = mons.size >= capacity
}

/** A Pokémon stored in a PC box. */
data class BoxMon(
    /** The same stable id as in the party (it doesn't change when the Pokémon moves between party and boxes). */
    val id: MonId,
    /** 0-based box and slot (0-29, row by row). */
    val box: Int,
    val slot: Int,
    val species: Named<SpeciesId>,
    /** Nickname when it differs from the species name. */
    val nickname: String?,
    /** Computed from the experience (boxes don't store the level), null when the growth rate isn't known. */
    val level: Int?,
    val heldItem: Named<ItemId>?,
    val isEgg: Boolean,
) {
    /** The name shown in game: the nickname if any, else the species (or "Egg"). */
    val displayName: String get() = if (isEgg) "Egg" else nickname ?: species.name
}

/** The game's OPTIONS, read from the save data. */
data class GameOptions(
    val textSpeed: TextSpeed,
    /** Battle animations on. */
    val battleScene: Boolean,
    val battleStyle: BattleStyle,
)

/** How fast messages print. */
enum class TextSpeed { SLOW, MID, FAST }

/** SHIFT offers a switch when the opponent sends a new Pokémon; SET doesn't. */
enum class BattleStyle { SHIFT, SET }

/**
 * A trainer standing on the map, told by the trainer its script battles (never by its sprite or its words).
 */
data class FieldTrainer(
    /** The game's trainer id (stable, language-independent). */
    val trainerId: Int,
    /** Trainer class name in the game's language ("Psychic", "Kimono Girl"...). */
    val trainerClass: String,
    /** The trainer's name in the game's language ("Eli"). */
    val name: String,
    /** True once beaten (the trainer's flag), null when unknown. */
    val defeated: Boolean?,
    /** Tiles the trainer sees ahead in the direction it faces (0: it only battles when talked to). */
    val sightRange: Int,
) {
    /** "Psychic Eli". */
    val fullName: String get() = listOf(trainerClass, name).filter { it.isNotBlank() }.joinToString(" ")
}

/** One item a shop sells, with its price (null when unknown). */
data class ShopItem(val item: Named<ItemId>, val price: Int?)
