package me.nathanfallet.aiplayspokemon.emulator

import kotlinx.coroutines.flow.StateFlow

/**
 * What the rest of the app (UI, AI agent) knows about an emulator.
 *
 * This is the seam between "an emulated console" and "everything that uses it": the libretro
 * implementation ([me.nathanfallet.aiplayspokemon.emulator.libretro.LibretroEmulator]) is one way
 * to provide it, but another backend (a socket bridge to a standalone emulator, a GDB stub, a
 * pure-Kotlin emulator, a fake for tests...) only has to implement this interface.
 *
 * Implementations own their emulation thread: every method here is safe to call from any thread.
 */
interface Emulator : AutoCloseable {

    /** Static description of the running emulator (name, screens...). */
    val info: EmulatorInfo

    /** The latest frame produced by the console, or null before the first one. */
    val frames: StateFlow<Frame?>

    /** Running/paused state, speed and frame counter. */
    val status: StateFlow<EmulatorStatus>

    /** Starts emulating (the emulator is created paused so callers can wire everything first). */
    fun start()

    fun pause()
    fun resume()

    /** Runs as fast as possible (no frame pacing, no audio) when [enabled]. */
    fun setFastForward(enabled: Boolean)

    /** Silences the game audio. */
    fun setMuted(muted: Boolean)

    /**
     * Sets the buttons currently held by one input [source].
     * The console sees the union of all sources, so a human can always help (or fight) the agent.
     */
    fun setButtons(source: InputSource, buttons: Set<Button>)

    /**
     * Touches the touch screen at a position expressed as a fraction (0..1) of the whole frame,
     * or releases it when [position] is null. Emulators without a touch screen ignore it.
     */
    fun touch(position: Pair<Float, Float>?)

    /** Suspends until [count] more frames have been emulated (time passes only while running). */
    suspend fun awaitFrames(count: Int)

    /** Copy of the console's main RAM, taken between two frames so it is consistent. */
    suspend fun readMainRam(): ByteArray

    /** Saves the full machine state to [slot] / restores it. Returns false when unsupported or missing. */
    suspend fun saveState(slot: Int): Boolean
    suspend fun loadState(slot: Int): Boolean

    suspend fun reset()
}

/** Who is pressing buttons. */
enum class InputSource { HUMAN, AGENT }

/** Buttons of a Nintendo DS (a superset of the GBA's, so it also fits GBA/GB cores). */
enum class Button { A, B, X, Y, L, R, START, SELECT, UP, DOWN, LEFT, RIGHT }

data class EmulatorInfo(
    /** Emulator name and version, e.g. "melonDS 0.9.3". */
    val name: String,
    /** Frames per second of the emulated console (~59.83 for the DS). */
    val fps: Double,
)

data class EmulatorStatus(
    val running: Boolean = false,
    val fastForward: Boolean = false,
    val muted: Boolean = false,
    val frameCount: Long = 0,
    /** Measured emulation speed, in frames per second. */
    val measuredFps: Double = 0.0,
)

/** One video frame: ARGB pixels, row-major. For the DS, both screens are stacked (256x384). */
class Frame(val width: Int, val height: Int, val pixels: IntArray)
