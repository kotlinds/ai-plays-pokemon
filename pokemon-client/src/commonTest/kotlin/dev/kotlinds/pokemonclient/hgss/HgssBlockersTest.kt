package dev.kotlinds.pokemonclient.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Blockers of the current map, on synthetic surroundings (the fixture tests check real saves). */
class HgssBlockersTest {

    private fun person(id: Int, x: Int, z: Int, mapId: Int, eventFlag: Int = 0, type: Int = 0) = MapObjectInfo(
        id = id, sprite = "X", x = x, z = z, dx = 0, dz = 0, height = 0, facing = "south", movement = 0, type = type,
        scriptId = 1, hidden = false, kind = "npc", mapId = mapId, eventFlag = eventFlag,
    )

    private fun state(mapId: Int, objects: List<MapObjectInfo> = emptyList(), triggers: List<TriggerInfo> = emptyList(), grid: LocalGrid? = null, story: StoryInfo = StoryInfo()) =
        HgssState(
            frame = 0, mode = GameMode.OVERWORLD, story = story,
            location = LocationInfo(mapId, "map", null, 0, 0, 0, "south", false, "WALKING"),
            surroundings = Surroundings(objects = objects, triggers = triggers, grid = grid),
        )

    @Test
    fun theRoute36SudowoodoBlocksUntilItIsBeaten() {
        val sudowoodo = person(4, 415, 246, mapId = 40, eventFlag = 0x1C2)
        val blockers = HgssBlockers.of(state(40, listOf(sudowoodo)))
        assertEquals(listOf("person:4"), blockers.map { it.target })
        assertTrue("SquirtBottle" in blockers.single().reason)
        // Once its flag is set it no longer blocks (even if the object is still drawn during the scene).
        val beaten = state(40, listOf(sudowoodo), story = StoryInfo(flags = setOf(HgssStoryTable.Flags.SUDOWOODO_GONE)))
        assertTrue(HgssBlockers.of(beaten).isEmpty())
    }

    @Test
    fun aCuratedPersonOfAnotherZoneIsMatchedByItsOwnZone() {
        // The Route 36 Sudowoodo seen from the neighbouring zone: matched with its own zone, not the player's.
        val blockers = HgssBlockers.of(state(39, listOf(person(4, 415, 246, mapId = 40, eventFlag = 0x1C2))))
        assertEquals(listOf("person:4"), blockers.map { it.target })
    }

    @Test
    fun theLeagueGateGuardStepsAsideWithOaksPermission() {
        val guard = person(1, 8, 9, mapId = 299)
        assertEquals(1, HgssBlockers.of(state(299, listOf(guard))).size)
        val allowed = StoryInfo(flags = setOf(HgssStoryTable.Flags.UNLOCKED_MT_SILVER))
        assertTrue(HgssBlockers.of(state(299, listOf(guard), story = allowed)).isEmpty())
    }

    @Test
    fun aPersonInAOneTilePassageThatWillLeaveIsAGenericBlocker() {
        // Corridor running north-south at x 11; the person stands in it.
        val grid = LocalGrid(10, 10, 3, 3, listOf("#.#", "#.#", "#.#"))
        val leaving = person(2, 11, 11, mapId = 500, eventFlag = 0x300)
        val stays = person(3, 11, 11, mapId = 500, eventFlag = 0)
        val trainer = person(5, 11, 11, mapId = 500, eventFlag = 0x301, type = 1)
        assertEquals(listOf("person:2"), HgssBlockers.of(state(500, listOf(leaving, stays, trainer), grid = grid)).map { it.target })
        // In the open, nobody blocks.
        val open = LocalGrid(10, 10, 3, 3, listOf("...", "...", "..."))
        assertTrue(HgssBlockers.of(state(500, listOf(leaving), grid = open)).isEmpty())
    }

    @Test
    fun armedTriggersAreReportedCuratedFirstAndPuzzlesAreNot() {
        fun trigger(index: Int, active: Boolean) = TriggerInfo(index = index, x = 700, z = 398, width = 1, height = 5, scriptId = 12, active = active, variable = 0x4081, value = 2)
        // New Bark east exit while Ho-Oh is not done: the curated reason.
        val newBark = HgssBlockers.of(state(60, triggers = listOf(trigger(2, false), trigger(3, true))))
        assertEquals(listOf("trigger:3"), newBark.map { it.target })
        assertTrue("Ho-Oh" in newBark.single().reason)
        // An unknown armed trigger: generic reason with its area.
        val generic = HgssBlockers.of(state(500, triggers = listOf(trigger(0, true))))
        assertEquals(listOf("trigger:0"), generic.map { it.target })
        // Fifteen armed pits (Ecruteak Gym): a mechanism, not story blockers.
        assertTrue(HgssBlockers.of(state(80, triggers = (0 until 15).map { trigger(it, true) })).isEmpty())
    }
}
