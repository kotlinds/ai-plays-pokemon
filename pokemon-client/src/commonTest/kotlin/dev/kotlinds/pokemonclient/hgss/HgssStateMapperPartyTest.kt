package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.MonId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mapper never shows nor remembers a rejected reading, and keeps the last valid one of the same Pokémon. */
class HgssStateMapperPartyTest {

    private val hoOh = PartyMon(slot = 0, personality = 0x59e9db62, otId = 0x76f3a6fb, species = 250, speciesName = "HO-OH", level = 46, hp = 164, maxHp = 164, status = "OK")
    private val kenya = PartyMon(slot = 1, personality = 0x6b5e, otId = 0x3e9, species = 22, speciesName = "FEAROW", nickname = "Kenya", level = 38, hp = 104, maxHp = 104, status = "OK")

    /** Kenya's party data caught mid-rewrite: values that look possible, rejected by [HgssMonCheck]. */
    private fun rewritten(slot: Int) = kenya.copy(slot = slot, level = 90, hp = 20295, maxHp = 31940, problems = listOf(HgssMonCheck.Problem.BadHp(20295, 31940)))

    private fun state(vararg party: PartyMon) = HgssState(frame = 0, mode = GameMode.OVERWORLD, party = party.toList())

    @Test
    fun aRejectedReadingShowsTheLastValidOneOfTheSamePokemonAtItsNewSlot() {
        val mapper = HgssStateMapper()
        mapper.map(state(hoOh, kenya))
        // reorder_party: Kenya is now the lead, and its slot is being rewritten.
        val after = mapper.map(state(rewritten(slot = 0), hoOh.copy(slot = 1)))
        assertEquals(listOf("Kenya" to 0, "HO-OH" to 1), after.party.map { it.displayName to it.slot })
        assertEquals(38, after.party[0].level)
        val warning = after.warnings.single().detail
        assertTrue("party slot 0 (position 1: Kenya, ${MonId(0x6b5e, 0x3e9)})" in warning, warning)
    }

    @Test
    fun aRejectedReadingIsNeverRemembered() {
        val mapper = HgssStateMapper()
        // First reading already rejected: nothing to show for it yet, and it must not become the "last good" one.
        val first = mapper.map(state(hoOh, rewritten(slot = 1)))
        assertEquals(listOf("HO-OH"), first.party.map { it.displayName })
        assertTrue(first.warnings.single().detail.startsWith("party slot 1 (position 2)"), first.warnings.toString())
        val second = mapper.map(state(hoOh, rewritten(slot = 1)))
        assertEquals(listOf("HO-OH"), second.party.map { it.displayName })
        val third = mapper.map(state(hoOh, kenya))
        assertEquals(38, third.party[1].level)
        assertEquals(emptyList(), third.warnings)
    }

    @Test
    fun aReadingFailingOnlyTheSpeciesChecksIsTrustedOnceStable() {
        val mapper = HgssStateMapper()
        val odd = kenya.copy(problems = listOf(HgssMonCheck.Problem.BadStat("atk", 99, 80..90)))
        repeat(29) { assertEquals(listOf("HO-OH"), mapper.map(state(hoOh, odd)).party.map { it.displayName }) }
        assertEquals(listOf("HO-OH", "Kenya"), mapper.map(state(hoOh, odd)).party.map { it.displayName })
    }
}
