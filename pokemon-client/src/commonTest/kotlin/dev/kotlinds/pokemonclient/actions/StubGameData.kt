package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.data.GameData
import dev.kotlinds.pokemonclient.data.ItemInfo
import dev.kotlinds.pokemonclient.data.LevelMove
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.data.MoveInfo
import dev.kotlinds.pokemonclient.data.PokemonType
import dev.kotlinds.pokemonclient.data.SpeciesInfo
import dev.kotlinds.pokemonclient.data.TextBankId
import dev.kotlinds.pokemonclient.data.TypeChart
import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId

/** Game data for tests: only the given [items] and [species], nothing else. */
class StubGameData(
    private val items: Map<ItemId, ItemInfo> = emptyMap(),
    private val speciesInfo: Map<SpeciesId, SpeciesInfo> = emptyMap(),
) : GameData {
    override val speciesCount = speciesInfo.size
    override val moveCount = 0
    override val itemCount = items.size
    override fun species(id: SpeciesId) = speciesInfo[id]
    override fun learnset(id: SpeciesId) = emptyList<LevelMove>()
    override fun move(id: MoveId): MoveInfo? = null
    override fun item(id: ItemId) = items[id]
    override fun abilityName(id: AbilityId): String? = null
    override fun typeName(type: PokemonType) = type.label
    override fun trainerClassName(id: Int): String? = null
    override val typeChart = TypeChart(emptyMap(), emptySet())
    override fun machineMove(machine: MachineId): MoveId? = null
    override fun machineOf(item: ItemId): MachineId? = null
    override fun text(bank: TextBankId, line: Int): String? = null
}
