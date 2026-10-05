package dev.kotlinds.pokemonclient.games.hgss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.MenuState
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PointOfInterest
import dev.kotlinds.pokemonclient.Tile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the agent gets out of the HGSS reader on real snapshots (sparse fixtures, see [HgssFixtures]), taken live
 * with melonDS along the early game:
 *  - d9: bedroom 2F, first controllable frame
 *  - mom1: 1F, Mom's first message fully printed (waiting for A)
 *  - nb1: outside the house in New Bark Town, after Mom's talk
 *  - lab1: Elm's lab after his speech, before choosing a starter
 *  - starterapp1: the starter selection app, Cyndaquil in front
 *  - yesno1: "Give a nickname to the CYNDAQUIL you received?" (touch-screen yes/no)
 *  - starter1 / startmenu1: in the lab right after getting the starter, then with the start menu open
 *  - battle1: first wild battle (Route 29), command menu with the cursor on FIGHT
 *  - multi1 / mailbox1: Cherrygrove Pokémon Center PC (player's PC menu, then the Mailbox app)
 *  - fol1: talking to the following Cyndaquil, message still printing
 */
class HgssGameTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)
    private fun observe(name: String): Observation = game.observe(HgssFixtures.load(name))
    private fun Observation.text(key: String) = state[key]!!.jsonPrimitive.content

    @Test
    fun `bedroom map is cropped to the room with exits objects and facing`() {
        val o = observe("d9")
        assertEquals(GameMode.OVERWORLD, o.mode)
        assertTrue(o.awaitingInput)
        val location = assertNotNull(o.location)
        assertEquals(64, location.mapId)
        assertEquals(6 to 6, location.x to location.y)
        assertEquals(Direction.SOUTH, location.facing)

        val map = assertNotNull(o.map)
        assertEquals(Tile.WALKABLE, map.tileAt(6, 6))
        assertEquals(Tile.BLOCKED, map.tileAt(11, 6)) // east wall
        assertEquals(Tile.UNKNOWN, map.tileAt(13, 6)) // beyond the wall: void, not floor
        assertEquals(Tile.UNKNOWN, map.tileAt(6, 14))
        assertTrue(map.width <= 13 && map.height <= 10, "cropped to the room: ${map.width}x${map.height}")
        assertEquals(Tile.WARP, map.tileAt(3, 4))
        val stairs = map.pointsOfInterest.single { it.kind == PointOfInterest.Kind.EXIT }
        assertEquals("stairs to New Bark Player House 1F", stairs.label)
        assertEquals(Direction.WEST, stairs.exitDirection)
        assertTrue(map.pointsOfInterest.any { it.kind == PointOfInterest.Kind.OBJECT && it.label == "PC" && it.x == 6 && it.y == 4 })

        assertEquals(mapOf("mode" to "overworld", "screen" to "map", "position" to "6,6", "facing" to "south"),
            o.facts.filterKeys { it in setOf("mode", "screen", "position", "facing") })
        assertEquals("Go downstairs (stairs in the top-left corner) and talk to Mom", o.storyGoal)
        assertTrue(o.progress.isEmpty())

        val rows = o.state["map"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("y\\x  0  1  2  3  4  5  6  7  8  9 10 11", rows.first())
        assertTrue(rows.any { it.startsWith("  6 ") && " v " in it }, "player drawn as an arrow facing south")
        assertTrue(rows.none { '@' in it })
        assertContains(o.text("legend"), "v you (facing south)")
        assertEquals("north: floor · south: floor · west: floor · east: floor", o.text("adjacent"))
        assertContains(o.state["exits"]!!.jsonArray.single().jsonPrimitive.content, "stairs at (3,4)")
        assertNull(o.state["storyGoal"])
    }

    @Test
    fun `dialogue shows the current page and waits for A`() {
        val o = observe("mom1")
        assertEquals(GameMode.DIALOGUE, o.mode)
        assertTrue(o.awaitingInput)
        assertEquals("Hi, AAAAAAA! / You’re finally awake.", o.facts["dialogue"])
        assertEquals("waiting_button", o.facts["waiting_for"])
        assertEquals("fully shown, press A to continue", o.text("message_status"))
        assertContains(assertNotNull(o.dialogue), "Professor Elm, was looking for you")
        assertTrue(o.map!!.pointsOfInterest.any { it.kind == PointOfInterest.Kind.PERSON && it.label == "Mom" && it.x == 3 && it.y == 4 })
        val mat = o.map!!.pointsOfInterest.first { it.label == "exit mat to New Bark" }
        assertEquals(Direction.SOUTH, mat.exitDirection)
        assertEquals("Talk to Mom on the first floor of your house", o.storyGoal)
    }

    @Test
    fun `follower message still printing is not awaiting input`() {
        val o = observe("fol1")
        assertEquals(GameMode.DIALOGUE, o.mode)
        assertFalse(o.awaitingInput)
        assertEquals("printing", o.facts["waiting_for"])
        assertEquals("CYNDAQUIL doesn’t seem used", o.facts["dialogue"])
    }

    @Test
    fun `outdoors lists doors people and neighbouring areas`() {
        val o = observe("nb1")
        val map = assertNotNull(o.map)
        val door = map.pointsOfInterest.single { it.label == "door to New Bark Player House 1F" }
        assertEquals(695 to 396, door.x to door.y)
        assertNull(door.exitDirection)
        assertTrue(map.pointsOfInterest.any { it.kind == PointOfInterest.Kind.PERSON && it.label == "red-haired boy" })
        assertEquals("north: door to New Bark Player House 1F · south: floor · west: floor · east: floor", o.text("adjacent"))
        assertContains(o.state["nearby_areas"]!!.jsonArray.map { it.jsonPrimitive.content }, "Route 29 to the west (x ≤ 671)")
        assertEquals(listOf("Talked to Mom (got the Bag)"), o.progress)
        assertContains(assertNotNull(o.storyGoal), "Elm's lab")
    }

    @Test
    fun `lab objects are named and the goal is to pick a starter`() {
        val o = observe("lab1")
        val pois = o.map!!.pointsOfInterest
        assertTrue(pois.any { it.label == "Prof. Elm" && it.x == 4 && it.y == 5 })
        assertTrue(pois.any { it.label == "starter Pokémon machine (Poké Balls)" && it.x == 8 && it.y == 4 })
        assertTrue(pois.any { it.label == "bookshelf" })
        assertEquals(listOf("Talked to Mom (got the Bag)", "Met Prof. Elm"), o.progress)
        assertContains(assertNotNull(o.storyGoal), "Choose a starter")
    }

    @Test
    fun `starter selection app is a menu`() {
        val o = observe("starterapp1")
        assertEquals(GameMode.MENU, o.mode)
        assertTrue(o.awaitingInput)
        val menu = assertNotNull(o.menu)
        assertEquals(listOf("CHIKORITA (Grass)", "TOTODILE (Water)", "CYNDAQUIL (Fire)"), menu.options)
        assertEquals(2, menu.cursor)
        assertEquals(MenuState.Layout.HORIZONTAL, menu.layout)
        assertEquals("2: CYNDAQUIL (Fire)", o.facts["menu.cursor"])
    }

    @Test
    fun `touch screen yes no`() {
        val o = observe("yesno1")
        assertTrue(o.awaitingInput)
        assertEquals(MenuState("yes/no", listOf("YES", "NO"), 0, MenuState.Layout.VERTICAL), o.menu)
        assertEquals("yes_no", o.facts["waiting_for"])
        assertEquals("Give a nickname to the CYNDAQUIL / you received?", o.facts["dialogue"])
        assertEquals("CYNDAQUIL 20/20", o.facts["party.hp"])
    }

    @Test
    fun `progress after getting the starter`() {
        val o = observe("starter1")
        assertEquals(
            listOf("Talked to Mom (got the Bag)", "Met Prof. Elm", "Has starter Pokémon (CYNDAQUIL)", "Party: 1 Pokémon"),
            o.progress,
        )
        assertEquals("Walk to the lab's exit (Elm's aide has something for you)", o.storyGoal)
    }

    @Test
    fun `start menu grid`() {
        val o = observe("startmenu1")
        assertEquals(GameMode.MENU, o.mode)
        assertTrue(o.awaitingInput)
        val menu = assertNotNull(o.menu)
        assertEquals(listOf("-", "TRAINER CARD", "POKéMON", "SAVE", "BAG", "OPTIONS"), menu.options)
        assertEquals(2, menu.cursor)
        assertEquals(MenuState.Layout.TWO_COLUMNS, menu.layout)
        assertEquals("start menu", o.facts["screen"])
    }

    @Test
    fun `battle command menu`() {
        val o = observe("battle1")
        assertEquals(GameMode.BATTLE, o.mode)
        assertTrue(o.awaitingInput)
        val menu = assertNotNull(o.menu)
        assertEquals("battle: main", menu.kind)
        assertEquals(listOf("FIGHT", "BAG", "RUN", "POKéMON"), menu.options)
        assertEquals(0, menu.cursor)
        assertEquals("CYNDAQUIL 20/20 vs HOOTHOOT 14/14", o.facts["battle.hp"])
        assertNull(o.map)
        val battle = o.state["battle"]!!.jsonObject
        assertEquals("wild Pokémon", battle["kind"]!!.jsonPrimitive.content)
        assertEquals("HOOTHOOT Lv2, HP 14/14, Normal/Flying", battle["opponent"]!!.jsonPrimitive.content)
        assertEquals("What will CYNDAQUIL do?", battle["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pc multichoice and mailbox`() {
        val pc = observe("multi1")
        assertEquals(MenuState("multichoice", listOf("MAILBOX", "BALL CAPSULES", "LOG OUT"), 0), pc.menu)
        assertTrue(pc.awaitingInput)
        assertEquals(listOf("Has Running Shoes"), pc.progress.filter { "Shoes" in it })

        val mailbox = observe("mailbox1")
        assertEquals("mailbox", mailbox.facts["screen"])
        val menu = assertNotNull(mailbox.menu)
        assertEquals("mail from Lyra", menu.options.first())
        assertEquals("CANCEL", menu.options.last())
        assertEquals(12, menu.options.size)
        assertEquals(0, menu.cursor)
    }

    @Test
    fun `intro screens map to the intro mode`() {
        assertEquals(GameMode.INTRO, observe("d1").mode)
        assertEquals(GameMode.INTRO, observe("d5").mode)
    }

    @Test
    fun `overworld description stays small`() {
        for (name in listOf("d9", "nb1", "lab1")) {
            val size = observe(name).state.toString().length
            assertTrue(size < 3500, "$name: $size chars")
        }
        assertTrue(observe("nb1").state["map"] is JsonArray)
    }
}
