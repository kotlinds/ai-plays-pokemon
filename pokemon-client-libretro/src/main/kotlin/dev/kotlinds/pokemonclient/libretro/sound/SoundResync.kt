package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.MemoryRegion

/**
 * Music during pauses, the resync: when a paused game resumes, gives it the sound playback state of the shadow
 * console that kept playing the music meanwhile, so the music goes on instead of jumping back to where the pause
 * started.
 *
 * The main console stays frozen during the pause. A second instance of the core (the shadow) loads the paused state
 * and runs it with no input, and its sound is played. At resume, the shadow stops at a safe frame
 * ([SoundDriverState.isSafeFrame]), and its sequencer state (ARM7 WRAM ranges of [SoundDriverLayout]) and sound
 * chip state are copied into the paused state, which the main console then loads. That is the only change to the
 * game: everything else, main RAM first, is the paused state's, byte for byte, and [apply] checks it.
 *
 * Every guard failing gives a [ResyncRefusal]: the game then resumes from its paused state exactly as without music
 * during pauses (the music jumps back).
 */
class SoundResync(
    /** Where the game's sound driver lives. */
    val layout: SoundDriverLayout,
    /** The core's save state format. */
    val splicer: SavestateSoundSplicer,
) {

    /** Whether the shadow's [state] is a frame where the sequencer state may be taken. */
    fun isSafeFrame(state: ByteArray): Boolean =
        runCatching { SoundDriverState.read(splicer.locate(state), layout).isSafeFrame }.getOrDefault(false)

    /**
     * Runs every guard on the [paused] state and the shadow's state at its end ([shadowEnd], a safe frame), and splices
     * them: the paused state with the shadow's sound playback state.
     */
    fun splice(paused: ByteArray, shadowEnd: ByteArray): ResyncDecision {
        val (p, s) = try {
            splicer.locate(paused) to splicer.locate(shadowEnd)
        } catch (error: UnsupportedSavestateException) {
            return ResyncDecision.Refused(ResyncRefusal.UnsupportedState(error.message.orEmpty()))
        }
        if (!p.sameLayoutAs(s)) return ResyncDecision.Refused(ResyncRefusal.UnsupportedState("paused and shadow states have different layouts"))
        val atPause = SoundDriverState.read(p, layout)
        val atEnd = SoundDriverState.read(s, layout)
        guards(atPause, atEnd)?.let { return ResyncDecision.Refused(it) }

        val spliced = splicer.splice(p, s, layout)
        // Defensive: the splice changes nothing but the sound playback state; main RAM above all.
        val allowed = splicer.splicedRegions(p, layout).sortedBy { it.offset }
        if (!equalOutside(paused, spliced, allowed) || !equalRange(paused, spliced, p.mainRam)) {
            return ResyncDecision.Refused(ResyncRefusal.SpliceTouchedGameState)
        }
        return ResyncDecision.Spliced(spliced, atPause, atEnd)
    }

    /** The guards, in order; null when they all pass. */
    internal fun guards(atPause: SoundDriverState, atEnd: SoundDriverState): ResyncRefusal? = when {
        atPause.soundThreadRunning -> ResyncRefusal.SoundThreadRunningAtPause
        atPause.commandsInFlight == null -> ResyncRefusal.UnreadableCommands
        atPause.commandsInFlight.any { it !in IDEMPOTENT_COMMANDS } ->
            ResyncRefusal.CommandsInFlightAtPause(atPause.commandsInFlight.filter { it !in IDEMPOTENT_COMMANDS })
        !atEnd.isSafeFrame -> ResyncRefusal.ShadowNotAtSafeFrame
        atPause.players.size != 1 || atEnd.players.size != 1 -> ResyncRefusal.NotOnlyMusic(atPause.players.size, atEnd.players.size)
        atPause.players != atEnd.players -> ResyncRefusal.SongChanged
        atPause.lockedChannels != 0 || atEnd.lockedChannels != 0 -> ResyncRefusal.LockedChannels(atPause.lockedChannels or atEnd.lockedChannels)
        else -> null
    }

    /**
     * Loads the [decision]'s spliced state into the [main] console, paused on [paused], making sure its main RAM
     * doesn't change: it must equal the paused state's before (nothing ran during the pause) and after loading;
     * otherwise the paused state is loaded back ([ResyncResult.Aborted]).
     */
    fun apply(main: ConsolePort, paused: ByteArray, decision: ResyncDecision.Spliced): ResyncResult {
        val ram = splicer.locate(paused).mainRam
        val buffer = ByteArray(main.memorySize(MemoryRegion.MAIN_RAM))
        fun mainRamIsPaused(): Boolean {
            if (buffer.size != ram.size) return false
            main.read(MemoryRegion.MAIN_RAM, 0, buffer.size, buffer)
            return buffer.contentEquals(paused, ram.offset)
        }
        if (!mainRamIsPaused()) return ResyncResult.Refused(ResyncRefusal.MainRamChangedDuringPause)
        if (!main.loadState(decision.state)) {
            main.loadState(paused)
            return ResyncResult.Aborted("the core rejected the spliced state")
        }
        if (!mainRamIsPaused()) {
            val restored = main.loadState(paused) && mainRamIsPaused()
            return ResyncResult.Aborted("main RAM changed after loading the spliced state (paused state restored: $restored)")
        }
        return ResyncResult.Resynced(decision.atPause.players.single())
    }

    /**
     * The whole resume: splices the [paused] state with the shadow's [end] and loads it into [main] ([splice] then
     * [apply]). Anything but [ResyncResult.Resynced] leaves [main] on its paused state.
     */
    fun resume(main: ConsolePort, paused: ByteArray, end: ShadowEnd): ResyncResult = when (end) {
        is ShadowEnd.NotSafe -> ResyncResult.Refused(if (end.frames == 0) ResyncRefusal.NothingPlayed else ResyncRefusal.ShadowNotAtSafeFrame)
        is ShadowEnd.Safe -> when (val decision = splice(paused, end.state)) {
            is ResyncDecision.Refused -> ResyncResult.Refused(decision.reason)
            is ResyncDecision.Spliced -> apply(main, paused, decision)
        }
    }

    companion object {
        /**
         * Commands the ARM7 may run twice with the same outcome (they set absolute values): those in flight when the
         * pause started were already run by the shadow, and the main console runs them again after the resync.
         * PLAYER_PARAM, TRACK_PARAM, ALLOCATABLE_CHANNEL, PLAYER_LOCAL_VAR, PLAYER_GLOBAL_VAR, CHANNEL_VOLUME,
         * CHANNEL_PAN, SURROUND_DECAY, MASTER_VOLUME, MASTER_PAN, OUTPUT_SELECTOR, READ_DRIVER_INFO
         * (pret/pokediamond `SND_command_shared.h`). Starting, stopping or muting a sequence, timers and channel setups
         * are not.
         */
        val IDEMPOTENT_COMMANDS = setOf(0x06, 0x07, 0x09, 0x0A, 0x0B, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x21)

        private fun ByteArray.contentEquals(other: ByteArray, otherOffset: Int): Boolean =
            java.util.Arrays.equals(this, 0, size, other, otherOffset, otherOffset + size)

        private fun equalRange(a: ByteArray, b: ByteArray, region: StateRegion): Boolean =
            java.util.Arrays.equals(a, region.offset, region.end, b, region.offset, region.end)

        /** [a] and [b] (same size) are equal everywhere except in [allowed] (sorted, non-overlapping). */
        private fun equalOutside(a: ByteArray, b: ByteArray, allowed: List<StateRegion>): Boolean {
            if (a.size != b.size) return false
            var from = 0
            for (region in allowed) {
                if (!java.util.Arrays.equals(a, from, region.offset, b, from, region.offset)) return false
                from = region.end
            }
            return java.util.Arrays.equals(a, from, a.size, b, from, b.size)
        }
    }
}

