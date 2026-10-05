package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.VolatileStatus

/**
 * How hard one of the actor's moves would hit one battler: the type chart [multiplier], with what changes it ([notes]:
 * fixed damage, an ability, the ally hit by a spread move...).
 */
data class MoveMatchup(
    val move: String,
    val target: BattlerRef,
    val multiplier: Double,
    /** Why the multiplier isn't the plain type chart, or what to keep in mind ("fixed damage", "hits your ally"...). */
    val notes: List<String> = emptyList(),
) {
    /** "x2", "x0.5", "no effect", plus the notes: "x1 (fixed damage)". */
    val label: String
        get() {
            val base = when (multiplier) {
                0.0 -> "no effect"
                else -> "x" + if (multiplier % 1.0 == 0.0) multiplier.toInt().toString() else multiplier.toString()
            }
            return if (notes.isEmpty()) base else "$base (${notes.joinToString("; ")})"
        }
}

/** The moves of a Pokémon of the party that isn't battling, against the foes out now ([Matchups.party]). */
data class PartyMatchups(val mon: PartyMon, val matchups: List<MoveMatchup>) {
    /**
     * One compact line: "TYPHLOSION: Flamethrower x2, Swift x1", per foe in double battles ("→ foe_left: ...;
     * → foe_right: ...").
     */
    fun line(isDouble: Boolean): String {
        val byFoe = matchups.groupBy { it.target }
        val moves = byFoe.entries.joinToString("; ") { (target, list) ->
            (if (isDouble) "→ ${target.wire}: " else "") + list.joinToString(", ") { "${it.move} ${it.label}" }
        }
        return "${mon.displayName}: ${moves.ifEmpty { "no damaging move" }}"
    }
}

/**
 * Estimated effectiveness of the acting Pokémon's moves, the way a player with a Pokédex would see it (shown from
 * [KnowledgeLevel.POKEDEX] on):
 * - the type chart against each foe (status moves are skipped);
 * - fixed-damage moves (Seismic Toss, Night Shade, Dragon Rage, SonicBoom, Super Fang...) ignore it: only an immunity
 *   counts ([MoveInfo.fixedDamage]);
 * - the foe's ability when it is known ([BattleKnowledge]: revealed, or the only one its species can have) — Flash
 *   Fire, Levitate, Volt Absorb... make a type do nothing; when it is only possible (two abilities, not revealed), the
 *   move says "no effect if <ability>";
 * - in double battles, spread moves hitting everyone (Earthquake, Surf, Discharge) also show their effect on the ally.
 * Items and weather are left out.
 */
object Matchups {

