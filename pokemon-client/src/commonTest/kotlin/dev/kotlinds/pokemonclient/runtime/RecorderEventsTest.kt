package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonObject
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattleKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Party and item events of the [Recorder]: only real changes (last valid reading of each Pokémon, lasting bag increases). */
class RecorderEventsTest {

    private val kenya = MonId(0x6b5e, 0x3e9)
    private val swinub = MonId(0x033ef671, 0x76f3a6fb)

    private fun mon(id: MonId, species: String, level: Int, slot: Int = 0) = PartyMon(
        id = id, slot = slot, species = Named(SpeciesId(species.length), species), nickname = null, level = level,
        hp = 10, maxHp = 10, status = null, types = emptyList(), heldItem = null, ability = null, moves = emptyList(),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    /**
     * Feeds [parties] to a recorder, one decoded state each, and returns the party events. The first party is held
     * for the recorder's seeding period first (like a game that has been running for a while), unless [boot].
     */
    private fun events(vararg parties: List<PartyMon>, boot: Boolean = false): List<GameEvent> =
        record((if (boot) parties.toList() else List(SEED_POLLS) { parties.first() } + parties)
            .map { GameState(0, Screen.Overworld(awaiting = Awaiting.INPUT), null, it, null, null, null) })
            .filter { it is GameEvent.LevelUp || it is GameEvent.Evolved || it is GameEvent.PokemonObtained }

    /** Feeds [states] to a recorder, one per frame, and returns every event. */
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
            recorder.onFrame(frame.toLong(), { memory })
        }
        return recorder.log.since(0)
    }

    private fun state(party: List<PartyMon>, inBattle: Boolean) = GameState(
        0, if (inBattle) Screen.Battle(Awaiting.ANIMATION) else Screen.Overworld(awaiting = Awaiting.INPUT), null, party, null,
        if (inBattle) BattleState(BattleKind.TRAINER, false, null, emptyList(), emptyList(), emptyList(), null) else null, null,
    )

    private fun bag(greatBalls: Int) = GameState(
        0, Screen.Overworld(awaiting = Awaiting.INPUT), null, emptyList(),
        listOf(BagPocket("balls", listOf(BagItem(Named(ItemId(3), "Great Ball"), greatBalls)))), null, null,
    )

    @Test
    fun theSavesOlderBagRightAfterABattleIsNotAnItemReceived() {
        // In battle 7 -> 6 (the battle's copy), one frame of the save's 7, then the copy written back: 6.
        val states = listOf(bag(7), bag(6), bag(7)) + List(20) { bag(6) }
        assertEquals(emptyList(), record(states).filterIsInstance<GameEvent.ItemReceived>())
    }

    @Test
    fun aLastingIncreaseIsRecordedOnce() {
        val states = listOf(bag(6)) + List(20) { bag(9) }
        assertEquals(listOf("Great Ball" to 3), record(states).filterIsInstance<GameEvent.ItemReceived>().map { it.item to it.quantity })
    }

    @Test
    fun aTornReadingWithAKnownPersonalityIsNotANewPokemon() {
        // "obtained Egg (mon:033ef671.4cc4af7d)" during the battle against Lance: Swinub's personality, another trainer id.
        val torn = mon(MonId(0x033ef671, 0x4cc4af7d), "EGG", 1).copy(isEgg = true)
        val events = events(listOf(mon(swinub, "PILOSWINE", 40)), listOf(mon(swinub, "PILOSWINE", 40), torn))
        assertEquals(emptyList(), events)
    }

    @Test
    fun anEggIsNeverObtainedInBattle() {
        val egg = mon(MonId(0x12345678, 0x3e9), "EGG", 1).copy(isEgg = true)
        val party = listOf(mon(kenya, "FEAROW", 38))
        val states = List(SEED_POLLS + 1) { state(party, inBattle = true) } + state(party + egg, inBattle = true)
        assertEquals(emptyList(), record(states).filterIsInstance<GameEvent.PokemonObtained>())
        // Out of battle, the same new Egg is obtained (the Day-Care, a gift).
        val field = List(SEED_POLLS + 1) { state(party, inBattle = false) } + state(party + egg, inBattle = false)
        assertEquals(1, record(field).filterIsInstance<GameEvent.PokemonObtained>().size)
    }

    @Test
    fun aRealLevelUpIsRecordedOnceWithTheName() {
        val events = events(listOf(mon(kenya, "FEAROW", 38)), listOf(mon(kenya, "FEAROW", 39)), listOf(mon(kenya, "FEAROW", 39)))
        assertEquals(listOf("LevelUp FEAROW 39"), events.map { (it as GameEvent.LevelUp).let { e -> "LevelUp ${e.name} ${e.level}" } })
    }

    @Test
    fun aLevelGoingDownAndBackIsNotALevelUp() {
        // "SWINUB reached level 31" while it already was 31: a lower reading in between must not reset the level.
        val events = events(
            listOf(mon(swinub, "SWINUB", 31)),
            listOf(mon(swinub, "SWINUB", 30)),
            listOf(mon(swinub, "SWINUB", 31)),
        )
        assertEquals(emptyList(), events)
    }

    @Test
    fun reorderingAndPcTripsAreNotNewPokemon() {
        val a = mon(kenya, "FEAROW", 38, slot = 1)
        val b = mon(swinub, "SWINUB", 31, slot = 0)
        val events = events(
            listOf(b, a),
            listOf(a.copy(slot = 0), b.copy(slot = 1)),
            listOf(a.copy(slot = 0)),
            listOf(a.copy(slot = 0), b.copy(slot = 1)),
        )
        assertEquals(emptyList(), events)
    }

    @Test
    fun evolutionAndNewPokemon() {
        val events = events(
            listOf(mon(swinub, "SWINUB", 32)),
            listOf(mon(swinub, "PILOSWINE", 33)),
            listOf(mon(swinub, "PILOSWINE", 33), mon(kenya, "FEAROW", 38, slot = 1)),
        )
        assertEquals(listOf(GameEvent.Evolved::class, GameEvent.PokemonObtained::class), events.map { it::class })
    }

    @Test
    fun aPartyReadSlotBySlotAfterBootIsNotNewPokemon() {
        // Booting a save: no party on the title screen, then the slots become readable one or a few at a time.
        val hooh = mon(MonId(0x59e9db62, 0x76f3a6fb), "HO-OH", 46, slot = 0)
        val kenya = mon(kenya, "FEAROW", 38, slot = 1)
        val swinub = mon(swinub, "PILOSWINE", 39, slot = 2)
        val events = events(emptyList(), emptyList(), listOf(hooh), listOf(hooh, kenya), listOf(hooh, kenya, swinub), listOf(hooh, kenya, swinub), boot = true)
        assertEquals(emptyList(), events)
    }
}