/** What [SoundResync.splice] decided. */
sealed interface ResyncDecision {
    /** The guards passed: [state] is the paused state with the shadow's sound playback state. */
    class Spliced(val state: ByteArray, val atPause: SoundDriverState, val atEnd: SoundDriverState) : ResyncDecision

    /** A guard failed: resume from the paused state. */
    data class Refused(val reason: ResyncRefusal) : ResyncDecision
}

/** The outcome of a resume with music during pauses. */
sealed interface ResyncResult {
    /** The game resumed with the shadow's music state, main RAM verified identical. */
    data class Resynced(val music: SoundDriverState.Player) : ResyncResult

    /** Nothing was loaded: the game resumes from its paused state, as without music during pauses. */
    data class Refused(val reason: ResyncRefusal) : ResyncResult

    /** The spliced state was loaded but failed verification: the paused state was loaded back. */
    data class Aborted(val why: String) : ResyncResult
}

/** Why the music couldn't be resynced (the game then resumes as without music during pauses). */
sealed interface ResyncRefusal {
    /** Short explanation for logs. */
    val message: String

    /** A save state of an unknown format, version or layout. */
    data class UnsupportedState(val detail: String) : ResyncRefusal {
        override val message get() = "unsupported save state ($detail)"
    }

    /** The pause started while the ARM7 was running the sound thread: its registers hold sequencer values. */
    data object SoundThreadRunningAtPause : ResyncRefusal {
        override val message get() = "the sound thread was running when the pause started"
    }

