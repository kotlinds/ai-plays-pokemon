package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.SpeciesId

/**
 * What a player has learned about the opponents of the current battle, the way a human would: an ability or a held
 * item is known once the game revealed it. The game's RAM knows them all along ([BattlerState.ability],
 * [BattlerState.heldItem]); this keeps only what was shown:
 * - an ability the game announced ([BattlerState.abilityRevealed]: Pressure, Intimidate, Flash Fire activated...);
 * - an ability or item named in a battle message ("MARILL's Thick Fat...", "HOUNDOOM's Leftovers restored..."). The
 *   names compared are the game's own ([GameData] names, in the ROM's language), never English words;
 * - an ability every member of the species has (the Pokédex says so: Gengar always has Levitate).
 *
 * Memory lasts until the battle ends (a revealed item stays known after it was eaten).
 */
class BattleKnowledge {

    /** One opponent, recognised across calls (species, level and nickname). */
    private data class FoeKey(val species: SpeciesId, val level: Int, val nickname: String?)

    private val abilities = mutableMapOf<FoeKey, Named<AbilityId>>()
    private val items = mutableMapOf<FoeKey, Named<ItemId>>()

    /** Held items seen at the previous observation: a berry is gone from RAM by the time its message is read. */
    private var previousItems = mapOf<FoeKey, Named<ItemId>>()

    /** Updates the memory from [state] and the battle [messages] shown since the previous call. */
    fun observe(state: GameState, messages: List<String>, data: GameData?) {
        val battle = state.battle
        if (battle == null) {
            abilities.clear()
            items.clear()
            previousItems = emptyMap()
            return
        }
        val text = messages.joinToString("\n")
        for (foe in battle.battlers.filter { !it.ref.isPlayerSide }) {
            val key = key(foe)
            val ability = foe.ability
            if (ability != null) {
                val name = data?.abilityName(ability.id) ?: ability.name
                if (foe.abilityRevealed || (name.length >= MIN_NAME && text.contains(name, ignoreCase = true))) abilities[key] = Named(ability.id, name)
            }
            for (item in listOfNotNull(foe.heldItem, previousItems[key]).distinct()) {
                val name = data?.item(item.id)?.name ?: item.name
                if (name.length >= MIN_NAME && text.contains(name, ignoreCase = true)) items[key] = Named(item.id, name)
            }
        }
        previousItems = battle.battlers.filter { !it.ref.isPlayerSide }.mapNotNull { foe -> foe.heldItem?.let { key(foe) to it } }.toMap()
    }

    /** The ability of [foe] when it was revealed, or when its species can only have that one. */
    fun ability(foe: BattlerState, data: GameData?): Named<AbilityId>? {
        if (foe.ref.isPlayerSide) return foe.ability
        abilities[key(foe)]?.let { return it }
        val only = data?.species(foe.species.id)?.abilities?.singleOrNull() ?: return null
        return Named(only, data.abilityName(only) ?: "ability ${only.value}")
    }

    /** The abilities [foe] may have (its species' ones), or the one known. */
    fun possibleAbilities(foe: BattlerState, data: GameData?): List<AbilityId> =
        ability(foe, data)?.let { listOf(it.id) } ?: data?.species(foe.species.id)?.abilities.orEmpty()

    /** The item [foe] was seen holding (it may have been used since). */
    fun heldItem(foe: BattlerState): Named<ItemId>? = if (foe.ref.isPlayerSide) foe.heldItem else items[key(foe)]

    /** One line per opponent with what is known about it (ability, held item), for agents. */
    fun describe(battle: BattleState, data: GameData?): List<String> = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }.map { foe ->
        val known = abilities[key(foe)]
        val ability = when {
            known != null -> "ability ${known.name} (revealed)"
            else -> {
                val possible = possibleAbilities(foe, data).map { data?.abilityName(it) ?: "ability ${it.value}" }
                when (possible.size) {
                    0 -> "ability unknown"
                    1 -> "ability ${possible.single()} (the only one of its species)"
                    else -> "ability ${possible.joinToString(" or ")} (not revealed yet)"
                }
            }
        }
        val item = items[key(foe)]?.let { "holds ${it.name} (seen)" } ?: "held item not seen"
        "${foe.ref.wire} ${foe.nickname ?: foe.species.name}: $ability, $item"
    }

    private fun key(foe: BattlerState) = FoeKey(foe.species.id, foe.level, foe.nickname)

    private companion object {
        /** Names shorter than this could match inside unrelated words. */
        const val MIN_NAME = 4
    }
}
