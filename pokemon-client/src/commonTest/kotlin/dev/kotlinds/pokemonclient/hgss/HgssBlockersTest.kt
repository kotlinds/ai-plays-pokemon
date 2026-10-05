package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.BlockerCause
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
        // The Route 36 Sudowoodo seen from the neighbouring zone: matched with its own zone, not the player's; its id
        // is qualified with that zone, like the field object's (HgssObjectIds).
        val blockers = HgssBlockers.of(state(39, listOf(person(4, 415, 246, mapId = 40, eventFlag = 0x1C2))))
        assertEquals(listOf("person:4@40"), blockers.map { it.target })
    }

    @Test
    fun theLeagueGateGuardStepsAsideWithOaksPermission() {
        val guard = person(1, 8, 9, mapId = 299)
        assertEquals(1, HgssBlockers.of(state(299, listOf(guard))).size)
        val allowed = StoryInfo(flags = setOf(HgssStoryTable.Flags.UNLOCKED_MT_SILVER))
        assertTrue(HgssBlockers.of(state(299, listOf(guard), story = allowed)).isEmpty())
    }

    @Test
    fun theDragonsDenGuardBlocksUntilClairIsBeaten() {
        // Blackthorn (map 89), obj_T30_gsoldman1 at (672,133): its hide flag and FLAG_UNK_0D1 (BEAT_CLAIR) are set
        // together when Clair loses (scr_seq_0943_T30GYM0101.s). Checked live with the Rising Badge: no blocker.
        val guard = person(1, 672, 133, mapId = 89, eventFlag = 0x202)
        val blockers = HgssBlockers.of(state(89, listOf(guard)))
        assertEquals(listOf("person:1"), blockers.map { it.target })
        assertTrue("Clair" in blockers.single().reason)
        val beaten = StoryInfo(flags = setOf(HgssStoryTable.Flags.BEAT_CLAIR))
        assertTrue(HgssBlockers.of(state(89, listOf(guard), story = beaten)).isEmpty())
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

    @Test
    fun whitneyCriesUntilTheLassCameThenGivesTheBadge() {
        // After the battle VAR_UNK_410A = 1 arms the trigger at (13,11).
        val armed = listOf(TriggerInfo(index = 0, x = 13, z = 11, width = 1, height = 1, scriptId = 2, active = true, variable = 0x410A, value = 1))
        val crying = HgssBlockers.of(state(137, triggers = armed))
        assertEquals(listOf("trigger:0"), crying.map { it.target })
        assertTrue("Whitney" in crying.single().reason && "person:0" in crying.single().reason)
        // Once the Lass came (FLAG_UNK_0B7), or with the badge, nothing blocks any more (the trigger stays armed).
        assertTrue(HgssBlockers.of(state(137, triggers = armed, story = StoryInfo(flags = setOf(0xB7)))).isEmpty())
        assertTrue(HgssBlockers.of(state(137, triggers = armed, story = StoryInfo(badges = setOf(HgssStoryTable.PLAIN)))).isEmpty())
    }

    @Test
    fun chuckTrainsUnderTheWaterfallUntilTheWinchIsTurned() {
        val chuck = person(0, 13, 10, mapId = 139, eventFlag = 0x2EB)
        val waiting = HgssBlockers.of(state(139, listOf(chuck)))
        assertEquals(listOf("person:0"), waiting.map { it.target })
        assertTrue(waiting.single().reason.startsWith("Chuck under the waterfall: turn the winch"))
        val turned = StoryInfo(flags = setOf(HgssGymPuzzles.FLAG_WATERFALL_DISABLE))
        assertTrue(HgssBlockers.of(state(139, listOf(chuck), story = turned)).isEmpty())
        // The flag is read with the story's flags.
        assertTrue(HgssGymPuzzles.FLAG_WATERFALL_DISABLE in HgssBlockers.flagIds)
    }

    @Test
    fun theVioletGymLiftIsAMechanismNotAStoryScene() {
        val lift = TriggerInfo(index = 0, x = 15, z = 20, width = 1, height = 1, scriptId = 5, active = true, variable = 0x4000, value = 0)
        assertTrue(HgssBlockers.of(state(135, triggers = listOf(lift))).isEmpty())
    }

    @Test
    fun theRocketHqPasswordDoorsBlockWhileClosedAndSayWhoKnowsThePassword() {
        // B3F: the two door objects on (23,15)-(24,15) until the door is told the two passwords.
        val door = listOf(person(10, 23, 15, mapId = 249), person(11, 24, 15, mapId = 249))
        val closed = HgssBlockers.of(state(249, door))
        assertEquals(listOf("person:10", "person:11"), closed.map { it.target })
        assertEquals(BlockerCause.PasswordDoor(listOf("person:3", "person:4"), known = false), closed.first().cause)
        // Both grunts beaten (trainer flags 0x550 + 222 / 404): the passwords are known, talking opens the door.
        val heard = StoryInfo(flags = setOf(0x550 + 222, 0x550 + 404))
        assertEquals(BlockerCause.PasswordDoor(listOf("person:3", "person:4"), known = true), HgssBlockers.of(state(249, door, story = heard)).first().cause)
        assertTrue(0x550 + 222 in HgssBlockers.flagIds, "the trainer flags are read with the story flags")
        // Open: the objects slid west onto (22,15) (scr_seq_D35R0104_008), the doorway is free.
        assertTrue(HgssBlockers.of(state(249, listOf(person(10, 22, 15, mapId = 249), person(11, 22, 15, mapId = 249)))).isEmpty())
    }

    @Test
    fun theRocketHqVoiceDoorAndTheElectrodesAreTypedBlockers() {
        // B2F: the voice-recognition door on (30,22)-(31,22), opened by the Murkrow (FLAG_UNK_0D3).
        val door = listOf(person(5, 31, 22, mapId = 248), person(6, 30, 22, mapId = 248))
        val blockers = HgssBlockers.of(state(248, door))
        assertEquals(listOf("person:5", "person:6"), blockers.map { it.target })
        assertEquals(false, (blockers.first().cause as BlockerCause.PasswordDoor).known)
        val heard = StoryInfo(flags = setOf(0xD3))
        assertEquals(true, (HgssBlockers.of(state(248, door, story = heard)).first().cause as BlockerCause.PasswordDoor).known)
        // Open (live, completed hideout: both objects on 29,22): nothing blocks.
        assertTrue(HgssBlockers.of(state(248, listOf(person(5, 29, 22, mapId = 248), person(6, 29, 22, mapId = 248)))).isEmpty())
        // The transmitter's Electrode: battle each; gone once its FLAG_REMOVED_..._ELECTRODE_n is set.
        val electrode = person(10, 21, 14, mapId = 248)
        assertEquals(BlockerCause.WildPokemon(101), HgssBlockers.of(state(248, listOf(electrode))).single().cause)
        assertTrue(HgssBlockers.of(state(248, listOf(electrode), story = StoryInfo(flags = setOf(0xCC)))).isEmpty())
    }

    @Test
    fun theSsAquaSleepingSailorAndTheB1fGuardSayWhatWakesThem() {
        // S.S. Aqua (NOTES.md, Kanto): Sailor Stanly (1F south-east cabins, map 309, obj 0) sleeps until the B1F guard
        // (map 329, obj 1, trigger 0 at 38,17..19) was met; beating Stanly sets VAR_UNK_40CB to 3 and opens B1F.
        val searching = StoryInfo(vars = mapOf(HgssStoryTable.Vars.SS_AQUA to 2))
        val stanly = HgssBlockers.of(state(309, listOf(person(0, 7, 10, mapId = 309, eventFlag = 0x21A)), story = searching)).single()
        assertEquals("person:0", stanly.target)
        assertTrue("asleep" in stanly.reason && "B1F" in stanly.reason, stanly.reason)
        val guardTrigger = TriggerInfo(index = 0, x = 38, z = 17, width = 1, height = 3, scriptId = 2, active = true, variable = HgssStoryTable.Vars.SS_AQUA, value = 2)
        val b1f = HgssBlockers.of(state(329, listOf(person(1, 38, 18, mapId = 329, eventFlag = 0x22A)), listOf(guardTrigger), story = searching))
        assertEquals(listOf("person:1", "trigger:0"), b1f.map { it.target })
        assertTrue(b1f.all { "Stanly" in it.reason && "every time" in it.reason }, b1f.toString())
        // Stanly beaten: neither blocks any more.
        val beaten = StoryInfo(vars = mapOf(HgssStoryTable.Vars.SS_AQUA to 3))
        assertTrue(HgssBlockers.of(state(309, listOf(person(0, 7, 10, mapId = 309, eventFlag = 0x21A)), story = beaten)).isEmpty())
        assertTrue(HgssBlockers.of(state(329, listOf(person(1, 38, 18, mapId = 329, eventFlag = 0x22A)), listOf(guardTrigger), story = beaten)).isEmpty())
    }
}
