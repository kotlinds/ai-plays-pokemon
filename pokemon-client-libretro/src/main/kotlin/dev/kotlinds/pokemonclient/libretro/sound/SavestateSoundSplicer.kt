package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.ARM7_WRAM
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.MAIN_RAM
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout.Companion.le32

/** A byte range [offset, offset + size) inside a save state blob. */
data class StateRegion(val offset: Int, val size: Int) {
    val end: Int get() = offset + size
}

/**
 * Where things are inside one save state of a known core: the main RAM, the ARM7 work RAM, and the sound chip
 * (SPU) state. Offsets are into the state blob.
 */
class SoundSavestate(
    val bytes: ByteArray,
    /** The 4 MB main RAM (0x02000000). */
    val mainRam: StateRegion,
    /** The 64 KB ARM7 work RAM (0x03800000). */
    val arm7Wram: StateRegion,
    /** The sound chip: channel registers and the core's mixer state. */
    val soundChip: List<StateRegion>,
) {
    /** u32 at an ARM7 WRAM bus address. */
    fun arm7(address: Int): Int = le32(bytes, arm7Wram.offset + (address - ARM7_WRAM).also { require(it in 0..arm7Wram.size - 4) })

    /** Whether [address] (4 bytes) is in main RAM. */
    fun inMainRam(address: Int): Boolean = address - MAIN_RAM in 0..mainRam.size - 4

    /** u32 at a main RAM bus address. */
    fun main(address: Int): Int = le32(bytes, mainRam.offset + (address - MAIN_RAM).also { require(it in 0..mainRam.size - 4) })

    /** Whether [other] has every region at the same place (same core build, same layout). */
    fun sameLayoutAs(other: SoundSavestate): Boolean =
        bytes.size == other.bytes.size && mainRam == other.mainRam && arm7Wram == other.arm7Wram && soundChip == other.soundChip
}

/** The state isn't one this core build is known to produce: music during pauses refuses to touch it. */
class UnsupportedSavestateException(message: String) : Exception(message)

/**
 * Copies the sound playback state of one save state into another: the only change music during pauses makes to the
 * game. Everything else (main RAM, both CPUs, video, every other device) stays the paused state's.
 *
 * Each core has its own save state format: [DESMUME] (DeSmuME 0.9.12) and [MELONDS] (melonDS 0.9.3). Both refuse
 * anything but the exact format version and section sizes they know ([UnsupportedSavestateException]).
 */
sealed class SavestateSoundSplicer {

    /** Locates the regions of [state], or throws [UnsupportedSavestateException]. */
    abstract fun locate(state: ByteArray): SoundSavestate

    /**
     * A copy of [paused] where the sequencer ranges of [layout] (ARM7 WRAM) and the sound chip come from [shadow].
     * Both must have the same layout (they come from the same core build).
     */
    fun splice(paused: SoundSavestate, shadow: SoundSavestate, layout: SoundDriverLayout): ByteArray {
        if (!paused.sameLayoutAs(shadow)) throw UnsupportedSavestateException("the two states have different layouts")
        val out = paused.bytes.copyOf()
        for (range in layout.sequencerRanges) {
            val offset = paused.arm7Wram.offset + (range.start - ARM7_WRAM)
            require(range.start - ARM7_WRAM + range.size <= paused.arm7Wram.size) { "range outside ARM7 WRAM: $range" }
            shadow.bytes.copyInto(out, offset, offset, offset + range.size)
        }
        for (region in paused.soundChip) shadow.bytes.copyInto(out, region.offset, region.offset, region.end)
        return out
    }

    /**
     * A state to run once in a fresh shadow before it can load [state] (see [MELONDS]), or null when none is needed.
     * Only ever given to the shadow, whose state is then replaced by [state] itself.
     */
    open fun primingState(state: ByteArray): ByteArray? = null

    /** The regions [splice] may change in a state of this layout (everything else must stay byte-identical). */
    fun splicedRegions(state: SoundSavestate, layout: SoundDriverLayout): List<StateRegion> =
        layout.sequencerRanges.map { StateRegion(state.arm7Wram.offset + (it.start - ARM7_WRAM), it.size) } + state.soundChip

