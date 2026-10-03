package dev.kotlinds.pokemonclient.state

import kotlin.jvm.JvmInline

/**
 * Stable identity of one Pokémon, the same in the party, the PC boxes and battles, before and after a trade.
 *
 * Gen 4: the personality value (PID, drawn when the Pokémon is generated) plus the original trainer's full id
 * (trainer id + secret id, 32 bits). Two identical un-nicknamed Pokémon still have different ids, which the
 * name alone can't tell apart.
 */
data class MonId(val personality: Long, val originalTrainerId: Long) {
    /** Wire form used in agent APIs, e.g. `mon:a3f1c2d4.0e2117b3`. */
    override fun toString() = "mon:${personality.toHex8()}.${originalTrainerId.toHex8()}"

    companion object {
        /** Parses the wire form of [toString]; null when malformed. */
        fun parse(value: String): MonId? {
            val parts = value.removePrefix("mon:").split('.')
            if (parts.size != 2) return null
            val pid = parts[0].toLongOrNull(16) ?: return null
            val ot = parts[1].toLongOrNull(16) ?: return null
            return MonId(pid, ot)
        }
    }
}

/** A species, by its national dex number in the game's data (0 = none). */
@JvmInline
value class SpeciesId(val value: Int)

/** A move, by its id in the game's move table (0 = none). */
@JvmInline
value class MoveId(val value: Int)

/** An item, by its id in the game's item table (0 = none). */
@JvmInline
value class ItemId(val value: Int)

/** An ability, by its id in the game's ability table (0 = none). */
@JvmInline
value class AbilityId(val value: Int)

/** Something with an id and the name the game shows for it (names come from the game's own text). */
data class Named<T>(val id: T, val name: String)

internal fun Long.toHex8() = (this and 0xFFFFFFFFL).toString(16).padStart(8, '0')
