package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.actions.FakeGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays battle turns recorded frame by frame on the bench (`truth:on` ... `truth:dump`): the battle message the
 * game held on every frame of Elite Four Will's battle, with a Pokémon K.O.'d on switching in, "Go! X", a miss,
 * confusion, a K.O. of the foe, EXP and the switch question, half-written reads included ("It hurt i").
 * Every complete message must be recorded exactly once, in order, whatever the polling phase.
 */
class RecorderReplayTest {
    /** The message of every frame (null: none), from the run-length encoded trace. */
    private val frames: List<String?> by lazy {
        val text = javaClass.classLoader.getResource("hgss/bt_will_turns.trace")!!.readText()
        text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.flatMap { line ->
            val count = line.substringBefore('\t').toInt()
            val message = line.substringAfter('\t').replace("\\n", "\n").replace("\\\\", "\\").ifEmpty { null }
            List(count) { message }
        }
    }

    /** What the player saw: each message once, in order, a half-written read being only the start of the next one. */
    private fun shown(frames: List<String?>): List<String> {
        val seen = mutableListOf<String>()
        frames.filterNotNull().forEach { message ->
            if (message == seen.lastOrNull()) return@forEach
            if (seen.isNotEmpty() && message.startsWith(seen.last())) seen[seen.size - 1] = message else seen += message
        }
        return seen
    }

    /** Runs a recorder polling every [every] frames (from frame [phase]) over [frames]. */
    private fun recorded(frames: List<String?>, every: Int, phase: Int): List<String> {
        var current: String? = null
        val game = FakeGame(Screen.Battle(Awaiting.ANIMATION)) { screen ->
            GameState(0, screen, null, emptyList(), null, BattleState(BattleKind.TRAINER, false, null, emptyList(), emptyList(), emptyList(), current), null)
        }
        val recorder = Recorder(game, every = every)
        val memory = RamMemory(ByteArray(16))
        frames.forEachIndexed { index, message ->
            current = message
            recorder.onFrame((index + phase).toLong()) { memory }
        }
        return recorder.log.since(0).filterIsInstance<GameEvent.TextShown>().filter { it.source == TextSource.BATTLE }.map { it.text }
    }

    @Test
    fun `every message of the recorded turns is recorded once, in order`() {
        val expected = shown(frames)
        assertEquals(43, expected.size)
        for (phase in 0..1) assertEquals(expected, recorded(frames, every = 2, phase = phase), "polling phase $phase")
    }

    @Test
    fun `messages cut short by presses are still recorded once`() {
        // The same turns with every message held 3 frames only (A / B / touch end the battle script's waits early).
        val short = frames.fold(mutableListOf<String?>()) { acc, message ->
            val run = acc.takeLastWhile { it == message }.size
            if (message == null || run < 3) acc += message
            acc
        }
        short += List(4) { null } // the battle goes on to its menus: the last message is replaced
        val expected = shown(frames)
        for (phase in 0..1) assertEquals(expected, recorded(short, every = 2, phase = phase), "polling phase $phase")
    }
}
