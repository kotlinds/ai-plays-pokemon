package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.BattleKnowledge
import dev.kotlinds.pokemonclient.data.Matchups
import dev.kotlinds.pokemonclient.data.MoveTarget
import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Move data (fixed damage, targets) and estimated effectiveness with abilities and doubles, on the ROM's data
 * (skipped without `POKEMON_ROM`).
 */
class BattleMatchupsRomTest {

    private fun move(id: Int, name: String) = KnownMove(Named(MoveId(id), name), 10, 10)

    private fun battler(ref: BattlerRef, species: Int, types: List<String>, moves: List<KnownMove> = emptyList(), ability: Int? = null, revealed: Boolean = false, item: Int? = null) =
        BattlerState(
            ref, null, Named(SpeciesId(species), "S$species"), null, 40, 100, 100, null, emptySet(), emptyMap(), types, moves,
            ability = ability?.let { Named(AbilityId(it), "A$it") }, heldItem = item?.let { Named(ItemId(it), "I$it") }, abilityRevealed = revealed,
        )

    private fun battle(vararg battlers: BattlerState, double: Boolean = false) =
        BattleState(BattleKind.TRAINER, double, BattlerRef.PLAYER_LEFT, battlers.toList(), listOf("X"), emptyList(), null)

    @Test
    fun fixedDamageAndTargetsComeFromTheMoveTable() {
        val data = HgssWorldRom.requireData()
        for (id in listOf(69, 101, 82, 49, 162, 283)) assertTrue(data.move(MoveId(id))!!.fixedDamage, "move $id is fixed damage")
        assertFalse(data.move(MoveId(85))!!.fixedDamage)
        assertEquals(MoveTarget.ALL_OTHERS, data.move(MoveId(435))!!.target) // Discharge
        assertEquals(MoveTarget.ALL_OTHERS, data.move(MoveId(89))!!.target) // Earthquake
        assertEquals(MoveTarget.ALL_FOES, data.move(MoveId(196))!!.target) // Icy Wind
        assertEquals(MoveTarget.SELECTED, data.move(MoveId(33))!!.target) // Tackle
    }

    @Test
    fun seismicTossIgnoresTypesButNotImmunities() {
        val data = HgssWorldRom.requireData()
        val toss = move(69, "Seismic Toss")
        val vsTentacool = Matchups.estimate(battle(battler(BattlerRef.PLAYER_LEFT, 66, listOf("Fighting"), listOf(toss)), battler(BattlerRef.FOE_LEFT, 72, listOf("Water", "Poison"))), data).single()
        assertEquals(1.0, vsTentacool.multiplier)
        val vsGastly = Matchups.estimate(battle(battler(BattlerRef.PLAYER_LEFT, 66, listOf("Fighting"), listOf(toss)), battler(BattlerRef.FOE_LEFT, 92, listOf("Ghost", "Poison"))), data).single()
        assertEquals(0.0, vsGastly.multiplier)
    }

    @Test
    fun flashFireIsAPossibilityUntilRevealedThenNoEffect() {
        val data = HgssWorldRom.requireData()
        val flamethrower = move(53, "Flamethrower")
        val me = battler(BattlerRef.PLAYER_LEFT, 157, listOf("Fire"), listOf(flamethrower))
        // Houndoom (229): Early Bird or Flash Fire.
        val hidden = Matchups.estimate(battle(me, battler(BattlerRef.FOE_LEFT, 229, listOf("Dark", "Fire"), ability = 18)), data, BattleKnowledge()).single()
        assertEquals(0.5, hidden.multiplier)
        assertTrue(hidden.label.contains("no effect if Flash Fire"), hidden.label)
        val foe = battler(BattlerRef.FOE_LEFT, 229, listOf("Dark", "Fire"), ability = 18, revealed = true)
        val knowledge = BattleKnowledge().apply { observe(GameState(0, Screen.Battle(Awaiting.ANIMATION), null, emptyList(), null, battle(me, foe), null), emptyList(), data) }
        assertEquals(0.0, Matchups.estimate(battle(me, foe), data, knowledge).single().multiplier)
        assertTrue(knowledge.describe(battle(me, foe), data).single().contains("Flash Fire (revealed)"))
    }

    @Test
    fun anAbilityOrItemNamedInAMessageIsRemembered() {
        val data = HgssWorldRom.requireData()
        val me = battler(BattlerRef.PLAYER_LEFT, 157, listOf("Fire"), listOf(move(53, "Flamethrower")))
        val foe = battler(BattlerRef.FOE_LEFT, 229, listOf("Dark", "Fire"), ability = 18, item = 234) // Leftovers
        val knowledge = BattleKnowledge()
        val state = GameState(0, Screen.Battle(Awaiting.ANIMATION), null, emptyList(), null, battle(me, foe), null)
        knowledge.observe(state, listOf("The foe's HOUNDOOM restored a little HP using its ${data.item(ItemId(234))!!.name}!"), data)
        assertEquals(234, knowledge.heldItem(foe)?.id?.value)
        assertEquals(null, knowledge.ability(foe, data))
        knowledge.observe(state, listOf("The foe's HOUNDOOM's ${data.abilityName(AbilityId(18))} raised its Fire power!"), data)
        assertEquals(18, knowledge.ability(foe, data)?.id?.value)
    }

    @Test
    fun levitateIsKnownWhenTheSpeciesAlwaysHasIt() {
        val data = HgssWorldRom.requireData()
        val earthquake = move(89, "Earthquake")
        // Gengar (94): Levitate only.
        val vsGengar = Matchups.estimate(battle(battler(BattlerRef.PLAYER_LEFT, 130, listOf("Water"), listOf(earthquake)), battler(BattlerRef.FOE_LEFT, 94, listOf("Ghost", "Poison"))), data).single()
        assertEquals(0.0, vsGengar.multiplier)
    }

    @Test
    fun spreadMovesShowTheirEffectOnTheAllyInDoubles() {
        val data = HgssWorldRom.requireData()
        val discharge = move(435, "Discharge")
        val matchups = Matchups.estimate(
            battle(
                battler(BattlerRef.PLAYER_LEFT, 181, listOf("Electric"), listOf(discharge)),
                battler(BattlerRef.PLAYER_RIGHT, 22, listOf("Normal", "Flying")),
                battler(BattlerRef.FOE_LEFT, 179, listOf("Electric")),
                battler(BattlerRef.FOE_RIGHT, 183, listOf("Water")),
                double = true,
            ),
            data,
        )
        val ally = matchups.single { it.target == BattlerRef.PLAYER_RIGHT }
        assertEquals(2.0, ally.multiplier)
        assertTrue(ally.label.contains("hits your ally"))
        assertEquals(3, matchups.size)
    }

    @Test
    fun catchChanceOnARealWildBattle() {
        val data = HgssWorldRom.requireData()
        val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("bt_cmd_hidden"))
        val balls = state.bag.orEmpty().first { it.name == "balls" }.items
        val estimate = dev.kotlinds.pokemonclient.data.CatchChance.estimate(state.battle!!, balls, data)!!
        assertEquals(data.species(estimate.foe.species.id)!!.catchRate, estimate.catchRate)
        val byBall = estimate.balls.associate { it.ball.item.id.value to it.chance }
        assertEquals(1.0, byBall.getValue(1)) // Master Ball
        assertTrue(byBall.getValue(2) >= byBall.getValue(3), "Ultra Ball >= Great Ball")
        assertTrue(estimate.balls.all { it.label.endsWith("%") })
    }
}
