package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.BaseStats
import dev.kotlinds.pokemonclient.data.Effectiveness
import dev.kotlinds.pokemonclient.data.Evolution
import dev.kotlinds.pokemonclient.data.EvolutionMethod
import dev.kotlinds.pokemonclient.data.GrowthRate
import dev.kotlinds.pokemonclient.data.ItemPocket
import dev.kotlinds.pokemonclient.data.LevelMove
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.data.MoveCategory
import dev.kotlinds.pokemonclient.data.PokemonType
import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HgssGameData] on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`, see [HgssWorldRom]): known values
 * (Bulbapedia / the decomp), then every record against the bundled tables generated from the decomp.
 */
class HgssGameDataTest {

    private val data get() = HgssWorldRom.requireData()

    // ---------------------------------------------------------------------------------------------------------
    // Known values
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `bulbasaur base data`() {
        val bulbasaur = assertNotNull(data.species(SpeciesId(1)))
        assertEquals("BULBASAUR", bulbasaur.name) // HG/SS (USA) species names are upper case
        assertEquals(listOf(PokemonType.GRASS, PokemonType.POISON), bulbasaur.types)
        assertEquals(BaseStats(hp = 45, attack = 49, defense = 49, speed = 45, spAttack = 65, spDefense = 65), bulbasaur.baseStats)
        assertEquals(listOf(AbilityId(65)), bulbasaur.abilities) // Overgrow
        assertEquals("Overgrow", data.abilityName(AbilityId(65)))
        assertEquals(45, bulbasaur.catchRate)
        assertEquals(GrowthRate.MEDIUM_SLOW, bulbasaur.growthRate)
        assertEquals(listOf(Evolution(EvolutionMethod.LEVEL, 16, SpeciesId(2))), bulbasaur.evolutions)
        assertTrue(MachineId(6) in bulbasaur.machines) // TM06 Toxic
        assertTrue(MachineId(93) in bulbasaur.machines) // HM01 Cut
        assertTrue(MachineId(35) !in bulbasaur.machines) // TM35 Flamethrower
        assertEquals(LevelMove(1, MoveId(33)), data.learnset(SpeciesId(1)).first()) // Tackle
        assertTrue(LevelMove(9, MoveId(22)) in data.learnset(SpeciesId(1))) // Vine Whip
    }

    @Test
    fun `single type species and branched evolutions`() {
        assertEquals(listOf(PokemonType.FIRE), data.species(SpeciesId(155))?.types) // Cyndaquil
        val eevee = assertNotNull(data.species(SpeciesId(133)))
        assertEquals(setOf(134, 135, 136, 196, 197, 470, 471), eevee.evolutions.map { it.target.value }.toSet())
        assertTrue(Evolution(EvolutionMethod.STONE, 84, SpeciesId(134)) in eevee.evolutions) // Water Stone → Vaporeon
    }

    @Test
    fun `thunderbolt move data`() {
        val thunderbolt = assertNotNull(data.move(MoveId(85)))
        assertEquals("Thunderbolt", thunderbolt.name)
        assertEquals(PokemonType.ELECTRIC, thunderbolt.type)
        assertEquals(MoveCategory.SPECIAL, thunderbolt.category)
        assertEquals(95, thunderbolt.power)
        assertEquals(100, thunderbolt.accuracy)
        assertEquals(15, thunderbolt.pp)
        assertEquals(10, thunderbolt.effectChance)
        assertEquals(1, data.move(MoveId(98))?.priority) // Quick Attack
        assertEquals(MoveCategory.PHYSICAL, data.move(MoveId(33))?.category) // Tackle
        assertEquals(MoveCategory.STATUS, data.move(MoveId(45))?.category) // Growl
    }

    @Test
    fun `items prices and pockets`() {
        val potion = assertNotNull(data.item(ItemId(17)))
        assertEquals("Potion", potion.name)
        assertEquals(300, potion.price)
        assertEquals(ItemPocket.MEDICINE, potion.pocket)
        assertEquals(ItemPocket.BALLS, data.item(ItemId(4))?.pocket) // Poké Ball
        assertEquals(200, data.item(ItemId(4))?.price)
        assertEquals(ItemPocket.TMS_HMS, data.item(ItemId(328))?.pocket) // TM01
        assertEquals(ItemPocket.KEY_ITEMS, data.item(ItemId(445))?.pocket) // Bicycle
        assertEquals(ItemPocket.BERRIES, data.item(ItemId(149))?.pocket) // Cheri Berry
    }

    @Test
    fun `machines`() {
        assertEquals(MoveId(264), data.machineMove(MachineId(1))) // TM01 Focus Punch
        assertEquals("Focus Punch", data.move(data.machineMove(MachineId(1))!!)?.name)
        assertEquals(MoveId(15), data.machineMove(MachineId(93))) // HM01 Cut
        assertEquals(MoveId(431), data.machineMove(MachineId(100))) // HM08 Rock Climb
        assertEquals(MachineId(1), data.machineOf(ItemId(328)))
        assertEquals(MachineId(100), data.machineOf(ItemId(427)))
        assertNull(data.machineOf(ItemId(17)))
        assertEquals("HM08", MachineId(100).label)
        assertEquals("TM24", MachineId(24).label)
    }

    @Test
    fun `type chart`() {
        val chart = data.typeChart
        assertEquals(Effectiveness.SUPER_EFFECTIVE, chart.effectiveness(PokemonType.FIRE, PokemonType.GRASS))
        assertEquals(Effectiveness.NOT_VERY_EFFECTIVE, chart.effectiveness(PokemonType.GRASS, PokemonType.FIRE))
        assertEquals(Effectiveness.NO_EFFECT, chart.effectiveness(PokemonType.ELECTRIC, PokemonType.GROUND))
        assertEquals(Effectiveness.NO_EFFECT, chart.effectiveness(PokemonType.NORMAL, PokemonType.GHOST))
        assertEquals(Effectiveness.NORMAL, chart.effectiveness(PokemonType.NORMAL, PokemonType.NORMAL))
        assertEquals(4.0, chart.multiplier(PokemonType.ICE, listOf(PokemonType.DRAGON, PokemonType.FLYING)))
        assertEquals(0.25, chart.multiplier(PokemonType.FIRE, listOf(PokemonType.FIRE, PokemonType.WATER)))
        assertEquals(
            setOf(PokemonType.NORMAL to PokemonType.GHOST, PokemonType.FIGHTING to PokemonType.GHOST),
            chart.ignoredByForesight,
        )
        // 110 entries in sTypeEffectiveness besides its two markers, all distinct pairs.
        assertEquals(110, chart.matchups.size)
    }

    @Test
    fun `text banks`() {
        assertEquals("Fire", data.typeName(PokemonType.FIRE).lowercase().replaceFirstChar { it.uppercase() })
        assertEquals("Youngster", data.trainerClassName(data.trainerClassNames.indexOf("Youngster")))
        assertEquals(data.speciesNames[25], data.text(HgssTextBanks.SPECIES_NAMES, 25))
        assertNull(data.text(HgssTextBanks.SPECIES_NAMES, 100_000))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Against the bundled tables (decomp)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `names match the bundled tables`() {
        assertPrefixEquals(HgssData.lines("species.txt"), data.speciesNames, "species")
        assertPrefixEquals(HgssData.lines("moves.txt"), data.moveNames, "moves")
        assertPrefixEquals(HgssData.lines("items.txt"), data.itemNames, "items")
        assertPrefixEquals(HgssData.lines("abilities.txt"), data.abilityNames, "abilities")
        assertPrefixEquals(HgssData.lines("trainer_classes.txt"), data.trainerClassNames, "trainer classes")
    }

    @Test
    fun `move data matches the bundled table`() {
        val bundled = HgssData.lines("move_data.tsv").map { it.split('\t') }
        assertEquals(bundled.size, data.moveCount)
        for (p in bundled) {
            val id = p[0].toInt()
            val m = assertNotNull(data.move(MoveId(id)), "move $id")
            assertEquals(
                listOf(p[2], p[3], p[4], p[5], p[6], p[7]),
                listOf(m.type.label, m.category.label, "${m.power}", "${m.accuracy}", "${m.pp}", "${m.priority}"),
                "move $id ${p[1]}",
            )
        }
    }

    @Test
    fun `species types and TM compatibility match the bundled tables`() {
        val types = HgssData.lines("species_types.txt")
        val compat = HgssData.lines("tm_compat.txt")
        assertEquals(types.size, data.speciesCount)
        for (id in types.indices) {
            val s = assertNotNull(data.species(SpeciesId(id)), "species $id")
            assertEquals(types[id], s.types.joinToString("/") { it.label }, "types of species $id")
            val machines = compat.getOrNull(id)?.split(' ')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
            assertEquals(machines, s.machines.map { it.number }.toSet(), "machines of species $id")
        }
    }

    @Test
    fun `machine moves and prices match the bundled tables`() {
        val tmMoves = HgssData.lines("tm_moves.txt").map { it.trim().toInt() }
        assertEquals(tmMoves, MachineId.all.map { data.machineMove(it)?.value })
        for (id in 0 until data.itemCount) {
            assertEquals(HgssItemPrices.bundledPrice(id), data.item(ItemId(id))?.price?.takeIf { it != 0 }, "price of item $id")
        }
    }

    @Test
    fun `HgssData delegates to the ROM data when installed`() {
        try {
            HgssData.useGameData(data)
            assertEquals(data.speciesNames[152], HgssData.speciesName(152))
            assertEquals(listOf("Grass", "Poison"), HgssData.speciesTypes(1))
            assertEquals(95, HgssData.moveData[85]?.power)
            assertEquals(264, HgssMachines.moveOf(328))
            assertTrue(HgssMachines.isHm(57))
            assertEquals(TmCompatibility.ABLE, HgssMachines.compatibility(181, false, listOf(435), 328))
            assertEquals(300, HgssItemPrices.price(17))
            assertNull(HgssItemPrices.price(445)) // Bicycle: key item, no price
        } finally {
            HgssData.useGameData(null)
        }
    }

    /**
     * The ROM bank has at least the bundled lines, equal one by one (it may have extra trailing lines). Compared
     * trimmed: the bundled tables were trimmed when generated (ability 0 is " -" in the ROM).
     */
    private fun assertPrefixEquals(bundled: List<String>, rom: List<String>, what: String) {
        assertTrue(rom.size >= bundled.size, "$what: ${rom.size} ROM lines < ${bundled.size} bundled")
        val diffs = bundled.indices.filter { bundled[it].trim() != rom[it].trim() }.map { "$it: '${bundled[it]}' != '${rom[it]}'" }
        assertTrue(diffs.isEmpty(), "$what differ (${diffs.size}):\n" + diffs.take(20).joinToString("\n"))
    }
}
