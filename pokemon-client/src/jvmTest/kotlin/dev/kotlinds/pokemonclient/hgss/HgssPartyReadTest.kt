package dev.kotlinds.pokemonclient.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Party slots caught while the game rewrites them (real frames of the run, Indigo Plateau): `GetMonData` decrypts
 * and re-encrypts a Pokémon without touching its flags, so a frame can show its blocks or its party data plain
 * while the flags say "encrypted". These readings used to be shown as "HO-OH species 47812" or "Kenya Lv90
 * HP 20295/31940 badly poisoned".
 */
class HgssPartyReadTest {

    private fun party(name: String) = HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).read()!!.party

    /** The reading the flags announce, without trying the other states: what the old decoder showed. */
    private fun naive(name: String, slot: Int) =
        HgssPokemon.decode(HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).partyRaw()[slot]) { true }!!

    /** True when the blocks, decrypted as the flags say, match the checksum (false: the old decoder showed garbage). */
    private fun flagsReadingMatches(name: String, slot: Int): Boolean {
        val raw = HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).partyRaw()[slot]
        val checksum = HgssPokemon.u16(raw, 6)
        val blocks = raw.copyOfRange(8, 0x88).also { if (HgssPokemon.u16(raw, 4) and 2 == 0) HgssPokemon.crypt(it, 0, it.size, checksum.toLong()) }
        return (0 until 0x40).sumOf { HgssPokemon.u16(blocks, 2 * it) } and 0xFFFF == checksum
    }

    private fun assertRealParty(name: String) {
        val party = party(name)
        assertEquals(6, party.size, name)
        party.forEach { assertTrue(it.plausible, "$name slot ${it.slot}: ${it.problems}") }
        val lead = party[0]
        assertEquals(listOf("HO-OH", "46", "164/164"), listOf(lead.speciesName, "${lead.level}", "${lead.hp}/${lead.maxHp}"), name)
        val kenya = party[1]
        assertEquals(listOf("FEAROW", "Kenya", "38", "104/104", "OK"), listOf(kenya.speciesName, kenya.nickname, "${kenya.level}", "${kenya.hp}/${kenya.maxHp}", kenya.status), name)
    }

    @Test
    fun leadWithPlainBlocksAndEncryptedFlags() {
        assertFalse(flagsReadingMatches("party_lead_plain_box", 0))
        assertRealParty("party_lead_plain_box")
    }

    @Test
    fun partyDataPlainWhileTheBlocksAreEncrypted() {
        for (name in listOf("party_kenya_plain_data", "party_kenya_outdoors")) {
            assertFalse(HgssMonCheck.isPlausible(naive(name, 1)), name)
            assertRealParty(name)
        }
    }

    @Test
    fun withTheRomDataTheStatsAndLevelAreChecked() {
        val data = HgssWorldRom.requireData()
        HgssData.useGameData(data)
        try {
            for (name in listOf("party_lead_plain_box", "party_kenya_plain_data", "party_kenya_outdoors")) {
                assertRealParty(name)
                party(name).forEach { assertEquals(emptyList(), it.problems, "$name slot ${it.slot}") }
            }
            // With the growth rates, the state tells the experience still needed (Kenya: 59319 - 56039).
            val kenya = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("party_kenya_plain_data")).party[1]
            assertEquals(3280L, kenya.expToNextLevel)
            // The naive Kenya reading: species and box fine, party data garbage (level 90, HP 20295/31940).
            val problems = HgssMonCheck.problems(naive("party_kenya_plain_data", 1))
            assertTrue(problems.any { it is HgssMonCheck.Problem.BadLevel || it is HgssMonCheck.Problem.BadHp }, "$problems")
        } finally {
            HgssData.useGameData(null)
        }
    }
}
