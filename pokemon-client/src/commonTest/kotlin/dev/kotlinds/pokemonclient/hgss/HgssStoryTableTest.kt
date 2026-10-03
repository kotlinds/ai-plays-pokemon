package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.StoryCondition
import dev.kotlinds.pokemonclient.state.StoryCondition.FlagSet
import dev.kotlinds.pokemonclient.state.StoryCondition.HasBadge
import dev.kotlinds.pokemonclient.state.StoryCondition.Or
import dev.kotlinds.pokemonclient.state.StoryCondition.VarAtLeast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The story table's logic on synthetic flags / vars / badges (no RAM). */
class HgssStoryTableTest {

    private fun goal(story: StoryInfo) = HgssStoryTable.goal(story)?.id

    /** The smallest progress that makes [condition] hold: the first alternative of an [Or], every part of an [StoryCondition.And]. */
    private fun StoryInfo.witness(condition: StoryCondition): StoryInfo = when (condition) {
        is FlagSet -> copy(flags = flags + condition.id)
        is VarAtLeast -> copy(vars = vars + (condition.id to maxOf(variable(condition.id), condition.value)))
        is HasBadge -> copy(badges = badges + condition.index)
        is Or -> witness(condition.any.first())
        is StoryCondition.And -> condition.all.fold(this) { s, c -> s.witness(c) }
    }

    @Test
    fun idsAreStableUniqueAndJohtoComesBeforeKanto() {
        val ids = HgssStoryTable.steps.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids")
        assertTrue(ids.all { it.startsWith("johto:") || it.startsWith("kanto:") })
        assertTrue(ids.indexOfLast { it.startsWith("johto:") } < ids.indexOfFirst { it.startsWith("kanto:") })
        assertEquals("johto:talk_to_mom", ids.first())
        assertEquals("kanto:red", ids.last())
        // Every Johto and Kanto badge has its step.
        val badgeSteps = HgssStoryTable.steps.filter { it.id.contains(":badge_") }
        assertEquals(16, badgeSteps.size)
    }

    @Test
    fun aNewGameStartsWithMom() {
        assertEquals("johto:talk_to_mom", goal(StoryInfo()))
        assertEquals(
            "Go downstairs (stairs in the top-left corner) and talk to Mom",
            HgssStoryTable.steps.first().describe(64),
        )
        assertEquals("Talk to Mom on the first floor of your house", HgssStoryTable.steps.first().describe(null))
    }

    @Test
    fun playingEveryStepInOrderWalksThroughTheWholeTable() {
        // Doing each step (its own condition only) moves the goal exactly one step further: no step is done by an
        // earlier one, and the order of the table is the order of the story.
        var story = StoryInfo()
        for (step in HgssStoryTable.steps) {
            assertEquals(step.id, goal(story))
            story = story.witness(step.done)
        }
        assertNull(goal(story), "the story is over after Red")
    }

    @Test
    fun aCheckpointCountsTheStepsBeforeItAsDone() {
        // Only the Fog Badge: everything before it (even without its flags) is behind the player.
        assertEquals("johto:surf", goal(StoryInfo(badges = setOf(HgssStoryTable.FOG))))
        // Only the Hall of Fame: Kanto starts.
        assertEquals("kanto:ss_ticket", goal(StoryInfo(flags = setOf(HgssStoryTable.Flags.GAME_CLEAR))))
        // A checkpoint does not jump ahead: with the Zephyr Badge only, the next step is right after it.
        assertEquals("johto:togepi_egg", goal(StoryInfo(badges = setOf(HgssStoryTable.ZEPHYR))))
    }

    @Test
    fun midgameBadgesInAnotherOrderKeepTheMissingStep() {
        // Glacier before Storm/Mineral (the midgame gyms can be done in any order): the Lighthouse is still to do.
        val story = StoryInfo(badges = (0..3).toSet() + HgssStoryTable.GLACIER, flags = setOf(HgssStoryTable.Flags.GOT_HM03))
        assertEquals("johto:lighthouse", goal(story))
    }

    @Test
    fun eightBadgesAndTheKimonoGirlsLeftPointToTheDanceTheater() {
        // The state of the agent's real run (see HgssStoryFixtureTest): 8 badges, Elm visited, Kimono Girl met.
        val story = StoryInfo(
            badges = (0..7).toSet(),
            vars = mapOf(HgssStoryTable.Vars.NEW_BARK_EAST_EXIT to 2, HgssStoryTable.Vars.DANCE_THEATER to 5),
        )
        assertEquals("johto:kimono_girls", goal(story))
        val afterBell = story.copy(flags = setOf(HgssStoryTable.Flags.BEAT_KIMONO_GIRLS))
        assertEquals("johto:bell_tower", goal(afterBell))
    }

    @Test
    fun kantoGymsInAnyOrderPointToTheFirstMissingOne() {
        val story = StoryInfo(
            badges = (0..7).toSet() + HgssStoryTable.MARSH,
            flags = setOf(HgssStoryTable.Flags.GAME_CLEAR, HgssStoryTable.Flags.ARRIVED_IN_VERMILION),
        )
        assertEquals("kanto:badge_thunder", goal(story))
    }

    @Test
    fun conditionsAreTypedAndListTheirIds() {
        val c = Or(FlagSet(1), StoryCondition.And(VarAtLeast(0x4100, 2), HasBadge(3)))
        assertEquals(setOf(1), c.flagIds())
        assertEquals(setOf(0x4100), c.varIds())
        assertTrue(c.holds(StoryInfo(flags = setOf(1))))
        assertFalse(c.holds(StoryInfo(vars = mapOf(0x4100 to 2))))
        assertTrue(c.holds(StoryInfo(vars = mapOf(0x4100 to 3), badges = setOf(3))))
    }

    @Test
    fun theReaderReadsEveryIdTheTableNeeds() {
        val flags = HgssStoryTable.steps.flatMap { it.done.flagIds() } + HgssBlockers.curated.flatMap { it.liftedWhen?.flagIds().orEmpty() }
        val vars = HgssStoryTable.steps.flatMap { it.done.varIds() } + HgssBlockers.curated.flatMap { it.liftedWhen?.varIds().orEmpty() }
        assertTrue(HgssStoryTable.flagIds.containsAll(flags))
        assertTrue(HgssStoryTable.varIds.containsAll(vars))
        assertTrue(vars.all { it in 0x4000 until 0x4000 + 0x170 }, "only save vars")
        assertTrue(flags.all { it in 1 until 0xB00 }, "only save flags")
    }

    @Test
    fun theProgressAdapterFollowsTheTable() {
        assertEquals(HgssStoryTable.steps.first().describe(63), HgssProgress.nextGoal(StoryInfo(), null, 63))
        assertNull(HgssProgress.nextGoal(null, null, 63))
    }
}
