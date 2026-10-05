package dev.kotlinds.pokemonclient.games.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Battlers read mid-rewrite ([HgssBattlerCheck]) are never shown: the last valid reading of the same Pokémon is. */
class HgssStateMapperBattleTest {
    private val xatu = Battler(
        battlerId = 1, personality = 0x1234, side = "opponent", species = 178, speciesName = "XATU", level = 40,
        hp = 120, maxHp = 130, status = "OK", types = listOf("Psychic", "Flying"), moves = listOf(MoveInfo(94, "Psychic", 10, 10)),
    )
    private val hoOh = Battler(
        battlerId = 0, personality = 0x59e9db62, otId = 0x76f3a6fb, side = "player", species = 250, speciesName = "HO-OH",
        level = 46, hp = 164, maxHp = 164, status = "OK", types = listOf("Fire", "Flying"),
    )

    private fun state(vararg foes: Battler) = HgssState(
        frame = 0, mode = GameMode.BATTLE,
        battle = BattleInfo(isWild = false, battleTypeFlags = emptyList(), isDoubles = false, player = listOf(hoOh), opponents = foes.toList()),
    )

    @Test
    fun anImpossibleBattlerShowsItsLastValidReading() {
        val mapper = HgssStateMapper()
        assertTrue(mapper.map(state(xatu)).warnings.isEmpty())
        val torn = xatu.copy(species = 54116, level = 93)
        val after = mapper.map(state(torn))
        assertEquals(listOf("HO-OH", "XATU"), after.battle!!.battlers.map { it.species.name })
        assertEquals(40, after.battle!!.battlers[1].level)
        assertTrue(after.warnings.single().detail.startsWith("battler foe_left is being rewritten"), after.warnings.toString())
    }

    @Test
    fun anImpossibleNewBattlerIsLeftOut() {
        val mapper = HgssStateMapper()
        val after = mapper.map(state(xatu.copy(personality = 0x9999, hp = 500, maxHp = 130)))
        assertEquals(listOf("HO-OH"), after.battle!!.battlers.map { it.species.name })
        assertTrue("left out" in after.warnings.single().detail)
    }

    /** A level-up stats panel cell left in the message buffer ("26", "+ 3") is never a battle message. */
    @Test
    fun aLevelUpPanelNumberIsNotABattleMessage() {
        val mapper = HgssStateMapper()
        fun message(text: String) = mapper.map(state(xatu).let { it.copy(battle = it.battle!!.copy(message = text)) }).battle!!.message
        assertEquals(null, message("26"))
        assertEquals(null, message("+ 3"))
        assertEquals("CYNDAQUIL grew to\nLv. 26!", message("CYNDAQUIL grew to\nLv. 26!"))
    }

    @Test
    fun checksCoverSpeciesLevelHpAndMoves() {
        assertEquals(emptyList(), HgssBattlerCheck.problems(xatu))
        assertEquals(listOf("species 54116", "level 0"), HgssBattlerCheck.problems(xatu.copy(species = 54116, level = 0)))
        assertEquals(listOf("PP 12/10 of move 94"), HgssBattlerCheck.problems(xatu.copy(moves = listOf(MoveInfo(94, "Psychic", 12, 10)))))
    }
}
