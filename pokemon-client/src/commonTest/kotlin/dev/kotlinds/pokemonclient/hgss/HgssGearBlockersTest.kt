package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Route 11 Snorlax blocker (Poké Flute, then talk again) and the credits / S.S. Aqua cut scenes. */
class HgssGearBlockersTest {

    private fun person(id: Int, x: Int, z: Int, mapId: Int) = MapObjectInfo(
        id = id, sprite = "X", x = x, z = z, dx = 0, dz = 0, height = 0, facing = "south", movement = 0, type = 0,
        scriptId = 1, hidden = false, kind = "npc", mapId = mapId, eventFlag = 0,
    )

    private fun route11(story: StoryInfo = StoryInfo()) = HgssState(
        frame = 0, mode = GameMode.OVERWORLD, story = story,
        location = LocationInfo(ROUTE_11, "map", null, 0, 0, 0, "south", false, "WALKING"),
        surroundings = Surroundings(objects = listOf(person(4, 10, 10, ROUTE_11))),
    )

    @Test
    fun theSnorlaxSaysToTuneThePokeFluteThenTalkToItAgain() {
        val blocker = HgssBlockers.of(route11()).single()
        assertEquals("person:4", blocker.target)
        assertTrue("tune_radio station:poke_flute" in blocker.reason)
        assertTrue("talk to the Snorlax again" in blocker.reason)
        assertEquals(BlockerCause.SleepingPokemon(143, RadioStation.POKE_FLUTE), blocker.cause)
        assertTrue(HgssBlockers.of(route11(StoryInfo(flags = setOf(HgssStoryTable.Flags.SNORLAX_BEATEN)))).isEmpty())
    }

    private val noRam = HgssMemory(object : Memory {
        override fun read8(addr: Long) = 0
        override fun read16(addr: Long) = 0
        override fun read32(addr: Long) = 0L
        override fun readBytes(addr: Long, size: Int) = ByteArray(size)
    }, HgssVersion.HEARTGOLD_US)

    @Test
    fun theCreditsAreACutSceneThatASoftResetSkips() {
        val credits = HgssCutsceneScreens.decode(noRam, HgssState(frame = 0, mode = GameMode.APP, modeDetail = "credits"))
        assertEquals(AnimationKind.CUTSCENE, (credits as Screen.Animation).kind)
        assertTrue("soft_reset" in credits.hint!!)
    }

    @Test
    fun theShipCrossingIsACutScene() {
        val ship = HgssCutsceneScreens.decode(noRam, HgssState(frame = 0, mode = GameMode.APP, modeDetail = "ship_crossing"))
        assertEquals(Screen.Animation(AnimationKind.CUTSCENE, HgssCutsceneScreens.SHIP_HINT), ship)
    }

    private companion object {
        const val ROUTE_11 = 19
    }
}
