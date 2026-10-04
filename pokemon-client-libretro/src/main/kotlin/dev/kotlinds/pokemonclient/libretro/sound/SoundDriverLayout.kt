package dev.kotlinds.pokemonclient.libretro.sound

import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where a game's sound driver keeps its state: the addresses that music during pauses needs (see [SoundResync]).
 *
 * DS games play music with the Nitro SDK sound driver ("SND"), which runs on the ARM7 (the sub CPU): the ARM9 sends
 * it commands, and the ARM7 sequencer plays the sequences, ticking every ~5.2 ms, then writes the sound chip. Its
 * state lives in the ARM7's own work RAM (0x03800000, 64 KB), at addresses fixed by the link of the ARM7 binary.
 * So a layout belongs to one ARM7 build, recognized by the SHA-1 of the ARM7 binary in the ROM ([forRom]); a game
 * whose ARM7 binary isn't known simply has no music during pauses.
 *
 * Addresses are absolute bus addresses (ARM7 WRAM 0x038xxxxx, main RAM 0x02xxxxxx).
 */
data class SoundDriverLayout(
    /** Human-readable name of the build, for logs. */
    val name: String,
    /** SHA-1 of the ARM7 binary (ROM header 0x30 / 0x3C), lowercase hex. */
    val arm7Sha1: String,
    /**
     * The ARM7 WRAM ranges owned by the sequencer that are copied from the shadow at resume: its random generator, the
     * surround decay and original channel pan / volume, the sequence cache, and `SNDi_Work` (channels, players,
     * tracks, alarms). Sorted by address.
     */
    val sequencerRanges: List<AddressRange>,
    /** `SNDi_Work`: `SNDExChannel[16]`, then `SNDPlayer[16]` at +0x540, `SNDTrack[32]` at +0x780, alarms at +0xF80. */
    val work: Int,
    /** `SNDi_SharedWork`: the ARM7's pointer to the ARM9's `SNDSharedWork` (in main RAM). */
    val sharedWorkPointer: Int,
    /** `OSi_CurrentThreadPtr`: points at the ARM7 scheduler's "current thread" pointer. */
    val currentThreadPointer: Int,
    /** `sndThread`: the sound thread's `OSThread`. */
    val soundThread: Int,
    /** `sCommandMesgQueue`: command lists received from the ARM9, waiting for the sound thread. */
    val commandQueue: Int,
    /** `sLockChannel` / `sWeakLockChannel`: channels taken away from the sequencer (wave out, direct use). */
    val lockedChannels: List<Int>,
    /**
     * The ARM9 side of the command protocol (Nitro SDK `SND_command.c` statics), as offsets from `sSharedWork`
     * (they are linked together): `sFinishedTag` block, waiting-list queue, current tag.
     */
    val arm9Commands: Arm9CommandLayout,
) {
    /** A range of bus addresses [start, start + size). */
    data class AddressRange(val start: Int, val size: Int)

    /** Offsets, from `sSharedWork`, of the ARM9 statics of `SND_command.c`. */
    data class Arm9CommandLayout(
        /** `sWaitingCommandListQueue[9]`: command lists flushed to the ARM7, oldest first. */
        val waitingQueue: Int,
        /** `sWaitingCommandListQueueWrite`: next slot of the waiting queue. */
        val waitingWrite: Int,
        /** `sCurrentTag`: tag of the next flushed list (lists 1..sCurrentTag-1 were flushed). */
        val currentTag: Int,
        /** Number of slots of the waiting queue. */
        val waitingSlots: Int = 9,
    )

    companion object {
        /** ARM7 WRAM base address. */
        const val ARM7_WRAM = 0x03800000

        /** Main RAM base address. */
        const val MAIN_RAM = 0x02000000

        /**
         * Pokémon HeartGold (US). Addresses from pret/pokeheartgold `sub/build/ichneumon_sub.elf.xMAP` and
         * `sub/asm/sub.wram_1.s` (`u$3681`, the state of `SND_CalcRandom`) (the ARM7
         * binary's hash is the one of `sub/ichneumon_sub.sha1`) and the ARM9 `SND_CommandInit` / `SND_FlushCommand`
         * literal pools (`sSharedWork` 0x021E1AC0, statics block 0x021E1A60, waiting queue 0x021E1A84).
         */
        val HEARTGOLD_US = SoundDriverLayout(
            name = "Pokémon HeartGold (US)",
            arm7Sha1 = "1d0b3418b85fa8b5e1a9e345d3a182073cb968ac",
            sequencerRanges = listOf(
                AddressRange(0x03806AF0, 0x4), // SND_CalcRandom's state (the sequences' `random` commands)
                AddressRange(0x03806EAC, 0x24), // sSurroundDecay, sOrgPan[16], sOrgVolume[16]
                AddressRange(0x038073F0, 0x18), // seqCache
                AddressRange(0x0380740C, 0x1180), // SNDi_Work
            ),
            work = 0x0380740C,
            sharedWorkPointer = 0x03807408,
            currentThreadPointer = 0x03806C24,
            soundThread = 0x03806F40,
            commandQueue = 0x0380858C,
            lockedChannels = listOf(0x038073E8, 0x038073E4),
            arm9Commands = Arm9CommandLayout(waitingQueue = -0x3C, waitingWrite = -0x48, currentTag = -0x40),
        )

        /** Every known layout. */
        val KNOWN = listOf(HEARTGOLD_US)

        /** The layout of the ROM's ARM7 binary, or null when this build isn't supported. */
        fun forRom(rom: Path): SoundDriverLayout? = arm7Sha1(rom)?.let { hash -> KNOWN.firstOrNull { it.arm7Sha1 == hash } }

        /** SHA-1 of the ROM's ARM7 binary (offset and size from the ROM header), or null if the header is invalid. */
        fun arm7Sha1(rom: Path): String? = RandomAccessFile(rom.toFile(), "r").use { file ->
            val header = ByteArray(0x40)
            if (file.length() < 0x200) return null
            file.readFully(header)
            val offset = le32(header, 0x30).toLong() and 0xFFFFFFFFL
            val size = le32(header, 0x3C).toLong() and 0xFFFFFFFFL
            if (size <= 0 || size > 0x40000 || offset + size > file.length()) return null
            val binary = ByteArray(size.toInt())
            file.seek(offset)
            file.readFully(binary)
            MessageDigest.getInstance("SHA-1").digest(binary).joinToString("") { "%02x".format(it) }
        }

        internal fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
