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

    /** The state given to [begin]. */
    private var paused: ByteArray? = null

    /**
     * Starts from the [paused] state of the main console (after a silent priming frame when the core needs one, see
     * [SavestateSoundSplicer.primingState]); false when the shadow rejects it.
     */
    fun begin(paused: ByteArray): Boolean {
        frames = 0
        this.paused = paused
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
     *
     * With [settleFrames] > 0, a safe frame where sounds the shadow's game started during the pause still play next
     * to the music ([SoundResync.soundsStartedPlayAtEnd]: a sound effect, a cry; the resync would be refused) isn't
     * taken at once: the shadow plays on, up to that many frames, until a safe frame where they ended; past that, the
     * last safe frame seen.
     */
    fun end(maxExtraFrames: Int = MAX_EXTRA_FRAMES, afterFrame: () -> Unit = {}, settleFrames: Int = 0): ShadowEnd {
        if (frames == 0) return ShadowEnd.NotSafe(0)
        var unsafe = 0
        var waited = 0
        var lastSafe: ShadowEnd.Safe? = null
        while (true) {
            val state = shadow.saveState()
            if (resync.isSafeFrame(state)) {
                unsafe = 0
                val safe = ShadowEnd.Safe(state, frames)
                val start = paused
                if (waited >= settleFrames || start == null || !resync.soundsStartedPlayAtEnd(start, state)) return safe
                lastSafe = safe
            } else if (unsafe++ == maxExtraFrames) {
                return lastSafe ?: ShadowEnd.NotSafe(frames)
            }
            if (lastSafe != null) waited++
            step()
            afterFrame()
        }
    }

    companion object {
        /** A safe frame comes within 3 frames (measured: at most 3 unsafe frames in a row, ~28% of frames unsafe). */
        const val MAX_EXTRA_FRAMES = 3

        /**
         * A good `settleFrames` for [end] (~0.5 s): most sound effects and the end of a cry fit (a wild battle's
         * intro paused 5 s: 76 -> 88 resyncs of 91 pauses).
         */
        const val SETTLE_FRAMES = 30
    }
}

/** Where the shadow stopped. */
sealed interface ShadowEnd {
    /** At a safe frame: [state] is the shadow's state there, after [frames] frames. */
    class Safe(val state: ByteArray, val frames: Int) : ShadowEnd

    /** No safe frame in reach (or nothing played): no resync. */
    data class NotSafe(val frames: Int) : ShadowEnd
}
