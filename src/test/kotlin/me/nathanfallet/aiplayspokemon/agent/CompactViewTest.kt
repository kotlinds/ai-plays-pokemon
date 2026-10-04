package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The compact `act` answer: the team only when it changed, no money / badges, the rest unchanged. */
class CompactViewTest {

    private fun view(team: String, position: String) = buildJsonObject {
        put("screen", "overworld")
        put("money", 22264)
        put("badges", 8)
        put("team", team)
        put("position", position)
    }

    @Test
    fun theFirstAnswerHasTheTeamAndLeavesOutMoneyAndBadges() {
        val taken = CompactView().take(view("HO-OH Lv46", "6,21"), compact = true)
        assertEquals(listOf("screen", "team", "position"), taken.entries.map { it.first })
        assertEquals(JsonPrimitive("HO-OH Lv46"), taken.entries.toMap()["team"])
        assertTrue(taken.moved)
    }

    @Test
    fun anUnchangedTeamAndPositionAreSaidUnchanged() {
        val compact = CompactView()
        compact.take(view("HO-OH Lv46", "6,21"), compact = true)
        val again = compact.take(view("HO-OH Lv46", "6,21"), compact = true)
        assertEquals(JsonPrimitive(CompactView.TEAM_UNCHANGED), again.entries.toMap()["team"])
        assertFalse(again.moved)
        // Then the team changes (a level up) and the player walks: both are sent again.
        val changed = compact.take(view("HO-OH Lv47", "6,20"), compact = true)
        assertEquals(JsonPrimitive("HO-OH Lv47"), changed.entries.toMap()["team"])
        assertTrue(changed.moved)
    }

    @Test
    fun aFullStateKeepsEverythingButStillRemembersWhatWasSent() {
        val compact = CompactView()
        val full = compact.take(view("HO-OH Lv46", "6,21"), compact = false)
        assertEquals(listOf("screen", "money", "badges", "team", "position"), full.entries.map { it.first })
        // The agent got the team with get_state: the next compact answer doesn't repeat it.
        assertEquals(JsonPrimitive(CompactView.TEAM_UNCHANGED), compact.take(view("HO-OH Lv46", "6,21"), compact = true).entries.toMap()["team"])
    }
}
