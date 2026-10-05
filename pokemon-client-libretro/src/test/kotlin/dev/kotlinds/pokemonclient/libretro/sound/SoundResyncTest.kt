package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.ARM7_WRAM
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.MAIN_RAM
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Music during pauses on real save states of HeartGold US (reduced fixtures, see [SoundFixtures], captured with the
 * bench's `pausemusicfixtures`): `_p` the paused game, `_s` the shadow's safe end 120 frames later, `_busy` a frame
 * where the ARM7 runs the sound thread, `_dma` (melonDS) a frame with a DMA mid-burst. `ds_cherry`: DeSmuME 0.9.12
 * in Cherrygrove City; `ds_battle_will` (Elite Four Will) and `ds_battle_wild` (a wild Hoothoot): DeSmuME battles at
 * the command menu, where HeartGold keeps the field music paused on another player while the battle music plays;
 * `mel`: melonDS 0.9.3.
 */
class SoundResyncTest {

    private val layout = SoundDriverLayout.HEARTGOLD_US

    private fun fixture(name: String): ByteArray =
        SoundFixtures.read(Path.of(javaClass.getResource("/sound/pausemusic_$name.state.gz")!!.toURI()))

    private fun resync(splicer: SavestateSoundSplicer) = SoundResync(layout, splicer)

    private fun setArm7(state: ByteArray, splicer: SavestateSoundSplicer, address: Int, value: Int) {
        val offset = splicer.locate(state).arm7Wram.offset + address - ARM7_WRAM
        for (b in 0 until 4) state[offset + b] = (value ushr (8 * b)).toByte()
    }

    private fun setMain(state: ByteArray, splicer: SavestateSoundSplicer, address: Int, value: Int) {
        val offset = splicer.locate(state).mainRam.offset + address - MAIN_RAM
        for (b in 0 until 4) state[offset + b] = (value ushr (8 * b)).toByte()
    }

    // region formats

    @Test
    fun locatesDesmumeRegions() {
        val state = SavestateSoundSplicer.DESMUME.locate(fixture("ds_cherry_p"))
        assertEquals(0x400000, state.mainRam.size)
        assertEquals(0x10000, state.arm7Wram.size)
        assertEquals(listOf(1084, 0x120), state.soundChip.map { it.size }) // SPU chunk + ARM7 sound registers
        assertEquals(0x021E1AC0, state.arm7(layout.sharedWorkPointer))
    }

    @Test
    fun locatesMelonDsRegions() {
        val state = SavestateSoundSplicer.MELONDS.locate(fixture("mel_p"))
        assertEquals(0x400000, state.mainRam.size)
        assertEquals(state.mainRam.offset + 0x408000, state.arm7Wram.offset)
        assertEquals(listOf(1787), state.soundChip.map { it.size })
        assertEquals(0x021E1AC0, state.arm7(layout.sharedWorkPointer))
    }

    @Test
    fun refusesUnknownFormatsAndVersions() {
        val ds = fixture("ds_cherry_p")
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.MELONDS.locate(ds) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf().also { it[0x10] = 13 }) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf().also { it[0x1C] = 0 }) } // compressed
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(ds.copyOf(1 shl 20)) } // truncated
        val mel = fixture("mel_p")
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.DESMUME.locate(mel) }
        assertFailsWith<UnsupportedSavestateException> { SavestateSoundSplicer.MELONDS.locate(mel.copyOf().also { it[4] = 10 }) }
    }

    @Test
    fun refusesStatesOfAnotherLayout() {
        val decision = resync(SavestateSoundSplicer.DESMUME).splice(fixture("ds_cherry_p"), fixture("mel_s"))
        assertIs<ResyncRefusal.UnsupportedState>(assertIs<ResyncDecision.Refused>(decision).reason)
    }

    // endregion

    // region decoding

    @Test
    fun decodesTheMusicPlayer() {
        for ((name, splicer, player) in listOf(Triple("ds_cherry_p", SavestateSoundSplicer.DESMUME, 7), Triple("mel_p", SavestateSoundSplicer.MELONDS, 15))) {
            val driver = SoundDriverState.read(splicer.locate(fixture(name)), layout)
            val music = driver.players.single()
            assertEquals(player, music.index, name)
            assertEquals(1, music.flags)
            assertEquals(9, music.tracks.size)
            assertTrue(driver.isSafeFrame, name)
            assertEquals(emptyList(), driver.commandsInFlight)
            assertEquals(0, driver.lockedChannels)
        }
    }

    /** In a battle, the field music stays paused on its own player while the battle music plays. */
    @Test
    fun decodesTheBattleMusicNextToThePausedFieldMusic() {
        for ((name, music, field) in listOf(Triple("ds_battle_will_p", 6, 4), Triple("ds_battle_wild_p", 12, 9))) {
            val driver = SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(fixture(name)), layout)
            assertEquals(listOf(field, music), driver.players.map { it.index }, name)
            assertEquals(music, driver.playing.single().index, name)
            assertEquals(setOf(field), driver.pausedPlayerState.keys, name)
            val paused = driver.players.first { it.index == field }
            assertTrue(paused.isPaused, name)
            assertEquals(0x24 + 0x40 * paused.tracks.size, driver.pausedPlayerState.getValue(field).size, name)
        }
    }

    @Test
    fun seesTheSoundThreadRunning() {
        assertTrue(SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(fixture("ds_cherry_busy")), layout).soundThreadRunning)
        assertTrue(SoundDriverState.read(SavestateSoundSplicer.MELONDS.locate(fixture("mel_busy")), layout).soundThreadRunning)
    }

    // endregion

    // region splice

    @Test
    fun spliceChangesOnlyTheSoundStateAndTakesTheShadows() {
        for ((name, splicer) in listOf("ds_cherry" to SavestateSoundSplicer.DESMUME, "mel" to SavestateSoundSplicer.MELONDS)) {
            val paused = fixture("${name}_p")
            val shadow = fixture("${name}_s")
            val spliced = assertIs<ResyncDecision.Spliced>(resync(splicer).splice(paused, shadow), name).state
            val p = splicer.locate(paused)
            val s = splicer.locate(shadow)
            val allowed = splicer.splicedRegions(p, layout)
            var changed = 0
            for (i in paused.indices) {
                val inAllowed = allowed.any { i >= it.offset && i < it.end }
                if (inAllowed) {
                    assertEquals(shadow[i], spliced[i], "$name: byte $i must come from the shadow")
                } else if (spliced[i] != paused[i]) {
                    error("$name: byte $i changed outside the sound state")
                }
                if (spliced[i] != paused[i]) changed++
            }
            assertTrue(changed > 0, "$name: the shadow's music state differs from the paused one")
            assertContentEquals(paused.copyOfRange(p.mainRam.offset, p.mainRam.end), spliced.copyOfRange(p.mainRam.offset, p.mainRam.end))
            // The sequencer work is the shadow's.
            val work = layout.work - ARM7_WRAM
            assertContentEquals(
                shadow.copyOfRange(s.arm7Wram.offset + work, s.arm7Wram.offset + work + 0x1180),
                spliced.copyOfRange(p.arm7Wram.offset + work, p.arm7Wram.offset + work + 0x1180),
            )
        }
    }

    // endregion

    // region guards

    private fun refusal(splicer: SavestateSoundSplicer, paused: ByteArray, shadow: ByteArray): ResyncRefusal? =
        (resync(splicer).splice(paused, shadow) as? ResyncDecision.Refused)?.reason

    @Test
    fun refusesWhenTheSoundThreadRanAtThePause() {
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_busy"), fixture("ds_cherry_s")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.MELONDS, fixture("mel_busy"), fixture("mel_s")))
    }

    @Test
    fun refusesAShadowNotAtASafeFrame() {
        assertEquals(ResyncRefusal.ShadowNotAtSafeFrame, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), fixture("ds_cherry_busy")))
        val queued = fixture("ds_cherry_s").also { setArm7(it, SavestateSoundSplicer.DESMUME, layout.commandQueue + 0x1C, 1) }
        assertEquals(ResyncRefusal.ShadowNotAtSafeFrame, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), queued))
        assertTrue(!resync(SavestateSoundSplicer.DESMUME).isSafeFrame(queued))
        assertTrue(resync(SavestateSoundSplicer.DESMUME).isSafeFrame(fixture("ds_cherry_s")))
    }

    /** A second active player at the pause (a sound effect, a cry...). */
    @Test
    fun refusesWhenNotOnlyTheMusicPlays() {
        val paused = fixture("ds_cherry_p").also {
            val player0 = layout.work + 0x540
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + player0 - ARM7_WRAM
            it[offset] = (it[offset].toInt() or 1).toByte()
        }
        assertEquals(ResyncRefusal.NotOnlyMusic(2, 1), refusal(SavestateSoundSplicer.DESMUME, paused, fixture("ds_cherry_s")))
    }

    /** Battles: the paused field music doesn't count as a second sound, and its state stays the paused game's. */
    @Test
    fun resyncsABattleWhoseFieldMusicIsPaused() {
        for (name in listOf("ds_battle_will", "ds_battle_wild")) {
            val decision = assertIs<ResyncDecision.Spliced>(resync(SavestateSoundSplicer.DESMUME).splice(fixture("${name}_p"), fixture("${name}_s")), name)
            assertEquals(decision.atPause.pausedPlayerState, SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(decision.state), layout).pausedPlayerState)
            val main = FakeConsole(SavestateSoundSplicer.DESMUME, fixture("${name}_p"))
            val result = resync(SavestateSoundSplicer.DESMUME).apply(main, fixture("${name}_p"), decision)
            assertEquals(decision.atPause.playing.single(), assertIs<ResyncResult.Resynced>(result, name).music)
        }
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_busy"), fixture("ds_battle_will_s")))
    }

    /** A paused player that moved during the pause (the game touched it): not the music's state to take. */
    @Test
    fun refusesWhenAPausedPlayerMoved() {
        val shadow = fixture("ds_battle_will_s").also {
            val field = layout.work + 0x540 + 4 * 0x24
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + field + 0x1C - ARM7_WRAM
            it[offset]++
        }
        assertEquals(ResyncRefusal.PausedPlayerMoved, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_p"), shadow))
        // Unpaused during the pause: two sequences playing at the end.
        val unpaused = fixture("ds_battle_will_s").also {
            val field = layout.work + 0x540 + 4 * 0x24
            val offset = SavestateSoundSplicer.DESMUME.locate(it).arm7Wram.offset + field - ARM7_WRAM
            it[offset] = (it[offset].toInt() and SoundDriverState.PAUSED.inv()).toByte()
        }
        assertEquals(ResyncRefusal.NotOnlyMusic(1, 2), refusal(SavestateSoundSplicer.DESMUME, fixture("ds_battle_will_p"), unpaused))
    }

    @Test
    fun refusesWhenTheSongChanged() {
        val shadow = fixture("ds_cherry_s").also {
            val player7 = layout.work + 0x540 + 7 * 0x24
            setArm7(it, SavestateSoundSplicer.DESMUME, player7 + 0x20, 0x02123456) // another bank
        }
        assertEquals(ResyncRefusal.SongChanged, refusal(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"), shadow))
    }

    @Test
    fun refusesLockedChannels() {
        val shadow = fixture("mel_s").also { setArm7(it, SavestateSoundSplicer.MELONDS, layout.lockedChannels.first(), 0x8000) }
        assertEquals(ResyncRefusal.LockedChannels(0x8000), refusal(SavestateSoundSplicer.MELONDS, fixture("mel_p"), shadow))
    }

    /** One more list flushed by the ARM9 than the ARM7 finished, holding a single command [id]. */
    private fun withCommandInFlight(id: Int): ByteArray = fixture("ds_cherry_p").also { state ->
        val splicer = SavestateSoundSplicer.DESMUME
        val located = splicer.locate(state)
        val shared = located.arm7(layout.sharedWorkPointer)
        val arm9 = layout.arm9Commands
        val write = located.main(shared + arm9.waitingWrite)
        val command = 0x021E1D40 // sCommandArray[0]
        setMain(state, splicer, shared + arm9.currentTag, located.main(shared + arm9.currentTag) + 1)
        setMain(state, splicer, shared + arm9.waitingQueue + ((write - 1 + arm9.waitingSlots) % arm9.waitingSlots) * 4, command)
        setMain(state, splicer, command, 0) // last command of the list
        setMain(state, splicer, command + 4, id)
    }

    @Test
    fun acceptsIdempotentCommandsInFlightAtThePause() {
        val paused = withCommandInFlight(0x21) // READ_DRIVER_INFO
        assertEquals(listOf(0x21), SoundDriverState.read(SavestateSoundSplicer.DESMUME.locate(paused), layout).commandsInFlight)
        assertIs<ResyncDecision.Spliced>(resync(SavestateSoundSplicer.DESMUME).splice(paused, fixture("ds_cherry_s")))
    }

    @Test
    fun refusesCommandsInFlightThatCantRunTwice() {
        assertEquals(ResyncRefusal.CommandsInFlightAtPause(listOf(0x00)), refusal(SavestateSoundSplicer.DESMUME, withCommandInFlight(0x00), fixture("ds_cherry_s")))
    }

    /** The paused-state guards alone: what the app checks to start a pause one frame later (see `ShadowAudio`). */
    @Test
    fun tellsFramesAPauseCantStartFrom() {
        val ds = resync(SavestateSoundSplicer.DESMUME)
        assertNull(ds.pauseRefusal(fixture("ds_cherry_p")))
        assertNull(ds.pauseRefusal(fixture("ds_battle_will_p")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, ds.pauseRefusal(fixture("ds_cherry_busy")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, ds.pauseRefusal(fixture("ds_battle_will_busy")))
        assertEquals(ResyncRefusal.CommandsInFlightAtPause(listOf(0x00)), ds.pauseRefusal(withCommandInFlight(0x00)))
        assertNull(ds.pauseRefusal(withCommandInFlight(0x21)))
        assertIs<ResyncRefusal.UnsupportedState>(ds.pauseRefusal(fixture("mel_p")))
        assertEquals(ResyncRefusal.SoundThreadRunningAtPause, resync(SavestateSoundSplicer.MELONDS).pauseRefusal(fixture("mel_busy")))
    }

    // endregion

    // region main RAM identity

    /** A console whose whole state is a save state blob, its main RAM being the blob's main RAM region. */
    private class FakeConsole(private val splicer: SavestateSoundSplicer, var state: ByteArray, val afterLoad: (ByteArray) -> Unit = {}) : ConsolePort {
        var loads = mutableListOf<ByteArray>()
        var rejects = false
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override var revision = 0L
        override fun step(frames: Int, input: InputFrame) { frame += frames }
        override fun memorySize(region: MemoryRegion) = 0x400000
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) {
            val ram = splicer.locate(state).mainRam
            state.copyInto(into, 0, ram.offset + offset, ram.offset + offset + length)
        }
        override fun framebuffer(): Frame? = null
        override fun saveState() = state.copyOf()
        override fun loadState(state: ByteArray): Boolean {
            if (rejects && loads.isEmpty()) return false.also { loads += state }
            loads += state
            this.state = state.copyOf().also(afterLoad)
            revision++
            return true
        }
    }

    private fun spliced(splicer: SavestateSoundSplicer, name: String) =
        assertIs<ResyncDecision.Spliced>(resync(splicer).splice(fixture("${name}_p"), fixture("${name}_s")))

    @Test
    fun appliesTheSpliceWithTheMainRamUnchanged() {
        val paused = fixture("ds_cherry_p")
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf())
        val decision = spliced(SavestateSoundSplicer.DESMUME, "ds_cherry")
        assertIs<ResyncResult.Resynced>(resync(SavestateSoundSplicer.DESMUME).apply(main, paused, decision))
        assertContentEquals(decision.state, main.state)
    }

    @Test
    fun restoresThePausedStateWhenTheMainRamDiffersAfterLoading() {
        val paused = fixture("mel_p")
        val ram = SavestateSoundSplicer.MELONDS.locate(paused).mainRam
        var corrupt = true
        val main = FakeConsole(SavestateSoundSplicer.MELONDS, paused.copyOf()) { loaded -> if (corrupt) loaded[ram.offset + 1234]++; corrupt = false }
        val result = resync(SavestateSoundSplicer.MELONDS).apply(main, paused, spliced(SavestateSoundSplicer.MELONDS, "mel"))
        assertIs<ResyncResult.Aborted>(result)
        assertContentEquals(paused, main.state) // the paused state, loaded back
    }

    @Test
    fun refusesWhenTheMainRamChangedDuringThePause() {
        val paused = fixture("ds_cherry_p")
        val ram = SavestateSoundSplicer.DESMUME.locate(paused).mainRam
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf().also { it[ram.offset + 99]++ })
        val result = resync(SavestateSoundSplicer.DESMUME).apply(main, paused, spliced(SavestateSoundSplicer.DESMUME, "ds_cherry"))
        assertEquals(ResyncResult.Refused(ResyncRefusal.MainRamChangedDuringPause), result)
        assertTrue(main.loads.isEmpty(), "nothing loaded")
    }

    @Test
    fun loadsThePausedStateBackWhenTheCoreRejectsTheSplice() {
        val paused = fixture("ds_cherry_p")
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, paused.copyOf()).also { it.rejects = true }
        assertIs<ResyncResult.Aborted>(resync(SavestateSoundSplicer.DESMUME).apply(main, paused, spliced(SavestateSoundSplicer.DESMUME, "ds_cherry")))
        assertContentEquals(paused, main.loads.last())
    }

    // endregion

    // region shadow

    /** A shadow whose frames walk through [states] (what [ConsolePort.saveState] returns after each step). */
    private class FakeShadow(private val states: List<ByteArray>) : ConsolePort {
        val loaded = mutableListOf<ByteArray>()
        val steps = mutableListOf<Boolean>() // silenced?
        var silenced = false
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override var revision = 0L
        override fun step(frames: Int, input: InputFrame) { repeat(frames) { steps += silenced; frame++ } }
        override fun memorySize(region: MemoryRegion) = 0
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = states[minOf(frame.toInt(), states.size - 1)]
        override fun loadState(state: ByteArray): Boolean { loaded += state; frame = 0; return true }
    }

    @Test
    fun shadowEndsAtTheFirstSafeFrame() {
        val busy = fixture("ds_cherry_busy")
        val safe = fixture("ds_cherry_s")
        val shadow = FakeShadow(listOf(busy, busy, busy, safe))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        run.step()
        val end = assertIs<ShadowEnd.Safe>(run.end())
        assertEquals(3, end.frames)
        assertContentEquals(safe, end.state)
    }

    @Test
    fun shadowGivesUpAfterThreeUnsafeFrames() {
        val busy = fixture("ds_cherry_busy")
        val shadow = FakeShadow(listOf(busy))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        run.step()
        assertEquals(ShadowEnd.NotSafe(1 + ShadowRun.MAX_EXTRA_FRAMES), run.end())
    }

    @Test
    fun aShadowThatPlayedNothingHasNothingToResync() {
        val run = ShadowRun(FakeShadow(listOf(fixture("ds_cherry_s"))), resync(SavestateSoundSplicer.DESMUME))
        run.begin(fixture("ds_cherry_p"))
        val main = FakeConsole(SavestateSoundSplicer.DESMUME, fixture("ds_cherry_p"))
        assertEquals(ResyncResult.Refused(ResyncRefusal.NothingPlayed), resync(SavestateSoundSplicer.DESMUME).resume(main, fixture("ds_cherry_p"), run.end()))
        assertTrue(main.loads.isEmpty())
    }

    /** melonDS 0.9.3 crashes loading a mid-burst DMA into a fresh instance: the shadow first runs a silent priming frame. */
    @Test
    fun primesAMelonDsShadowSilentlyBeforeAMidBurstDma() {
        val dma = fixture("mel_dma")
        val priming = assertNotNull(SavestateSoundSplicer.MELONDS.primingState(dma))
        assertEquals(dma.size, priming.size)
        assertEquals(1, dma.indices.count { dma[it] != priming[it] })
        assertNull(SavestateSoundSplicer.MELONDS.primingState(fixture("mel_p")))
        assertNull(SavestateSoundSplicer.DESMUME.primingState(fixture("ds_cherry_p")))

        val shadow = FakeShadow(listOf(fixture("mel_s")))
        val run = ShadowRun(shadow, resync(SavestateSoundSplicer.MELONDS), silence = { shadow.silenced = it })
        assertTrue(run.begin(dma))
        assertEquals(listOf(true), shadow.steps) // one silent frame
        assertContentEquals(priming, shadow.loaded[0])
        assertContentEquals(dma, shadow.loaded[1])
        assertEquals(0, run.frames)
    }

    // endregion

    // region layout

    @Test
    fun recognizesHeartGoldUsByItsArm7Binary() {
        val rom = System.getenv("POKEMON_ROM")?.let(Path::of)?.takeIf(Files::exists) ?: return
        assertEquals(SoundDriverLayout.HEARTGOLD_US, SoundDriverLayout.forRom(rom))
    }

    @Test
    fun anUnknownArm7BinaryIsNotSupported() {
        val rom = Files.createTempFile("rom", ".nds")
        try {
            val bytes = ByteArray(0x1000)
            bytes[0x30] = 0x00; bytes[0x31] = 0x02 // ARM7 at 0x200
            bytes[0x3C] = 0x00; bytes[0x3D] = 0x01 // 0x100 bytes
            Files.write(rom, bytes)
            assertNotNull(SoundDriverLayout.arm7Sha1(rom))
            assertNull(SoundDriverLayout.forRom(rom))
        } finally {
            Files.delete(rom)
        }
    }

    // endregion
}