    /**
     * DeSmuME 0.9.12 (`savestate.cpp`): a 32-byte header ("DeSmuME SState", format version 12, uncompressed), then
     * chunks (`u32 id, u32 size, data`) until id 0xFFFFFFFF. Chunk 4 (ARM9 memory) and chunk 60 (ARM7 memory) are
     * lists of fields (`char key[4], u32 size, u32 count, data`): main RAM is field `WRAM`, ARM7 WRAM is `M7ER`, the
     * ARM7 I/O registers are `M7RG` (sound registers at 0x400-0x51F). Chunk 8 is the SPU (mixer + channels).
     */
    data object DESMUME : SavestateSoundSplicer() {
        private const val MAGIC = "DeSmuME SState"
        private const val FORMAT_VERSION = 12
        private const val CHUNK_ARM9_MEMORY = 4
        private const val CHUNK_ARM7_MEMORY = 60
        private const val CHUNK_SPU = 8
        private const val SPU_SIZE = 1084
        private const val SOUND_REGISTERS = 0x400
        private const val SOUND_REGISTERS_END = 0x520

        override fun locate(state: ByteArray): SoundSavestate {
            fun fail(why: String): Nothing = throw UnsupportedSavestateException("DeSmuME state: $why")
            if (state.size < 32 || state.copyOfRange(0, MAGIC.length).decodeToString() != MAGIC) fail("bad magic")
            if (le32(state, 0x10) != FORMAT_VERSION) fail("format version ${le32(state, 0x10)}, expected $FORMAT_VERSION")
            if (le32(state, 0x1C) != -1) fail("compressed")
            val chunks = HashMap<Int, StateRegion>()
            var offset = 32
            while (true) {
                if (offset + 4 > state.size) fail("no end marker")
                val id = le32(state, offset)
                if (id == -1) break
                if (offset + 8 > state.size) fail("truncated chunk header")
                val size = le32(state, offset + 4)
                if (size < 0 || offset + 8L + size > state.size) fail("chunk $id overflows")
                chunks[id] = StateRegion(offset + 8, size)
                offset += 8 + size
            }
            val arm9 = fields(state, chunks[CHUNK_ARM9_MEMORY] ?: fail("no ARM9 memory chunk"), ::fail)
            val arm7 = fields(state, chunks[CHUNK_ARM7_MEMORY] ?: fail("no ARM7 memory chunk"), ::fail)
            val spu = chunks[CHUNK_SPU] ?: fail("no SPU chunk")
            if (spu.size != SPU_SIZE) fail("SPU chunk is ${spu.size} bytes, expected $SPU_SIZE")
            val ram = arm9["WRAM"]?.takeIf { it.size == 0x400000 } ?: fail("main RAM missing or not 4 MB")
            val wram7 = arm7["M7ER"]?.takeIf { it.size == 0x10000 } ?: fail("ARM7 WRAM missing or not 64 KB")
            val io7 = arm7["M7RG"]?.takeIf { it.size == 0x10000 } ?: fail("ARM7 registers missing or not 64 KB")
            val soundRegisters = StateRegion(io7.offset + SOUND_REGISTERS, SOUND_REGISTERS_END - SOUND_REGISTERS)
            return SoundSavestate(state, ram, wram7, listOf(spu, soundRegisters))
        }

        private fun fields(state: ByteArray, chunk: StateRegion, fail: (String) -> Nothing): Map<String, StateRegion> {
            val out = HashMap<String, StateRegion>()
            var offset = chunk.offset
            while (offset < chunk.end) {
                if (offset + 12 > chunk.end) fail("truncated field header")
                val key = state.copyOfRange(offset, offset + 4).decodeToString()
                val length = le32(state, offset + 4).toLong() * le32(state, offset + 8).toLong()
                if (length < 0 || offset + 12 + length > chunk.end) fail("field $key overflows")
                out.putIfAbsent(key, StateRegion(offset + 12, length.toInt()))
                offset += 12 + length.toInt()
            }
            return out
        }
    }

