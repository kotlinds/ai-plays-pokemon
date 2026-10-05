package dev.kotlinds.pokemonclient.games.hgss

import kotlin.test.Test
import kotlin.test.assertEquals

class HgssTextTest {

    /** Encodes ASCII with the Gen 4 charmap; '\n' newline, '\r' scroll, '\f' new page. */
    private fun encode(text: String): IntArray {
        val reverse = HgssData.charmap.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
        return text.map {
            when (it) {
                '\n' -> HgssText.NEWLINE
                '\r' -> HgssText.SCROLL
                '\u000C' -> HgssText.NEW_PAGE
                else -> reverse.getValue(it)
            }
        }.toIntArray() + HgssText.EOS
    }

    @Test
    fun visibleLinesFollowPagesAndScrolls() {
        val chars = encode("Hi!\nYou are awake.\u000CYour friend was here.\rShe played.\nWith MARILL.")
        assertEquals("Hi!\nYou are awake.", HgssText.visibleLines(chars, 18))
        // At the page break the old page is still shown, until the next page's first character prints.
        assertEquals("Hi!\nYou are awake.", HgssText.visibleLines(chars, 19))
        assertEquals("Y", HgssText.visibleLines(chars, 20))
        assertEquals("Your friend was here.\nShe played.", HgssText.visibleLines(chars, 52))
        assertEquals("She played.\nWith MARILL.", HgssText.visibleLines(chars, null))
    }

    @Test
    fun goalsFollowTheStory() {
        fun story(flags: Set<Int> = emptySet(), vararg vars: Pair<Int, Int>) = StoryInfo(flags, vars.toMap())
        assertEquals("Talk to Mom on the first floor of your house", HgssProgress.nextGoal(story(), null, 63))
        assertEquals(
            "Leave the house (exit mat at the bottom of the first floor)",
            HgssProgress.nextGoal(story(setOf(HgssProgress.FLAG_GOT_BAG), HgssProgress.VAR_PLAYERS_HOUSE_1F to 1), null, 63),
        )
        val afterEgg = story(
            setOf(HgssProgress.FLAG_GOT_STARTER, HgssProgress.FLAG_GOT_POKEGEAR, HgssProgress.FLAG_GOT_POKEDEX, HgssProgress.FLAG_RIVAL_CHERRYGROVE),
            HgssProgress.VAR_PLAYERS_HOUSE_1F to 2, HgssProgress.VAR_ELMS_LAB to 3, HgssProgress.VAR_NEW_BARK_TOWN to 2,
            HgssProgress.VAR_NEW_BARK_WEST_EXIT to 1, HgssProgress.VAR_CHERRYGROVE to 4, HgssProgress.VAR_MR_POKEMONS_HOUSE to 1,
            HgssProgress.VAR_ROUTE_30 to 3,
        )
        assertEquals("Return to Prof. Elm's lab in New Bark Town with the Mystery Egg", HgssProgress.nextGoal(afterEgg, null, 60))
        val badge = PlayerInfo("A", "male", 0, 0, 0, listOf("Zephyr"), 1)
        // With the Zephyr Badge, the steps before it count as done: the table goes on (Togepi Egg in Violet City).
        assertEquals(HgssStoryTable.step("johto:togepi_egg")!!.description, HgssProgress.nextGoal(afterEgg, badge, 60))
    }
}
