package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Lookup
import dev.kotlinds.pokemonclient.data.LookupKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `lookup` on the HeartGold ROM data (skipped without `POKEMON_ROM`). */
class LookupTest {

    private fun lookup(level: KnowledgeLevel = KnowledgeLevel.POKEDEX) = Lookup(HgssWorldRom.requireData(), level)

    @Test
    fun speciesByIdAndByNameGiveTheSameSheet() {
        val byId = lookup().lookup(LookupKind.SPECIES, "species:1").getOrThrow()
        val byName = lookup().lookup(LookupKind.SPECIES, "bulbasaur").getOrThrow()
        assertEquals(byId, byName)
        assertEquals(318, byId["base_stats"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("Grass", "Poison"), byId["types"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun movesMachinesAndTypes() {
        val thunderbolt = lookup().lookup(LookupKind.MOVE, "Thunderbolt").getOrThrow()
        assertEquals("95", thunderbolt["power"]!!.jsonPrimitive.content)
        val tm01 = lookup().lookup(LookupKind.MACHINE, "TM01").getOrThrow()
        assertEquals("Focus Punch", tm01["move"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val fire = lookup().lookup(LookupKind.TYPE, "fire").getOrThrow()
        assertTrue("Grass" in fire["super_effective"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theKnowledgeLevelLimitsWhatIsShown() {
        val none = lookup(KnowledgeLevel.NONE)
        assertTrue(none.lookup(LookupKind.SPECIES, "species:1").isFailure)
        assertEquals("300", none.lookup(LookupKind.ITEM, "Potion").getOrThrow()["price"]!!.jsonPrimitive.content)
        assertTrue(lookup().lookup(LookupKind.MOVE, "no such move").isFailure)
    }
}

/** Estimated effectiveness on a real battle fixture with the ROM's type chart (skipped without `POKEMON_ROM`). */
class MatchupsTest {
    @Test
    fun ampharosMovesAgainstTheWildPokemon() {
        val data = HgssWorldRom.requireData()
        val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("bt_cmd_hidden"))
        val battle = state.battle ?: error("not a battle fixture")
        val matchups = dev.kotlinds.pokemonclient.data.Matchups.estimate(battle, data)
        assertTrue(matchups.isNotEmpty())
        assertTrue(matchups.all { it.multiplier in setOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0) })
    }
}

/** Place names and map types read from the ROM equal the decomp's tables, for every map (skipped without `POKEMON_ROM`). */
class HgssMapNamesTest {
    @Test
    fun everyMapHasTheSamePlaceNameAndTypeAsTheDecomp() {
        val world = HgssWorldRom.require()
        val data = HgssWorldRom.requireData()
        val expectedLocations = HgssData.lines("map_locations.txt")
        val expectedTypes = HgssData.lines("map_types.txt")
        HgssData.useGameData(data)
        HgssData.useWorld(world)
        try {
            for (zone in expectedLocations.indices) {
                kotlin.test.assertEquals(expectedLocations[zone].ifEmpty { null }, HgssData.mapLocation(zone), "location of zone $zone")
                kotlin.test.assertEquals(expectedTypes[zone].ifEmpty { null }, HgssData.mapType(zone), "type of zone $zone")
            }
        } finally {
            HgssData.useGameData(null)
            HgssData.useWorld(null)
        }
    }
}