    /**
     * The same estimate for each Pokémon of [party] that could be sent in (not battling, not fainted, not an Egg): what
     * its moves would do to the foes out now, to choose whom to switch to.
     */
    fun party(battle: BattleState, party: List<PartyMon>, data: GameData, knowledge: BattleKnowledge? = null): List<PartyMatchups> {
        val battling = battle.battlers.filter { it.ref.isPlayerSide }.mapNotNull { it.mon }.toSet()
        val foes = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }
        if (foes.isEmpty()) return emptyList()
        return party.filter { it.id !in battling && !it.fainted && !it.isEgg }.map { mon ->
            PartyMatchups(mon, mon.moves.flatMap { known -> movesAgainst(known, foes, null, data, knowledge) })
        }
    }

    private fun movesAgainst(known: KnownMove, foes: List<BattlerState>, ally: BattlerState?, data: GameData, knowledge: BattleKnowledge?): List<MoveMatchup> {
        val info = data.move(known.move.id) ?: return emptyList()
        if (info.category == MoveCategory.STATUS || info.power == 0) return emptyList()
        val targets = foes + listOfNotNull(ally?.takeIf { info.target == MoveTarget.ALL_OTHERS })
        return targets.mapNotNull { target -> matchup(known.move.name, info, target, data, knowledge, isAlly = target == ally) }
    }

    fun estimate(battle: BattleState, data: GameData, knowledge: BattleKnowledge? = null): List<MoveMatchup> {
        val actorRef = battle.actor ?: BattlerRef.PLAYER_LEFT
        val actor = battle.battlers.firstOrNull { it.ref == actorRef } ?: return emptyList()
        val foes = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }
        val ally = if (battle.isDouble) battle.battlers.firstOrNull { it.ref.isPlayerSide && it.ref != actorRef && it.hp > 0 } else null
        return actor.moves.flatMap { known -> movesAgainst(known, foes, ally, data, knowledge) }
    }

    private fun matchup(move: String, info: MoveInfo, target: BattlerState, data: GameData, knowledge: BattleKnowledge?, isAlly: Boolean): MoveMatchup? {
        val types = target.types.mapNotNull(::typeOf)
        if (types.isEmpty()) return null
        val notes = mutableListOf<String>()
        if (isAlly) notes += "hits your ally"
        // What the foe set up: knocking it out costs something.
        if (!isAlly && VolatileStatus.DestinyBond in target.volatile) notes += "Destiny Bond: knocking it out takes your Pokémon down too"
        if (!isAlly && VolatileStatus.Grudge in target.volatile) notes += "Grudge: the move that knocks it out loses all its PP"
        val chart = data.typeChart.multiplier(info.type, types)
        var multiplier = if (info.fixedDamage) {
            if (chart != 0.0) notes += "fixed damage: types don't matter"
            if (chart == 0.0) 0.0 else 1.0
        } else {
            chart
        }
        if (multiplier == 0.0) return MoveMatchup(move, target.ref, 0.0, notes)
        if (info.type == PokemonType.GROUND && VolatileStatus.MagnetRise in target.volatile) return MoveMatchup(move, target.ref, 0.0, notes + "Magnet Rise")
        // Abilities: the one known, else every one the species may have.
        val known = if (isAlly) target.ability?.id else knowledge?.ability(target, data)?.id ?: data.species(target.species.id)?.abilities?.singleOrNull()
        val possible = if (known != null) listOf(known) else data.species(target.species.id)?.abilities.orEmpty()
        for (ability in possible) {
            val effect = abilityEffect(ability, info, chart) ?: continue
            val name = data.abilityName(ability) ?: "ability ${ability.value}"
            if (known != null) {
                multiplier *= effect
                notes += name
            } else {
                notes += (if (effect == 0.0) "no effect" else "x$effect") + " if $name"
            }
        }
        return MoveMatchup(move, target.ref, multiplier, notes)
    }

    /**
     * The factor [ability] applies to a move of [info]'s type (Gen 4), or null when it changes nothing: immunities
     * (Flash Fire, Levitate, Volt Absorb, Water Absorb, Motor Drive, Dry Skin, Wonder Guard) and halvings (Thick Fat,
     * Heatproof).
     */
    internal fun abilityEffect(ability: AbilityId, info: MoveInfo, chart: Double): Double? = when (ability.value) {
        VOLT_ABSORB, MOTOR_DRIVE -> if (info.type == PokemonType.ELECTRIC) 0.0 else null
        WATER_ABSORB, DRY_SKIN -> if (info.type == PokemonType.WATER) 0.0 else null
        FLASH_FIRE -> if (info.type == PokemonType.FIRE) 0.0 else null
        LEVITATE -> if (info.type == PokemonType.GROUND) 0.0 else null
        WONDER_GUARD -> if (chart <= 1.0 && !info.fixedDamage) 0.0 else null
        THICK_FAT -> if (info.type == PokemonType.FIRE || info.type == PokemonType.ICE) 0.5 else null
        HEATPROOF -> if (info.type == PokemonType.FIRE) 0.5 else null
        else -> null
    }

    // Ability ids of Generation 4 (include/constants/abilities.h).
    private const val VOLT_ABSORB = 10
    private const val WATER_ABSORB = 11
    private const val FLASH_FIRE = 18
    private const val WONDER_GUARD = 25
    private const val LEVITATE = 26
    private const val THICK_FAT = 47
    private const val MOTOR_DRIVE = 78
    private const val HEATPROOF = 85
    private const val DRY_SKIN = 87

    private fun typeOf(label: String): PokemonType? =
        PokemonType.entries.firstOrNull { it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true) }
}
