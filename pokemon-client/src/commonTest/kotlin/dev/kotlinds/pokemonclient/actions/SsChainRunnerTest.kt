package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Chains of actions in battle and their limits ([ChainRunner]), with a scripted game. */
class SsChainRunnerTest {

    private val piloswine = MonId(0x033ef671, 0x76f3a6fb)

    private fun battler(ref: BattlerRef, species: Int, name: String, hp: Int, personality: Long? = null, mon: MonId? = null) = BattlerState(
        ref = ref, mon = mon, species = Named(SpeciesId(species), name), nickname = null, level = 50, hp = hp, maxHp = 150,
        status = null, volatile = emptySet(), statStages = statMap(), types = emptyList(), moves = emptyList(), personality = personality,
    )

    private fun statMap() = emptyMap<dev.kotlinds.pokemonclient.state.BattleStat, Int>()

    private fun state(vararg battlers: BattlerState) = GameState(
        0, Screen.Battle(Awaiting.INPUT), null, emptyList(), null,
        BattleState(BattleKind.TRAINER, false, BattlerRef.PLAYER_LEFT, battlers.toList(), listOf("Leader Misty"), emptyList(), null), null,
    )

    private val me = battler(BattlerRef.PLAYER_LEFT, 221, "PILOSWINE", 120, mon = piloswine)
    private val golduck = battler(BattlerRef.FOE_LEFT, 55, "GOLDUCK", 100, personality = 1)
    private val quagsire = battler(BattlerRef.FOE_LEFT, 195, "QUAGSIRE", 140, personality = 2)

    private fun attack(n: Int) = GameAction.Attack(MoveRef("move:$n"))

    /** Runs [actions]; step i leaves the game in `after[i]`. */
    private suspend fun run(
        start: GameState,
        after: List<GameState>,
        vararg actions: GameAction,
        limits: ChainLimits? = null,
        idle: () -> Duration = { Duration.ZERO },
        time: TestTimeSource = TestTimeSource(),
        stepTime: Duration = Duration.ZERO,
    ): Pair<ChainResult, List<Int>> {
        var current = start
        val executed = mutableListOf<Int>()
        val result = ChainRunner(
            observe = { current },
            execute = { _, index ->
                executed += index
                time += stepTime
                current = after[index]
                ActionOutcome.Done()
            },
            limits = limits,
            idle = idle,
            timeSource = time,
        ).run(actions.toList())
        return result to executed
    }

    @Test
    fun theChainStopsWhenTheAiSwitchesItsPokemon() = runTest {
        // Misty's AI: GOLDUCK out, QUAGSIRE (Water Absorb, immune to Ground... and to the planned move) in.
        val (result, executed) = run(state(me, golduck), listOf(state(me, quagsire), state(me, quagsire)), attack(1), attack(2))
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.FoeChanged>(result.stop)
        assertEquals("GOLDUCK" to "QUAGSIRE", stop.before to stop.now)
        assertEquals("FOE_CHANGED", stop.code)
        assertEquals(listOf("attack(move:2)"), result.skipped.map { it.key })
        assertNull(result.failed)
    }

    @Test
    fun aPokemonOfTheSameSpeciesSentAfterAKnockOutIsAnotherOne() = runTest {
        val electrode = battler(BattlerRef.FOE_LEFT, 101, "ELECTRODE", 100, personality = 7)
        val second = electrode.copy(personality = 8, hp = 150)
        val (result, executed) = run(state(me, electrode), listOf(state(me, second), state(me, second)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        assertIs<ChainStop.FoeChanged>(result.stop)
    }

    @Test
    fun theChainStopsWhenTheFoeFainted() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me, golduck.copy(hp = 0)), state(me, quagsire)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        assertEquals("FOE_FAINTED", result.stop?.code)
    }

    @Test
    fun theChainStopsWhenOurPokemonFainted() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me.copy(hp = 0), golduck), state(me, golduck)), attack(1), attack(1))
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.OwnFainted>(result.stop)
        assertEquals(piloswine, stop.mon)
    }

    @Test
    fun theSameFoeLosingHpLetsTheChainGoOn() = runTest {
        val (result, executed) = run(state(me, golduck), listOf(state(me, golduck.copy(hp = 40)), state(me, golduck.copy(hp = 10))), attack(1), attack(1))
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
        assertEquals(2, result.performed.size)
    }

    @Test
    fun ourOwnSwitchIsNoReasonToStop() = runTest {
        val gyarados = battler(BattlerRef.PLAYER_LEFT, 130, "GYARADOS", 150, mon = MonId(0x49c199cc, 0x76f3a6fb))
        val (result, executed) = run(state(me, golduck), listOf(state(gyarados, golduck), state(gyarados, golduck)), GameAction.Switch(gyarados.mon!!), attack(1))
        assertEquals(listOf(0, 1), executed)
        assertNull(result.stop)
    }

    @Test
    fun aChainGoesOnPastTheOldBudgetWhileTheGameProgresses() = runTest {
        // Three 20 s steps (60 s in all): the old 35 s budget would have stopped it; the game kept progressing.
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(3) { field }, GameAction.Wait(), GameAction.Wait(), GameAction.Wait(), limits = limits, idle = { 2.seconds }, stepTime = 20.seconds)
        assertEquals(listOf(0, 1, 2), executed)
        assertNull(result.stop)
    }

    @Test
    fun aChainStopsWhenNothingProgresses() = runTest {
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(3) { field }, GameAction.Wait(), GameAction.Wait(), GameAction.Wait(), limits = limits, idle = { 25.seconds }, stepTime = 25.seconds)
        assertEquals(listOf(0), executed)
        val stop = assertIs<ChainStop.Idle>(result.stop)
        assertEquals("IDLE", stop.code)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun theSafetyCapStopsEvenAProgressingChain() = runTest {
        val limits = ChainLimits(idle = 20.seconds, total = 90.seconds)
        val field = state(me, golduck).copy(battle = null)
        val (result, executed) = run(field, List(4) { field }, *Array(4) { GameAction.Wait() }, limits = limits, idle = { 1.seconds }, stepTime = 50.seconds)
        assertEquals(listOf(0, 1), executed)
        assertEquals("TIME_CAP", result.stop?.code)
    }
}
