package dev.kotlinds.pokemonclient.libretro.sound

import dev.kotlinds.pokemonclient.console.ConsolePort

/**
 * The shadow side of music during pauses: a second console (another instance of the core, see
 * [dev.kotlinds.pokemonclient.libretro.ConsoleRole.SHADOW]) that loads the paused game and runs it with no input, so its
 * music can be played while the real game stays frozen. Its frames never reach the game: only its sound, and, at
 * resume, its sound playback state ([SoundResync]).
 *
 * Single-threaded like the console it drives.
 */
class ShadowRun(
    private val shadow: ConsolePort,
    private val resync: SoundResync,
    /** Called with true before frames whose sound must not be heard (priming), with false after. */
    private val silence: (Boolean) -> Unit = {},
) {

    /** Frames emulated since [begin]. */
    var frames = 0
        private set

    /**
     * Starts from the [paused] state of the main console (after a silent priming frame when the core needs one, see
     * [SavestateSoundSplicer.primingState]); false when the shadow rejects it.
     */
    fun begin(paused: ByteArray): Boolean {
        frames = 0
        resync.splicer.primingState(paused)?.let { priming ->
            if (!shadow.loadState(priming)) return false
            silence(true)
            try {
                shadow.step(1)
            } finally {
                silence(false)
            }
        }
        return shadow.loadState(paused)
    }

    /** Emulates one frame (its sound goes to the shadow's audio callback). */
    fun step() {
        shadow.step(1)
        frames++
    }

    /**
     * Stops at a safe frame: the current one if it is safe, else after up to [maxExtraFrames] more frames (each
     * followed by [afterFrame], e.g. real-time pacing). A shadow that played nothing has nothing to give.
     */
    fun end(maxExtraFrames: Int = MAX_EXTRA_FRAMES, afterFrame: () -> Unit = {}): ShadowEnd {
        if (frames == 0) return ShadowEnd.NotSafe(0)
        var extra = 0
        while (true) {
            val state = shadow.saveState()
            if (resync.isSafeFrame(state)) return ShadowEnd.Safe(state, frames)
            if (extra++ == maxExtraFrames) return ShadowEnd.NotSafe(frames)
            step()
            afterFrame()
        }
    }

    companion object {
        /** A safe frame comes within 3 frames (measured: at most 3 unsafe frames in a row, ~28% of frames unsafe). */
        const val MAX_EXTRA_FRAMES = 3
    }
}

/** Where the shadow stopped. */
sealed interface ShadowEnd {
    /** At a safe frame: [state] is the shadow's state there, after [frames] frames. */
    class Safe(val state: ByteArray, val frames: Int) : ShadowEnd

    /** No safe frame in reach (or nothing played): no resync. */
    data class NotSafe(val frames: Int) : ShadowEnd
}
