package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The story goal and blockers on real HeartGold saves: the agent's run with 8 Johto badges, which lost to the
 * Kimono Girls (bench states `ecruteak*.state`, `pond.state`; `story_bell_tower_gate` walked from `pond.state` to the
 * Bell Tower gate with `go_to warp:2`).
 */
class HgssStoryFixtureTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun theReaderReadsTheBadgesAndTheStoryFlags() {
        val story = assertNotNull(HgssReader(HgssFixtures.load("story_ecruteak")).read()?.story)
        assertEquals((0..7).toSet(), story.badges)
        // Done on this save: Sudowoodo, Burned Tower, Radio Tower, Dragon's Den, Elm visited after the 8th badge.
        assertTrue(story.flag(HgssStoryTable.Flags.SUDOWOODO_GONE))
        assertTrue(story.flag(HgssStoryTable.Flags.BEAT_RADIO_TOWER_ROCKETS))
        assertTrue(story.flag(HgssStoryTable.Flags.PASSED_DRAGONS_DEN))
        assertEquals(2, story.variable(HgssStoryTable.Vars.NEW_BARK_EAST_EXIT))
        assertEquals(5, story.variable(HgssStoryTable.Vars.DANCE_THEATER))
        // Not done: the Kimono Girls (Clear Bell), Ho-Oh, the League.
        assertTrue(!story.flag(HgssStoryTable.Flags.BEAT_KIMONO_GIRLS))
        assertTrue(!story.flag(HgssStoryTable.Flags.HO_OH_DONE))
        assertTrue(!story.flag(HgssStoryTable.Flags.GAME_CLEAR))
    }

    @Test
    fun eightBadgesPointToTheKimonoGirls() {
        for (name in listOf("story_ecruteak", "story_ecruteak_city", "story_pond", "story_ecruteak_gym", "story_bike")) {
            val story = assertNotNull(state(name).story, name)
            assertEquals("johto:kimono_girls", story.goal?.id, name)
            assertTrue("Kimono Girls" in story.goal!!.description, name)
        }
    }

    @Test
    fun nothingBlocksInEcruteakAndTheGymPitsAreNotStoryBlockers() {
        for (name in listOf("story_ecruteak", "story_ecruteak_city", "story_pond", "story_ecruteak_gym")) {
            assertEquals(emptyList(), state(name).story?.blockers, name)
        }
    }

    @Test
    fun theBellTowerGateSageIsABlockerUntilHoOh() {
        val state = state("story_bell_tower_gate")
        assertEquals(83, state.field?.mapId)
        val blockers = assertNotNull(state.story).blockers
        assertEquals(listOf("person:0"), blockers.map { it.target })
        assertTrue("Fog Badge" in blockers.single().reason)
        // The sage is a live person of the map.
        assertTrue(state.field!!.objects.any { it.id == "person:0" })
    }
}
