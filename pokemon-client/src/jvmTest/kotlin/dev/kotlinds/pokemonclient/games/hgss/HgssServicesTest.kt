package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.GrowthRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trainers, marts and map flags read from the ROM (skipped without `POKEMON_ROM`). */
class HgssServicesRomTest {

    private fun rom(): HgssWorldSource {
        val world = HgssWorldRom.require()
        HgssData.useWorld(world)
        HgssData.useGameData(HgssWorldRom.requireData())
        return world
    }

    @Test
    fun trainerNamesAreUnpacked() {
        rom()
        assertEquals("Psychic" to "Eli", HgssTrainers.names(412))
        assertEquals("Kimono Girl" to "Zuki", HgssTrainers.names(162))
        assertEquals("Elite Four" to "Will", HgssTrainers.names(245))
    }

    @Test
    fun onlyTheCounterNurseHeals() {
        rom()
        // Cherrygrove's Center: the counter nurse (person:0, CallStd std_nurse_joy) and the one blocking the stairs
        // (person:7, std_wifi_club_closed) wear the same sprite; `heal` must talk to the first one.
        val objects = kotlin.test.assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("cherrygrove_center")).field).objects
        assertEquals(listOf("person:0"), objects.filter { it.role == dev.kotlinds.pokemonclient.state.PersonRole.NURSE }.map { it.id })
    }

    @Test
    fun routeTrainersComeFromTheCommonTrainerScripts() {
        // obj_R27_mystery_2: std_trainer(TRAINER_PSYCHIC_M_ELI) = 3000 + 412 - 1.
        assertEquals(412, HgssTrainers.commonScriptTrainer(3411))
        assertEquals(412, HgssTrainers.commonScriptTrainer(5411))
        assertNull(HgssTrainers.commonScriptTrainer(7000))
    }

    @Test
    fun eliteFourAndKimonoGirlsComeFromTheirScripts() {
        rom()
        // Will's own script battles WILL (245) and his rematch (702): the first one is kept.
        assertEquals(245, HgssTrainers.trainerOf(MAP_WILL_ROOM, 0, 2))
        // The Dance Theater: one scene script moves each Kimono Girl forward then battles her (scr_seq_T27R0501_016).
        assertEquals(162, HgssTrainers.trainerOf(MAP_DANCE_THEATER, 10, 8)) // dancer_6: Zuki
        assertEquals(160, HgssTrainers.trainerOf(MAP_DANCE_THEATER, 4, 6)) // dancer: Naoko
        assertEquals(164, HgssTrainers.trainerOf(MAP_DANCE_THEATER, 8, 10)) // dancer_5: Miki
        assertEquals(161, HgssTrainers.trainerOf(MAP_DANCE_THEATER, 5, 7)) // dancer_2: Sayo
        assertEquals(163, HgssTrainers.trainerOf(MAP_DANCE_THEATER, 7, 9)) // dancer_4: Kuni
        // The old woman of the theater isn't a trainer.
        assertNull(HgssTrainers.trainerOf(MAP_DANCE_THEATER, 0, 15))
    }

    @Test
    fun theEliteFourAreBeatenThroughTheFlagTheirScriptSets() {
        rom()
        // scr_seq_T10R0501_001: TrainerBattle KAREN / KAREN_2, CheckBattleWon, then SetFlag FLAG_DEFEATED_KAREN (0xE7).
        assertEquals(0xE7, HgssTrainers.wonFlag(MAP_KAREN_ROOM, 246))
        assertEquals(0xE4, HgssTrainers.wonFlag(MAP_WILL_ROOM, 245))
        // Route trainers have no scripted win flag: their trainer flag says it.
        assertNull(HgssTrainers.wonFlag(MAP_DANCE_THEATER, 412))
    }

    @Test
    fun leadersEliteFourAndElderLiAreBeatenThroughWhatTheirScriptRecords() {
        rom()
        val badge = { b: Int -> HgssTrainers.WonCondition.Badge(b) }
        val flag = { f: Int -> HgssTrainers.WonCondition.Flag(f) }
        // (map, trainer) -> what the win records. Gym leaders: the badge given right after CheckBattleWon
        // (scr_seq_T22GYM0101_001 for Falkner). Elder Li (scr_seq_D15R0103_002): a message and TM70 come before
        // SetFlag FLAG_UNK_076, the flag his script checks before the battle. Whitney and Clair don't give the badge
        // after the battle (she cries, Clair sends you to the Dragon's Den): the var / flag their script checks first.
        val expected = mapOf(
            (156 to 290) to flag(0x76), // Elder Li, Sprout Tower 3F
            (135 to 20) to badge(0), // Falkner
            (180 to 21) to badge(1), // Bugsy
            (137 to 30) to HgssTrainers.WonCondition.VarAtLeast(0x410A, 1), // Whitney (cries: the badge comes later)
            (80 to 31) to badge(3), // Morty
            (139 to 34) to badge(4), // Chuck
            (138 to 33) to badge(5), // Jasmine
            (140 to 32) to badge(6), // Pryce
            (141 to 35) to flag(0xD1), // Clair
            (473 to 253) to badge(8), // Brock
            (427 to 254) to badge(9), // Misty
            (365 to 255) to badge(10), // Lt. Surge
            (395 to 256) to badge(11), // Erika
            (480 to 257) to badge(12), // Janine
            (410 to 258) to badge(13), // Sabrina
            (457 to 259) to badge(14), // Blaine
            (496 to 261) to badge(15), // Blue
            (MAP_WILL_ROOM to 245) to flag(0xE4),
            (302 to 247) to flag(0xE5), // Koga
            (303 to 418) to flag(0xE6), // Bruno
            (MAP_KAREN_ROOM to 246) to flag(0xE7),
        )
        val actual = expected.keys.associateWith { (map, trainer) -> HgssTrainers.wonCondition(map, trainer) }
        assertEquals(expected, actual)
    }

    @Test
    fun clerkCatalogsComeFromTheirScripts() {
        rom()
        // The Indigo Plateau clerk (scr_seq_T10R0101_004): special mart 13.
        assertEquals(listOf(2, 77, 25, 24, 23, 28, 27), HgssMarts.catalog(MAP_LEAGUE_ENTRANCE, 5, badges = 8))
        assertEquals(listOf(4, 17, 18, 22), HgssMarts.normal(0))
    }

    @Test
    fun flyIsAllowedOutdoorsOnly() {
        val world = rom()
        assertFalse(world.header(MAP_LEAGUE_ENTRANCE)!!.flyAllowed)
        assertTrue(world.header(MAP_ROUTE_27)!!.flyAllowed)
    }

    private companion object {
        const val MAP_ROUTE_27 = 31
        const val MAP_DANCE_THEATER = 86
        const val MAP_LEAGUE_ENTRANCE = 300
        const val MAP_WILL_ROOM = 301
        const val MAP_KAREN_ROOM = 304
    }
}