    /** The ARM9's command bookkeeping couldn't be read. */
    data object UnreadableCommands : ResyncRefusal {
        override val message get() = "the sound commands in flight couldn't be read"
    }

    /** Commands in flight at the pause that can't run twice (they would restart / stop something). */
    data class CommandsInFlightAtPause(val ids: List<Int>) : ResyncRefusal {
        override val message get() = "sound commands in flight at the pause: ${ids.joinToString { "0x%02X".format(it) }}"
    }

    /** The shadow didn't reach a safe frame in time. */
    data object ShadowNotAtSafeFrame : ResyncRefusal {
        override val message get() = "the shadow didn't reach a safe frame"
    }

    /** Not exactly one sequence (the music) playing, at the pause or at the shadow's end (sound effects, cries...). */
    data class NotOnlyMusic(val atPause: Int, val atEnd: Int) : ResyncRefusal {
        override val message get() = "not only the music playing (players: $atPause at the pause, $atEnd at the end)"
    }

    /** The song changed during the pause (the game is driving the music: the main console must do it itself). */
    data object SongChanged : ResyncRefusal {
        override val message get() = "the song changed during the pause"
    }

    /** Channels locked away from the sequencer (wave out...). */
    data class LockedChannels(val mask: Int) : ResyncRefusal {
        override val message get() = "locked sound channels 0x%04X".format(mask)
    }

    /** Defensive check: the splice changed something else than the sound playback state. */
    data object SpliceTouchedGameState : ResyncRefusal {
        override val message get() = "the splice changed bytes outside the sound state"
    }

    /** The main console's RAM isn't the paused state's anymore (something ran or was loaded meanwhile). */
    data object MainRamChangedDuringPause : ResyncRefusal {
        override val message get() = "the main RAM changed during the pause"
    }

    /** The shadow couldn't run (failed to load the paused state, or crashed). */
    data class ShadowFailed(val detail: String) : ResyncRefusal {
        override val message get() = "the shadow failed ($detail)"
    }

    /** The shadow didn't emulate any frame: nothing to resync. */
    data object NothingPlayed : ResyncRefusal {
        override val message get() = "the shadow played nothing"
    }
}
