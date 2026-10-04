package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.BattleStyle
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameOptions
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The PC boxes, the OPTIONS and their screens on real HeartGold (USA) RAM captured at the Indigo Plateau (the real
 * run: 8 badges, 7 Pokémon in box 1).
 */
class HgssServicesFixtureTest {

    private val version = HgssVersion.HEARTGOLD_US

    @Test
    fun boxesAndOptionsAreReadFromTheSaveAnywhere() {
        val state = HgssGame(version).state(HgssFixtures.load("svc_plateau"))
        val storage = assertNotNull(state.storage)
        assertEquals(0, storage.currentBox)
        assertEquals(18, storage.boxes.size)
        val box1 = storage.boxes[0]
        assertEquals(7, box1.mons.size)
        val jelly = assertNotNull(storage.find(MonId.parse("mon:1577dd6c.76f3a6fb")!!))
        assertEquals("Jelly", jelly.nickname)
        assertEquals(6, jelly.slot)
        // Slot 2 is empty: HO-OH was withdrawn.
        assertEquals(listOf(0, 1, 3, 4, 5, 6, 7), box1.mons.map { it.slot })
        assertEquals(GameOptions(TextSpeed.FAST, battleScene = true, battleStyle = BattleStyle.SHIFT), state.options)
    }

    @Test
    fun optionsScreenShowsEachRowWithItsValueId() {
        val memory = HgssFixtures.load("svc_options")
        val screen = assertIs<Screen.ListMenu>(HgssOptionsScreen.decode(HgssMemory(memory, version), assertNotNull(HgssReader(memory, version).read())))
        assertEquals(
            listOf("setting:text_speed:fast", "setting:battle_scene:on", "setting:battle_style:shift", "setting:sound:stereo", "setting:button_mode:0", "setting:frame:0", "setting:exit:quit"),
            screen.entries.map { it.id },
        )
        assertEquals(Cursor.At(1), screen.cursor)
    }

    @Test
    fun pcMoveModeCarryingAPokemonOverTheBoxTabs() {
        val memory = HgssFixtures.load("svc_pc_hold_tab")
        val box = assertIs<Screen.PcBox>(HgssKeyboardPcShopScreens.decode(HgssMemory(memory, version), assertNotNull(HgssReader(memory, version).read())))
        assertEquals(PcMode.MOVE, box.mode)
        assertEquals(MonId.parse("mon:c50a0956.76f3a6fb"), box.holding)
        assertEquals(Cursor.At(38), box.cursor)
        assertEquals((0..5).map { "box:$it" }, box.entries.subList(37, 43).map { it.id })
        // 30-35 the party panel, 36 its EXIT; the tabs wrap on LEFT / RIGHT, UP from a box slot of the top row reaches them.
        assertEquals("option:close_party", box.entries[36].id)
        assertEquals(37, box.topology.next(0, dev.kotlinds.pokemonclient.console.Button.UP))
    }
}
