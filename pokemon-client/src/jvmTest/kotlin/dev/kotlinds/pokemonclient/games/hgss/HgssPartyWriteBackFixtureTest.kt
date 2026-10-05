package dev.kotlinds.pokemonclient.games.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The end of a won battle (Elite Four Will, real frames): `sf_writeback_f2436` is the last battle frame (the party read
 * from the battle's copy, the save still holding the party from before the battle), `sf_writeback_f2438` the first
 * overworld frame where the save's older party shows before the game writes the copy back (HO-OH 164/164, PILOSWINE
 * Lv39, AMPHAROS Lv36), `sf_writeback_f2440` the party written back.
 */
class HgssPartyWriteBackFixtureTest {

    private fun read(name: String) = HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).read()!!

    private fun summary(party: List<dev.kotlinds.pokemonclient.state.PartyMon>) = party.map { "${it.species.name} L${it.level} ${it.hp}/${it.maxHp}" }

    private val afterBattle = listOf(
        "HO-OH L46 91/164", "FEAROW L38 0/104", "TYPHLOSION L42 130/130", "GYARADOS L36 123/123", "PILOSWINE L40 136/136", "AMPHAROS L37 0/120",
    )

    @Test
    fun theStaleFrameReallyShowsThePartyFromBeforeTheBattle() {
        val battle = read("sf_writeback_f2436")
        assertNotNull(battle.partyBeforeWriteBack)
        val stale = read("sf_writeback_f2438")
        assertNull(stale.partyBeforeWriteBack)
        assertEquals(battle.partyBeforeWriteBack, stale.party)
        assertEquals("HO-OH L46 164/164", stale.party[0].let { "${it.speciesName} L${it.level} ${it.hp}/${it.maxHp}" })
        // Without the battle frame before it, nothing tells it's stale: shown as read.
        assertEquals(164, HgssStateMapper().map(stale).party[0].hp)
    }

    @Test
    fun theBattlesCopyIsShownUntilTheGameWritesItBack() {
        val mapper = HgssStateMapper()
        for (name in listOf("sf_writeback_f2436", "sf_writeback_f2438", "sf_writeback_f2440")) {
            assertEquals(afterBattle, summary(mapper.map(read(name)).party), name)
        }
    }

    @Test
    fun longAfterTheBattleTheSavesPartyIsShownAsRead() {
        val mapper = HgssStateMapper()
        mapper.map(read("sf_writeback_f2436"))
        val stale = read("sf_writeback_f2438")
        val later = stale.copy(frame = stale.frame + HgssPartyWriteBack.WRITE_BACK_FRAMES + 1)
        assertEquals(164, mapper.map(later).party[0].hp)
    }
}
