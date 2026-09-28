package me.nathanfallet.aiplayspokemon.emulator.libretro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.emulator.EmulatorInfo
import me.nathanfallet.aiplayspokemon.emulator.EmulatorStatus
import me.nathanfallet.aiplayspokemon.emulator.Frame
import me.nathanfallet.aiplayspokemon.emulator.InputSource
import me.nathanfallet.aiplayspokemon.emulator.audio.AudioPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * [Emulator] implementation backed by a libretro core running on a dedicated thread.
 *
 * Threading model: the [LibretroCore] is only ever touched by the emulation thread. Other threads
 * talk to it by posting tasks ([onEmulationThread]) that run between two frames. This keeps the
 * core single-threaded (as libretro requires) and gives consistent RAM snapshots.
 */
class LibretroEmulator(
    private val spec: LibretroCoreSpec,
    private val romPath: Path,
    private val dataDirectory: Path,
) : Emulator {

    private val tasks = ConcurrentLinkedQueue<(LibretroCore) -> Unit>()
    private val heldButtons = ConcurrentHashMap<InputSource, Set<Button>>()

    @Volatile private var touchPosition: Pair<Float, Float>? = null
    @Volatile private var running = false
    @Volatile private var fastForward = false
    @Volatile private var closed = false

    private val _frames = MutableStateFlow<Frame?>(null)
    override val frames: StateFlow<Frame?> = _frames.asStateFlow()

    private val _status = MutableStateFlow(EmulatorStatus())
    override val status: StateFlow<EmulatorStatus> = _status.asStateFlow()

    override lateinit var info: EmulatorInfo
        private set

    private val saveStateDirectory = dataDirectory.resolve("states")
    private lateinit var audio: AudioPlayer

    /** Released once the core is loaded (or failed to), so the constructor can report errors. */
    private val ready = CountDownLatch(1)
    private var startupError: Throwable? = null

    private val emulationThread = thread(name = "emulation", isDaemon = true) {
        val core = try {
            createCore()
        } catch (error: Throwable) {
            startupError = error
            ready.countDown()
            return@thread
        }
        ready.countDown()
        try {
            loop(core)
        } finally {
            core.close() // also makes the core flush the in-game save (.sav) to disk
            audio.close()
        }
    }

    init {
        ready.await()
        startupError?.let { throw IllegalStateException("Failed to start ${spec.name}", it) }
    }

    private fun createCore(): LibretroCore {
        val coreFile = spec.resolve(dataDirectory.resolve("cores"))
        val systemDirectory = Files.createDirectories(dataDirectory.resolve("system"))
        val saveDirectory = Files.createDirectories(dataDirectory.resolve("saves"))
        val core = LibretroCore(
            corePath = coreFile,
            systemDirectory = systemDirectory,
            saveDirectory = saveDirectory,
            options = spec.options,
            video = { _frames.value = it },
            audio = { samples, count -> audio.play(samples, count) },
            input = ::inputState,
        )
        core.loadGame(romPath)
        audio = AudioPlayer(core.avInfo.timing.sample_rate)
        info = EmulatorInfo(
            name = "${core.systemInfo.library_name} ${core.systemInfo.library_version}",
            fps = core.avInfo.timing.fps,
        )
        return core
    }

    /** The emulation loop: run tasks, emulate one frame, then sleep to keep real-time speed. */
    private fun loop(core: LibretroCore) {
        val frameDurationNanos = (1_000_000_000 / info.fps).toLong()
        var nextFrameAt = System.nanoTime()
        var speedWindowStart = System.nanoTime()
        var framesInWindow = 0

        while (!closed) {
            while (true) tasks.poll()?.invoke(core) ?: break

            if (!running) {
                Thread.sleep(5)
                nextFrameAt = System.nanoTime()
                continue
            }

            core.run()
            framesInWindow++

            val now = System.nanoTime()
            val measuredFps = if (now - speedWindowStart >= 500_000_000) {
                (framesInWindow * 1e9 / (now - speedWindowStart)).also {
                    speedWindowStart = now
                    framesInWindow = 0
                }
            } else null
            _status.update { it.copy(frameCount = it.frameCount + 1, measuredFps = measuredFps ?: it.measuredFps) }

            if (fastForward) {
                nextFrameAt = now
            } else {
                nextFrameAt += frameDurationNanos
                val sleepNanos = nextFrameAt - System.nanoTime()
                if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                else if (sleepNanos < -frameDurationNanos * 5) nextFrameAt = System.nanoTime() // too late: don't try to catch up
            }
        }
    }

    /** Called by the core (on the emulation thread) for each input it wants to read. */
    private fun inputState(port: Int, device: Int, index: Int, id: Int): Short {
        if (port != 0) return 0
        return when (device) {
            Retro.DEVICE_JOYPAD -> {
                val button = BUTTON_IDS[id] ?: return 0
                if (heldButtons.values.any { button in it }) 1 else 0
            }

            Retro.DEVICE_POINTER -> {
                val (x, y) = touchPosition ?: return 0
                // libretro pointer coordinates span the whole frame from -0x7FFF to 0x7FFF.
                when (id) {
                    Retro.POINTER_X -> (x * 0xFFFE - 0x7FFF).toInt().toShort()
                    Retro.POINTER_Y -> (y * 0xFFFE - 0x7FFF).toInt().toShort()
                    Retro.POINTER_PRESSED -> 1
                    else -> 0
                }
            }

            else -> 0
        }
    }

    /** Runs [block] on the emulation thread between two frames and returns its result. */
    private suspend fun <T> onEmulationThread(block: (LibretroCore) -> T): T {
        val result = CompletableDeferred<T>()
        tasks.add { core -> result.completeWith(runCatching { block(core) }) }
        return result.await()
    }

    // region Emulator

    override fun start() = resume()

    override fun pause() {
        running = false
        _status.update { it.copy(running = false) }
    }

    override fun resume() {
        running = true
        _status.update { it.copy(running = true) }
    }

    override fun setFastForward(enabled: Boolean) {
        fastForward = enabled
        _status.update { it.copy(fastForward = enabled) }
        updateAudio()
    }

    override fun setMuted(muted: Boolean) {
        _status.update { it.copy(muted = muted) }
        updateAudio()
    }

    /** Audio is silent when muted, and while fast-forwarding (it would be sped-up noise). */
    private fun updateAudio() {
        audio.muted = status.value.muted || status.value.fastForward
    }

    override fun setButtons(source: InputSource, buttons: Set<Button>) {
        heldButtons[source] = buttons
    }

    override fun touch(position: Pair<Float, Float>?) {
        touchPosition = position
    }

    override suspend fun awaitFrames(count: Int) {
        val target = status.value.frameCount + count
        status.first { it.frameCount >= target }
    }

    override suspend fun readMainRam(): ByteArray = onEmulationThread { core ->
        core.readMemory(Retro.MEMORY_SYSTEM_RAM) ?: error("${spec.name} doesn't expose its system RAM")
    }

    override suspend fun saveState(slot: Int): Boolean = onEmulationThread { core ->
        val state = core.saveState() ?: return@onEmulationThread false
        Files.createDirectories(saveStateDirectory)
        Files.write(stateFile(slot), state)
        true
    }

    override suspend fun loadState(slot: Int): Boolean = onEmulationThread { core ->
        val file = stateFile(slot)
        Files.exists(file) && core.loadState(Files.readAllBytes(file))
    }

    override suspend fun reset() = onEmulationThread { core -> core.reset() }

    override fun close() {
        closed = true
        emulationThread.join(2_000)
    }

    // endregion

    private fun stateFile(slot: Int) = saveStateDirectory.resolve("${romPath.fileName}.state$slot")

    private companion object {
        /** libretro joypad ids -> our buttons. */
        val BUTTON_IDS = mapOf(
            Retro.JOYPAD_A to Button.A,
            Retro.JOYPAD_B to Button.B,
            Retro.JOYPAD_X to Button.X,
            Retro.JOYPAD_Y to Button.Y,
            Retro.JOYPAD_L to Button.L,
            Retro.JOYPAD_R to Button.R,
            Retro.JOYPAD_START to Button.START,
            Retro.JOYPAD_SELECT to Button.SELECT,
            Retro.JOYPAD_UP to Button.UP,
            Retro.JOYPAD_DOWN to Button.DOWN,
            Retro.JOYPAD_LEFT to Button.LEFT,
            Retro.JOYPAD_RIGHT to Button.RIGHT,
        )
    }
}
