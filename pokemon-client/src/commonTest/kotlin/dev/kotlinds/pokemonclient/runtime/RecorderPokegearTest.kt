package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Pokégear upgraded" events: a card gained (never a bag item), once, and never from an unknown reading. */
class RecorderPokegearTest {

    private fun state(cards: Set<PokegearCard>?) = GameState(
        0, Screen.Overworld(awaiting = Awaiting.INPUT), PlayerInfo("ACE", 0, emptyList(), 1, pokegearCards = cards), emptyList(), null, null, null,
    )

    private fun record(states: List<GameState>): List<GameEvent> {
        var current = states.first()
        val game = object : PokemonGame {
            override val name = "Scripted"
            override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
            override fun state(memory: Memory) = current
            override val inputProbe = InputProbe { emptySet() }
        }
        val recorder = Recorder(game, every = 1)
        val memory = object : Memory {
            override fun read8(addr: Long) = 0
            override fun read16(addr: Long) = 0
            override fun read32(addr: Long) = 0L
            override fun readBytes(addr: Long, size: Int) = ByteArray(size)
        }
        states.forEachIndexed { frame, state ->
            current = state
            recorder.onFrame(frame.toLong()) { memory }
        }
        return recorder.log.since(0)
    }

    @Test
    fun theExpansionCardIsAnUpgradeOnce() {
        val before = setOf(PokegearCard.MAP, PokegearCard.RADIO)
        val events = record(List(3) { state(before) } + List(4) { state(before + PokegearCard.EXPANSION) })
        assertEquals(listOf(PokegearCard.EXPANSION), events.filterIsInstance<GameEvent.PokegearUpgraded>().map { it.card })
    }

    @Test
    fun cardsFirstReadAfterAnUnknownReadingAreNotUpgrades() {
        val events = record(List(2) { state(null) } + List(3) { state(setOf(PokegearCard.RADIO)) })
        assertTrue(events.none { it is GameEvent.PokegearUpgraded })
    }
}
