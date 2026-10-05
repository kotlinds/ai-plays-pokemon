package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BoxMon
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PcBoxContents
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Typed events of the [Recorder]: captures (party or PC box), Pokémon sent to the PC, moves learned. */
class RecorderTypedEventsTest {
    private val ampharos = MonId(0x8dd175d1, 0x76f3a6fb)
    private val spinarak = MonId(0x1111, 0x76f3a6fb)
    private val hoothoot = MonId(0xc50a0956, 0x76f3a6fb)

    private fun move(id: Int, name: String) = KnownMove(Named(MoveId(id), name), 10, 10)

    private fun mon(id: MonId, species: String, moves: List<KnownMove> = emptyList(), slot: Int = 0) = PartyMon(
        id = id, slot = slot, species = Named(SpeciesId(1), species), nickname = null, level = 30,
        hp = 10, maxHp = 10, status = null, types = emptyList(), heldItem = null, ability = null, moves = moves,
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    private fun storage(vararg mons: BoxMon) = PcStorage(0, listOf(PcBoxContents(0, "BOX 1", mons.toList(), 30)))
    private fun boxed(id: MonId, species: String) = BoxMon(id, 0, 0, Named(SpeciesId(2), species), null, 20, null, false)
    private val wild = BattleState(BattleKind.WILD, false, null, emptyList(), emptyList(), emptyList(), null)

    private fun state(party: List<PartyMon>, storage: PcStorage?, battle: BattleState? = null, screen: Screen = Screen.Overworld(awaiting = Awaiting.INPUT)) =
        GameState(0, if (battle != null) Screen.Battle(Awaiting.ANIMATION) else screen, null, party, null, battle, null, storage = storage)

    /** Feeds [states] (after a seeding period on the first one) and returns the typed events. */
    private fun record(vararg states: GameState): List<GameEvent> {
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
        (List(SEED_POLLS + 1) { states.first() } + states.drop(1)).forEachIndexed { frame, s ->
            current = s
            recorder.onFrame(frame.toLong()) { memory }
        }
        return recorder.log.since(0).filter { it is GameEvent.Caught || it is GameEvent.SentToBox || it is GameEvent.LearnedMove || it is GameEvent.PokemonObtained }
    }

    @Test
    fun aCaptureWithAFullPartyGoesToTheBox() {
        val party = listOf(mon(ampharos, "AMPHAROS"))
        val events = record(
            state(party, storage(boxed(hoothoot, "HOOTHOOT"))),
            state(party, storage(boxed(hoothoot, "HOOTHOOT")), wild),
            state(party, storage(boxed(hoothoot, "HOOTHOOT"), boxed(spinarak, "SPINARAK")), wild),
            state(party, storage(boxed(hoothoot, "HOOTHOOT"), boxed(spinarak, "SPINARAK")), wild),
            state(party, storage(boxed(hoothoot, "HOOTHOOT"), boxed(spinarak, "SPINARAK"))),
        ).map { it::class.simpleName to it }
        assertEquals(listOf("SentToBox", "Caught"), events.map { it.first })
        val caught = events[1].second as GameEvent.Caught
        assertEquals(spinarak, caught.mon)
        assertEquals("BOX 1", caught.boxName)
    }

    @Test
    fun aCaptureWithRoomJoinsTheParty() {
        val party = listOf(mon(ampharos, "AMPHAROS"))
        val events = record(
            state(party, storage()),
            state(party, storage(), wild),
            state(party + mon(spinarak, "SPINARAK", slot = 1), storage(), wild),
        )
        assertEquals(listOf("PokemonObtained", "Caught"), events.map { it::class.simpleName })
    }

    @Test
    fun depositingAndWithdrawingAreNotNewPokemon() {
        val both = listOf(mon(ampharos, "AMPHAROS"), mon(spinarak, "SPINARAK", slot = 1))
        val events = record(
            state(both, storage(boxed(hoothoot, "HOOTHOOT"))),
            state(both.take(1), storage(boxed(hoothoot, "HOOTHOOT"), boxed(spinarak, "SPINARAK"))),
            state(both.take(1), storage(boxed(hoothoot, "HOOTHOOT"), boxed(spinarak, "SPINARAK"))),
            state(both.take(1) + mon(hoothoot, "HOOTHOOT", slot = 1), storage(boxed(spinarak, "SPINARAK"))),
        )
        assertEquals(emptyList(), events)
    }

    @Test
    fun aLearnedMoveNamesTheOneForgotten() {
        val before = mon(ampharos, "AMPHAROS", listOf(move(84, "ThunderShock"), move(86, "Thunder Wave"), move(9, "ThunderPunch"), move(33, "Tackle")))
        val after = before.copy(moves = listOf(move(84, "ThunderShock"), move(86, "Thunder Wave"), move(9, "ThunderPunch"), move(435, "Discharge")))
        val events = record(state(listOf(before), storage()), state(listOf(after), storage()), state(listOf(after), storage()))
        val learned = events.single() as GameEvent.LearnedMove
        assertEquals("Discharge" to "Tackle", learned.move to learned.forgot)
    }

    @Test
    fun ssAMovesetReadOnceWhileTheGameRewritesThePokemonIsNoMoveLearned() {
        // The nurse restores PP: one reading caught mid-rewrite passed the checks with garbage moves, then the real ones.
        val piloswine = mon(ampharos, "PILOSWINE", listOf(move(70, "Strength"), move(420, "Ice Shard"), move(196, "Icy Wind"), move(426, "Mud Bomb")))
        val torn = piloswine.copy(moves = listOf(move(57918, "MOVE_57918"), move(19110, "MOVE_19110"), move(196, "Icy Wind"), move(426, "Mud Bomb")))
        val events = record(
            state(listOf(piloswine), storage()),
            state(listOf(torn), storage()),
            state(listOf(piloswine), storage()),
            state(listOf(piloswine), storage()),
        )
        assertEquals(emptyList(), events)
    }
}
