package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.Matchups
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Effectiveness of the party's moves for a switch, and what the foe set up, on the ROM's data (skipped without a ROM). */
class SsPartyMatchupsRomTest {

    private fun move(id: Int, name: String) = KnownMove(Named(MoveId(id), name), 10, 10)

    private fun mon(personality: Long, species: Int, name: String, moves: List<KnownMove>, hp: Int = 100) = PartyMon(
        id = MonId(personality, 1), slot = 0, species = Named(SpeciesId(species), name), nickname = null, level = 50, hp = hp, maxHp = 100,
        status = null, types = emptyList(), heldItem = null, ability = null, moves = moves, stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    private fun foe(species: Int, types: List<String>, volatile: Set<VolatileStatus> = emptySet()) = BattlerState(
        BattlerRef.FOE_LEFT, null, Named(SpeciesId(species), "S$species"), null, 50, 100, 100, null, volatile, emptyMap(), types, emptyList(),
    )

    private val ampharos = mon(1, 181, "AMPHAROS", listOf(move(435, "Discharge"), move(86, "Thunder Wave")))
    private val fearow = mon(2, 22, "FEAROW", listOf(move(332, "Aerial Ace"), move(19, "Fly")))
    private val piloswine = mon(3, 221, "PILOSWINE", listOf(move(89, "Earthquake"), move(420, "Ice Shard")))
    private val fainted = mon(4, 157, "TYPHLOSION", listOf(move(53, "Flamethrower")), hp = 0)

    private fun battle(foe: BattlerState) = BattleState(
        BattleKind.TRAINER, false, BattlerRef.PLAYER_LEFT,
        listOf(BattlerState(BattlerRef.PLAYER_LEFT, ampharos.id, ampharos.species, null, 50, 100, 100, null, emptySet(), emptyMap(), listOf("Electric"), ampharos.moves), foe),
        listOf("Leader Lt. Surge"), emptyList(), null,
    )

    @Test
    fun theSwitchCandidatesShowWhatTheirMovesWouldDo() {
        val data = HgssWorldRom.requireData()
        // Lt. Surge's ELECTRODE (Electric): Fearow's Aerial Ace is resisted, Piloswine's Earthquake super effective.
        val lines = Matchups.party(battle(foe(101, listOf("Electric"))), listOf(ampharos, fearow, piloswine, fainted), data).map { it.line(isDouble = false) }
        assertEquals(listOf("FEAROW: Aerial Ace x0.5, Fly x0.5", "PILOSWINE: Earthquake x2, Ice Shard x1"), lines)
    }

    @Test
    fun aFoeUnderMagnetRiseIsImmuneToGroundAndDestinyBondIsSaid() {
        val data = HgssWorldRom.requireData()
        val floating = Matchups.party(battle(foe(101, listOf("Electric"), setOf(VolatileStatus.MagnetRise))), listOf(piloswine), data).single()
        assertEquals(0.0, floating.matchups.first { it.move == "Earthquake" }.multiplier)
        val bond = Matchups.party(battle(foe(94, listOf("Ghost", "Poison"), setOf(VolatileStatus.DestinyBond))), listOf(piloswine), data).single()
        assertTrue(bond.matchups.all { m -> m.notes.any { it.startsWith("Destiny Bond") } }, bond.line(false))
    }
}