/** The experience curves give box Pokémon their level. */
class HgssBoxLevelsTest {
    @Test
    fun levelsFollowTheGrowthCurves() {
        assertEquals(1, HgssBoxReader.levelFor(GrowthRate.MEDIUM_FAST, 0))
        assertEquals(50, HgssBoxReader.levelFor(GrowthRate.MEDIUM_FAST, 125_000))
        assertEquals(49, HgssBoxReader.levelFor(GrowthRate.MEDIUM_FAST, 124_999))
        assertEquals(100, HgssBoxReader.levelFor(GrowthRate.SLOW, 1_250_000))
        assertEquals(100, HgssBoxReader.levelFor(GrowthRate.ERRATIC, 600_000))
        assertEquals(100, HgssBoxReader.levelFor(GrowthRate.FLUCTUATING, 1_640_000))
        assertEquals(560, HgssBoxReader.expFor(GrowthRate.MEDIUM_SLOW, 10))
    }
}

/** People labels from their sprite (the fallback when the script doesn't make them a known trainer). */
class HgssPeopleLabelsTest {
    @Test
    fun spritesAreNamedWhatAPlayerSees() {
        // Psychic Eli (Route 27) uses MYSTERY_2: a psychic, not a delivery man (DELIVERY is the courier).
        assertEquals("psychic", HgssLabels.person("MYSTERY_2"))
        assertEquals("delivery man", HgssLabels.person("DELIVERY"))
        assertEquals("Kimono Girl", HgssLabels.person("DANCER"))
        assertEquals("Will (Elite Four)", HgssLabels.person("GSBIGFOUR1"))
        assertEquals("little boy", HgssLabels.person("BABYBOY1"))
        // Placeholder sprites: a League door (BABYBOY1_11) is a door, other placeholders scenery objects.
        assertEquals("door", HgssLabels.person("BABYBOY1_11"))
        assertEquals("object", HgssLabels.person("BABYBOY1_5"))
        assertEquals("shutter", HgssLabels.person("GATE_LEFT"))
        assertEquals("Apricorn tree", HgssLabels.person("BONGURI"))
    }

}
