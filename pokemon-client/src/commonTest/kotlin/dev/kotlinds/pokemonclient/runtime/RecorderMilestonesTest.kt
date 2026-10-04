package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Badge and evolution events of the [Recorder]: once each, and never backwards. */
class RecorderMilestonesTest {

    private val magikarp = MonId(0x120ae20c, 0x76f3a6fb)

    private fun mon(species: Int, name: String, level: Int) = PartyMon(
        id = magikarp, slot = 0, species = Named(SpeciesId(species), name), nickname = null, level = level,
        hp = 10, maxHp = 10, status = null, types = emptyList(), heldItem = null, ability = null, moves = emptyList(),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    private fun state(party: List<PartyMon>, badges: List<String>? = null) = GameState(
        0, Screen.Overworld(awaiting = Awaiting.INPUT), badges?.let { PlayerInfo("ACE", 0, it, 1) }, party, null, null, null,
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
    fun aBadgeIsReceivedOnceWhenItAppears() {
        val party = listOf(mon(129, "MAGIKARP", 20))
        val events = record(List(5) { state(party, emptyList()) } + List(5) { state(party, listOf("Zephyr")) })
        assertEquals(listOf("Zephyr"), events.filterIsInstance<GameEvent.BadgeReceived>().map { it.badge })
    }

    @Test
    fun badgesReadWhileThePlayerIsUnknownAreNotReceived() {
        // Before the player's data is readable (a load), then all eight badges: nothing was received.
        val party = listOf(mon(129, "MAGIKARP", 20))
        val all = listOf("Zephyr", "Hive", "Plain", "Fog", "Storm", "Mineral", "Glacier", "Rising")
        val events = record(List(3) { state(party, null) } + List(5) { state(party, all) })
        assertTrue(events.none { it is GameEvent.BadgeReceived })
    }

    @Test
    fun theOlderPartyCopyAfterAnEvolutionIsNotAnEvolutionBack() {
        // Live (Elite Four Will, Magikarp with Exp. Share): GYARADOS, then for two frames the save's older party
        // (MAGIKARP Lv20), then GYARADOS again. One evolution only.
        val before = state(listOf(mon(129, "MAGIKARP", 20)))
        val after = state(listOf(mon(130, "GYARADOS", 22)))
        val events = record(List(SEED_POLLS + 2) { before } + after + after + before + before + after)
        assertEquals(listOf("MAGIKARP" to "GYARADOS"), events.filterIsInstance<GameEvent.Evolved>().map { it.from to it.to })
        assertTrue(events.none { it is GameEvent.LevelUp })
    }
}
