package dev.kotlinds.pokemonclient.libretro.sound

/**
 * The Nitro SDK sound driver as found in one save state: what the resync guards need (see [SoundResync]).
 *
 * Structures from the Nitro SDK (pret/pokediamond `SND_work_shared.h`, `SND_seq_shared.h`): `SNDPlayer` is 0x24 bytes
 * (flags: bit 0 active, bit 2 paused; track indexes at +0x08; bank at +0x20), `SNDTrack` is 0x40
 * bytes (sequence data base at +0x24).
 */
data class SoundDriverState(
    /** The active sequence players (idle players keep other flags set, e.g. bit 1). */
    val players: List<Player>,
    /** True when the ARM7 was running the sound thread when the state was taken (mid-tick or mid-command). */
    val soundThreadRunning: Boolean,
    /** Command lists received by the ARM7 and not processed yet. */
    val queuedCommandLists: Int,
    /** Channels locked away from the sequencer (bit mask of both lock words). */
    val lockedChannels: Int,
    /**
     * Commands the ARM9 flushed that the ARM7 hasn't finished (still in the PXI FIFO, queued, or being processed), by
     * id (`SND_CMD_*`); null when they couldn't be read (inconsistent tags or pointers).
     */
    val commandsInFlight: List<Int>?,
) {
    /**
     * One sequence player. Two equal players play the same song: same player, flags, bank and sequence data for every
     * track (positions inside the sequence aren't part of it).
     */
    data class Player(
        /** SND player number (0-15; not the game's archive player number). */
        val index: Int,
        /** Flags: bit 0 active, bit 2 paused. */
        val flags: Int,
        /** Address of the instrument bank in main RAM. */
        val bank: Int,
        /** The player's tracks: (track index, address of the track's sequence data). */
        val tracks: List<Pair<Int, Int>>,
    )

    /** No sound command in flight and the sound thread idle: the sequencer state is consistent and complete. */
    val isSafeFrame: Boolean get() = !soundThreadRunning && queuedCommandLists == 0

    companion object {
        private const val PLAYERS = 0x540
        private const val PLAYER_SIZE = 0x24
        private const val TRACKS = 0x780
        private const val TRACK_SIZE = 0x40
        private const val NO_TRACK = 0xFF
        private const val ACTIVE = 0x1
        private const val PAUSED = 0x4
        private const val MAX_COMMANDS = 256

        /** Decodes the driver of [state] with [layout]. */
        fun read(state: SoundSavestate, layout: SoundDriverLayout): SoundDriverState {
            val work = layout.work
            val wram = state.bytes
            fun byte(address: Int) = wram[state.arm7Wram.offset + address - SoundDriverLayout.ARM7_WRAM].toInt() and 0xFF
            val players = (0 until 16).mapNotNull { i ->
                val player = work + PLAYERS + i * PLAYER_SIZE
                val flags = byte(player)
                if ((flags and ACTIVE) == 0) return@mapNotNull null
                val tracks = (0 until 16).map { byte(player + 8 + it) }.filter { it != NO_TRACK }.map { t ->
                    t to (if (t < 32) state.arm7(work + TRACKS + t * TRACK_SIZE + 0x24) else 0)
                }
                Player(i, flags and (ACTIVE or PAUSED), state.arm7(player + 0x20), tracks)
            }
            val current = state.arm7(layout.currentThreadPointer).let { pointer ->
                if (pointer - SoundDriverLayout.ARM7_WRAM in 0..0xFFFC) state.arm7(pointer) else 0
            }
            return SoundDriverState(
                players = players,
                soundThreadRunning = current == layout.soundThread,
                queuedCommandLists = state.arm7(layout.commandQueue + 0x1C),
                lockedChannels = layout.lockedChannels.fold(0) { mask, address -> mask or state.arm7(address) },
                commandsInFlight = commandsInFlight(state, layout),
            )
        }

        /**
         * The ids of the commands of every list flushed by the ARM9 (`sCurrentTag - 1` lists so far) that the ARM7
         * hasn't finished (`SNDSharedWork.finishedCommandTag`): the newest entries of the ARM9's waiting queue.
         */
        private fun commandsInFlight(state: SoundSavestate, layout: SoundDriverLayout): List<Int>? {
            val shared = state.arm7(layout.sharedWorkPointer)
            val arm9 = layout.arm9Commands
            val addresses = listOf(shared, shared + arm9.currentTag, shared + arm9.waitingWrite, shared + arm9.waitingQueue)
            if (!addresses.all(state::inMainRam)) return null
            val finished = state.main(shared)
            val flushed = state.main(shared + arm9.currentTag) - 1
            val pending = flushed - finished
            val write = state.main(shared + arm9.waitingWrite)
            if (pending !in 0 until arm9.waitingSlots || write !in 0 until arm9.waitingSlots) return null
            val ids = mutableListOf<Int>()
            for (k in 1..pending) {
                var command = state.main(shared + arm9.waitingQueue + ((write - k + arm9.waitingSlots) % arm9.waitingSlots) * 4)
                var count = 0
                while (command != 0) {
                    if (!state.inMainRam(command) || !state.inMainRam(command + 4) || ++count > MAX_COMMANDS) return null
                    ids += state.main(command + 4)
                    command = state.main(command)
                }
            }
            return ids
        }
    }
}