    /**
     * melonDS 0.9.3 (`Savestate.cpp`): "MELN", u16 major 9, u16 minor 0, u32 length, then sections (`char magic[4],
     * u32 length including the 16-byte header, 8 bytes padding`). Section "NDSG" starts with main RAM (4 MB), shared
     * WRAM (32 KB) then ARM7 WRAM (64 KB); "SPU." holds the sound chip.
     */
    data object MELONDS : SavestateSoundSplicer() {
        private const val MAGIC = "MELN"
        private const val DMA_RUNNING = 10 * 4
        private const val DMA_SECTION_SIZE = 14 * 4

        /**
         * melonDS 0.9.3 doesn't save the DMA channels' `MRAMBurstTable` pointer: an instance that never ran a DMA on a
         * channel crashes (null pointer in `DMA::UnitTimings9_32`) when it loads a state where that channel is in the
         * middle of a burst (`Running == 1`). The priming state is [state] with those channels marked as starting a
         * burst (`Running == 2`), which sets the pointer on the first unit; after one frame of it, the shadow can load
         * [state] itself. DMA sections: 14 u32 (`DMA::DoSavestate`), `Running` is the 11th.
         */
        override fun primingState(state: ByteArray): ByteArray? {
            locate(state)
            var primed: ByteArray? = null
            for (channel in 0 until 8) {
                val section = section(state, "DMA$channel") ?: continue
                if (section.size != DMA_SECTION_SIZE || le32(state, section.offset + DMA_RUNNING) != 1) continue
                val out = primed ?: state.copyOf().also { primed = it }
                out[section.offset + DMA_RUNNING] = 2
            }
            return primed
        }

        private fun section(state: ByteArray, name: String): StateRegion? {
            var offset = 16
            val total = le32(state, 8)
            while (offset + 16 <= total) {
                val length = le32(state, offset + 4)
                if (length < 16) return null
                if (state.copyOfRange(offset, offset + 4).decodeToString() == name) return StateRegion(offset + 16, length - 16)
                offset += length
            }
            return null
        }
        private const val MAJOR = 9
        private const val MINOR = 0
        private const val NDSG_SIZE = 4293313
        private const val SPU_SIZE = 1787

        override fun locate(state: ByteArray): SoundSavestate {
            fun fail(why: String): Nothing = throw UnsupportedSavestateException("melonDS state: $why")
            if (state.size < 16 || state.copyOfRange(0, 4).decodeToString() != MAGIC) fail("bad magic")
            val major = (state[4].toInt() and 0xFF) or ((state[5].toInt() and 0xFF) shl 8)
            val minor = (state[6].toInt() and 0xFF) or ((state[7].toInt() and 0xFF) shl 8)
            if (major != MAJOR || minor != MINOR) fail("version $major.$minor, expected $MAJOR.$MINOR")
            val total = le32(state, 8)
            if (total < 16 || total > state.size) fail("bad length $total")
            val sections = HashMap<String, StateRegion>()
            var offset = 16
            while (offset + 16 <= total) {
                val magic = state.copyOfRange(offset, offset + 4)
                if (!magic.all { it in 32..126 }) fail("bad section magic at $offset")
                val length = le32(state, offset + 4)
                if (length < 16 || offset.toLong() + length > total) fail("section ${magic.decodeToString()} overflows")
                sections.putIfAbsent(magic.decodeToString(), StateRegion(offset + 16, length - 16))
                offset += length
            }
            val ndsg = sections["NDSG"] ?: fail("no NDSG section")
            if (ndsg.size != NDSG_SIZE) fail("NDSG is ${ndsg.size} bytes, expected $NDSG_SIZE")
            val spu = sections["SPU."] ?: fail("no SPU section")
            if (spu.size != SPU_SIZE) fail("SPU is ${spu.size} bytes, expected $SPU_SIZE")
            return SoundSavestate(
                state,
                mainRam = StateRegion(ndsg.offset, 0x400000),
                arm7Wram = StateRegion(ndsg.offset + 0x408000, 0x10000),
                soundChip = listOf(spu),
            )
        }
    }
}
