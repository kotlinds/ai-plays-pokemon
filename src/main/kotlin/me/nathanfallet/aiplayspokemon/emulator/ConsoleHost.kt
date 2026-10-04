package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.runtime.Interruption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import me.nathanfallet.aiplayspokemon.emulator.audio.AudioPlayer
import me.nathanfallet.aiplayspokemon.emulator.audio.ShadowAudio
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Owns the emulated console on one dedicated thread, and decides who drives it.
 *
 * The console ([LibretroConsole]) only advances when someone calls `step`. The host is that someone, and at any
 * time exactly one [Driver] is in charge:
 * - [Driver.Human]: the game runs freely in real time, with the keyboard / mouse inputs of the person watching
 *   (whether someone actually plays is [humanActivity], what agents are refused on);
 * - [Driver.Agent]: an agent action holds a [lease] and steps the console itself, frame by frame (the free run is
 *   suspended meanwhile, so nothing happens that the action doesn't see);
 * - [Driver.Idle]: nobody, the game is frozen (paused by the user, or "pause while thinking").
 *
 * A human input during an agent action interrupts that action ([Interruption.HUMAN]): the human always wins.
 *
 * Frames are paced for display (real time, unless fast-forward) after every frame, agent ones included, so
 * the picture and the sound stay smooth. Pacing never changes what the game computes.
 *
 * While nobody drives ([Driver.Idle]), the game's music goes on ([ShadowAudio], "Music during pauses"): a shadow
 * console plays it, and the game resumes in sync with it. The game itself never moves during a pause.
 *
 * It also implements the older [Emulator] interface used by the UI.
 */
class ConsoleHost(
    private val spec: LibretroCoreSpec,
    private val romPath: Path,
    private val dataDirectory: Path,
) : Emulator {

    /** Who drives the console right now. */
    sealed interface Driver {
        data object Idle : Driver
        data object Human : Driver
        data class Agent(val label: String) : Driver
    }

    private val tasks = ConcurrentLinkedQueue<(LibretroConsole) -> Unit>()
    private val humanButtons = MutableStateFlow<Set<Button>>(emptySet())

    @Volatile private var humanTouch: TouchPoint? = null

    /** The human's real inputs (not the free run): agents are refused while they play. */
    val humanActivity = HumanActivity()
    @Volatile private var closed = false
    @Volatile private var leaseActive = false

    private val _frames = MutableStateFlow<Frame?>(null)
    override val frames: StateFlow<Frame?> = _frames.asStateFlow()

    private val _status = MutableStateFlow(EmulatorStatus())
    override val status: StateFlow<EmulatorStatus> = _status.asStateFlow()

    private val _driver = MutableStateFlow<Driver>(Driver.Idle)

    /** Who drives the console right now (shown in the UI and to agents). */
    val driver: StateFlow<Driver> = _driver.asStateFlow()

    /**
     * Called on the console thread after every emulated frame (free play and agent actions alike), with the frame
     * number and a way to read the main RAM of that frame. Used by the event recorder.
     */
    @Volatile
    var frameListener: ((frame: Long, memory: () -> Memory) -> Unit)? = null

    /** Called when the human starts pressing buttons or touching the screen. */
    @Volatile
    var humanInputListener: (() -> Unit)? = null

    override lateinit var info: EmulatorInfo
        private set

    private val saveStateDirectory = dataDirectory.resolve("states")
    private lateinit var audio: AudioPlayer
    private lateinit var pacer: FramePacer
    private lateinit var shadowAudio: ShadowAudio

    /** The "Music during pauses" setting, until [shadowAudio] exists (it is created on the console thread). */
    @Volatile private var musicDuringPauses = true

    private val ready = CountDownLatch(1)
    private var startupError: Throwable? = null

    private val consoleThread = thread(name = "console", isDaemon = true) {
        val console = try {
            LibretroConsole(spec, romPath, dataDirectory, onVideo = { _frames.value = it }, onAudio = { s, n -> audio.play(s, n) }).also {
                audio = AudioPlayer(it.sampleRate)
                info = EmulatorInfo(name = it.coreName, fps = it.fps)
                pacer = FramePacer(it.fps)
                shadowAudio = ShadowAudio(spec, romPath, dataDirectory, audio, it.fps).apply {
                    enabled = musicDuringPauses
                    soundOn = !status.value.muted && !status.value.fastForward
                    unsupportedReason?.let { why -> println("[music during pauses] unavailable: $why") }
                }
            }
        } catch (error: Throwable) {
            startupError = error
            ready.countDown()
            return@thread
        }
        ready.countDown()
        try {
            loop(console)
        } finally {
            shadowAudio.close()
            console.close() // also makes the core write the in-game save (.sav) to disk
            audio.close()
        }
    }

    init {
        ready.await()
        startupError?.let { throw IllegalStateException("Failed to start ${spec.name}", it) }
    }

    /** Free run: tasks (including agent leases) first, then one real-time frame with the human's inputs. */
    private fun loop(console: LibretroConsole) {
        while (!closed) {
            while (true) tasks.poll()?.invoke(console) ?: break
            if (!status.value.running) {
                _driver.value = Driver.Idle
                shadowAudio.onIdle(console)
                Thread.sleep(5)
                pacer.reset()
                continue
            }
            _driver.value = Driver.Human
            shadowAudio.beforeMainFrame(console)
            console.step(1, InputFrame(humanButtons.value, humanTouch))
            afterFrame(console)
        }
    }

    /** Bookkeeping after every emulated frame, free run or agent: listeners, counters, then pacing. */
    private fun afterFrame(console: LibretroConsole) {
        frameListener?.let { listener -> runCatching { listener(console.frame) { mainRam(console) } } }
        val measured = pacer.frameDone(fastForward = status.value.fastForward)
        _status.update { it.copy(frameCount = it.frameCount + 1, measuredFps = measured ?: it.measuredFps) }
    }

    /**
     * Gives [block] exclusive control of the console: it runs on the console thread, between two frames, and
     * every frame it steps is emulated right away. Waits while the game is paused by the user.
     *
     * Throws [dev.kotlinds.pokemonclient.runtime.ActionInterruptedException] when a human presses a button
     * or touches the screen during the action.
     */
    suspend fun <T> lease(label: String, inputProbe: InputProbe, block: ActionScope.() -> T): T {
        userPaused.first { !it }
        return onConsoleThread { console ->
            leaseActive = true
            _driver.value = Driver.Agent(label)
            try {
                val scope = ActionScope(
                    port = resumingPort(console),
                    inputProbe = inputProbe,
                    interruption = {
                        when {
                            closed -> Interruption.CANCELLED
                            humanButtons.value.isNotEmpty() || humanTouch != null -> Interruption.HUMAN
                            else -> null
                        }
                    },
                    onFrame = { afterFrame(console) },
                )
                scope.block()
            } finally {
                leaseActive = false
            }
        }
    }

    /** [console], resyncing the music of a pause ([ShadowAudio]) before the first frame an action emulates. */
    private fun resumingPort(console: LibretroConsole): ConsolePort = object : ConsolePort by console {
        override fun step(frames: Int, input: InputFrame) {
            shadowAudio.beforeMainFrame(console)
            console.step(frames, input)
        }
    }

    /**
     * Reads the game (the main RAM of the current frame) on the console thread, without running any frame: unlike
     * [lease], it doesn't wait while the user has paused the game, so the state can always be looked at.
     */
    suspend fun <T> observe(block: (Memory) -> T): T = onConsoleThread { console -> block(mainRam(console)) }

    /** True while the person watching has paused the game (agent actions are refused, see [lease]). */
    val isUserPaused: Boolean get() = userPaused.value

    /** Runs [block] on the console thread between two frames and returns its result. */
    private suspend fun <T> onConsoleThread(block: (LibretroConsole) -> T): T {
        val result = CompletableDeferred<T>()
        tasks.add { console -> result.completeWith(runCatching { block(console) }) }
        return result.await()
    }

    // region Emulator (used by the UI)

    private val userPaused = MutableStateFlow(false)

    /**
     * Pause asked by the person watching (the pause button), as opposed to the agent's "pause while thinking":
     * agent actions wait while it is set.
     */
    fun setUserPaused(paused: Boolean) {
        userPaused.value = paused
        _status.update { it.copy(running = !paused) }
    }

    override fun start() = resume()

    override fun pause() = _status.update { it.copy(running = false) }

    /** Resumes the free run, unless the person watching paused the game (their pause wins). */
    override fun resume() {
        if (!userPaused.value) _status.update { it.copy(running = true) }
    }

    override fun setFastForward(enabled: Boolean) {
        _status.update { it.copy(fastForward = enabled) }
        updateAudio()
    }

    override fun setMuted(muted: Boolean) {
        _status.update { it.copy(muted = muted) }
        updateAudio()
    }

    /** Audio is silent when muted, and while fast-forwarding (it would be sped-up noise): no music during pauses then. */
    private fun updateAudio() {
        audio.muted = status.value.muted || status.value.fastForward
        shadowAudio.soundOn = !audio.muted
    }

    /**
     * "Music during pauses" (on by default): while the game is paused, its music goes on, and the game resumes with the
     * music where it got to. Only when the sound is on.
     */
    fun setMusicDuringPauses(enabled: Boolean) {
        musicDuringPauses = enabled
        shadowAudio.enabled = enabled
    }

    /** Why music during pauses can't work here (ROM, core, platform), or null when it can. */
    val musicDuringPausesUnavailable: String? get() = shadowAudio.unsupportedReason

    override fun setButtons(source: InputSource, buttons: Set<Button>) {
        // Agents don't hold buttons anymore: they act through a lease. Only the human's keys are tracked here.
        if (source != InputSource.HUMAN) return
        if (humanButtons.value.isEmpty() && buttons.isNotEmpty()) humanInputListener?.invoke()
        humanButtons.value = buttons
        humanActivity.keys(held = buttons.isNotEmpty())
    }

    override fun touch(position: Pair<Float, Float>?) {
        // The UI gives a position as a fraction of the whole frame (both screens); only the bottom one is touchable.
        humanTouch = position?.let { (fx, fy) ->
            val y = (fy * 2 - 1) * SCREEN_HEIGHT
            if (y < 0) null else TouchPoint((fx * SCREEN_WIDTH).toInt().coerceIn(0, SCREEN_WIDTH - 1), y.toInt().coerceIn(0, SCREEN_HEIGHT - 1))
        }
        humanActivity.touch(touching = humanTouch != null)
    }

    override suspend fun awaitFrames(count: Int) {
        val target = status.value.frameCount + count
        status.first { it.frameCount >= target }
    }

    override suspend fun readMainRam(): ByteArray = onConsoleThread { console ->
        ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }
    }

    private var ramBuffer = ByteArray(0)

    /** The main RAM of the current frame, in a buffer reused from frame to frame (console thread only). */
    private fun mainRam(console: LibretroConsole): Memory {
        val size = console.memorySize(MemoryRegion.MAIN_RAM)
        if (ramBuffer.size != size) ramBuffer = ByteArray(size)
        console.read(MemoryRegion.MAIN_RAM, 0, size, ramBuffer)
        return RamMemory(ramBuffer)
    }

    override suspend fun saveState(slot: Int): Boolean = onConsoleThread { console ->
        Files.createDirectories(saveStateDirectory)
        Files.write(stateFile(slot), console.saveState())
        true
    }

    override suspend fun loadState(slot: Int): Boolean = onConsoleThread { console ->
        shadowAudio.discard() // the paused game is replaced: its shadow's music isn't the new one's
        val file = stateFile(slot)
        Files.exists(file) && console.loadState(Files.readAllBytes(file))
    }

    override suspend fun reset() = onConsoleThread { console ->
        shadowAudio.discard()
        console.reset()
    }

    override fun close() {
        closed = true
        consoleThread.join(2_000)
    }

    // endregion

    private fun stateFile(slot: Int) = saveStateDirectory.resolve("${romPath.fileName}.state$slot")

    private companion object {
        const val SCREEN_WIDTH = 256
        const val SCREEN_HEIGHT = 192
    }
}

/**
 * Real-time pacing for display: sleeps after each frame so the game runs at its native speed, and measures
 * the actual speed. Only affects timing, never what the game computes.
 */
internal class FramePacer(fps: Double) {
    private val frameNanos = (1_000_000_000 / fps).toLong()
    private var nextFrameAt = System.nanoTime()
    private var windowStart = System.nanoTime()
    private var framesInWindow = 0

    fun reset() {
        nextFrameAt = System.nanoTime()
    }

    /** Call after each frame; returns the measured fps every half second, null otherwise. */
    fun frameDone(fastForward: Boolean): Double? {
        framesInWindow++
        val now = System.nanoTime()
        val measured = if (now - windowStart >= 500_000_000) {
            (framesInWindow * 1e9 / (now - windowStart)).also { windowStart = now; framesInWindow = 0 }
        } else null
        if (fastForward) {
            nextFrameAt = now
        } else {
            nextFrameAt += frameNanos
            val sleep = nextFrameAt - System.nanoTime()
            if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            else if (sleep < -frameNanos * 5) nextFrameAt = System.nanoTime() // too late: don't try to catch up
        }
        return measured
    }
}
