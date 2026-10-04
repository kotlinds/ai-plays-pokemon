package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Dialogue speakers named from the ROM's trainers (skipped without `POKEMON_ROM`). */
class HgssSpeakerRomTest {
    @Test
    fun aTrainerBattledByItsMapScriptIsNamedAsTheSpeaker() {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        // Kimono Girl Sayo in the Dance Theater: no common trainer script, her battle is in the map's own scripts.
        val dialogue = assertIs<Screen.Dialogue>(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("bt_kimono_speaker")).screen)
        assertEquals("Kimono Girl Sayo", dialogue.speaker)
    }
}
