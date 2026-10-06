package me.nathanfallet.aiplayspokemon.emulator.audio

import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import dev.kotlinds.pokemonclient.libretro.ConsoleRole
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.PauseStart
import dev.kotlinds.pokemonclient.libretro.sound.ResyncRefusal
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import dev.kotlinds.pokemonclient.libretro.sound.ShadowEnd
import dev.kotlinds.pokemonclient.libretro.sound.SampleBuffer
import dev.kotlinds.pokemonclient.libretro.sound.ShadowRun
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout
import dev.kotlinds.pokemonclient.libretro.sound.SoundResync
import me.nathanfallet.aiplayspokemon.emulator.FramePacer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Music during pauses: while the game is paused (by the user, or while an agent thinks), its music goes on, and the
 * game resumes with the music where it got to instead of jumping back to where the pause started.
 *
 * The game itself stays frozen: a shadow console (a second instance of the core, [ConsoleRole.SHADOW]) loads the
 * paused state and runs it with no input, in real time, and its sound is played. At resume, the shadow stops at a
 * safe frame and [SoundResync] copies its sound playback state (and nothing else) into the paused state, which the
 * game loads, with its main RAM verified byte-identical. Any guard failing resumes the game as without this feature
 * (the music jumps back, with a short fade), and is logged.
 *
 * Nothing runs while the game runs, nor when the sound is off ([soundOn]) or the feature disabled
 * ([MusicDuringPausesSettings.enabled]).
 *
 * Threads: [onIdle], [beforeMainFrame] and [discard] are called on the console thread (the only one touching the main
 * console); the shadow runs on its own thread ("shadow-audio").
 */
class ShadowAudio(
    private val spec: LibretroCoreSpec,
    private val rom: Path,
    private val dataDirectory: Path,
    private val audio: AudioPlayer,
    fps: Double,
    /** The settings, read at every pause: the owner ([me.nathanfallet.aiplayspokemon.emulator.ConsoleHost]) holds them. */
    private val settings: () -> MusicDuringPausesSettings,
) : AutoCloseable {

    private val layout = SoundDriverLayout.forRom(rom.toKotlinxPath())

    /** Why music during pauses can't run with this ROM / core / platform, or null when it can. */
    val unsupportedReason: String? = when {
        layout == null -> "the ROM's sound driver isn't a known one (ARM7 binary ${SoundDriverLayout.arm7Sha1(rom.toKotlinxPath())})"
        else -> null
    }

    private val resync = layout?.takeIf { unsupportedReason == null }?.let { SoundResync(it, spec.soundSplicer) }

    /** The game's sound is heard (not muted, not fast-forwarding). */
    @Volatile
    var soundOn = true

    /** What the last resume with a shadow did (null before the first one). */
    @Volatile
    var lastResult: ResyncResult? = null
        private set

    /** Called on the console thread with the outcome of every resume that had a shadow playing. */
    @Volatile
    var onResume: ((ResyncResult) -> Unit)? = null

    /** Set after an unexpected failure: the feature stays off until the app restarts. */
    @Volatile
    private var broken = false

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pending: Session? = null // guarded by lock: the session the shadow thread must play next
    @Volatile private var closed = false

    /** The current pause's session (console thread only). */
    private var session: Session? = null

    /** When the coming pause starts: the library's rule, shared with the bench (console thread only). */
    private val pauseStart = resync?.let { PauseStart(it) }

    /** The main console's state saved by [delaysPause] for the pause to start from, with its frame and revision. */
    private var pauseCandidate: Triple<Long, Long, ByteArray>? = null

    /** Whether the shadow should play during pauses now. */
    private val wanted: Boolean get() = resync != null && settings().enabled && soundOn && !broken

    /** One pause. Its [phase] is guarded by [lock]. */
    private class Session(val paused: ByteArray) {
        var phase = Phase.STARTING
        var end: ShadowEnd = ShadowEnd.NotSafe(0)
        var failure: String? = null

        /** Sound of one frame after the end (not played): faded out if the resync is refused. */
        var tail = ShortArray(0)
    }

    private enum class Phase { STARTING, PLAYING, ENDING, ENDED, CANCELLED }

    // region shadow thread

    private var shadow: LibretroConsole? = null
    private val pacer = FramePacer(fps)
    @Volatile private var silenced = false
    @Volatile private var capturing: SampleBuffer? = null

    private val worker = thread(name = "shadow-audio", isDaemon = true) {
        try {
            // Warm up, so that the first pause starts at once (creating the shadow takes a few hundred ms).
            if (resync != null && settings().enabled && soundOn) {
                runCatching { shadowConsole() }.onFailure {
                    broken = true
                    log("the shadow console can't start (${it.message}); music during pauses is off")
                }
            }
            while (!closed) {
                val next = lock.withLock {
                    while (pending == null && !closed) changed.await()
                    pending.also { pending = null }
                } ?: break
                play(next)
            }
        } catch (_: InterruptedException) {
            // closing
        } finally {
            shadow?.close()
        }
    }

    private fun shadowConsole(): LibretroConsole = shadow ?: LibretroConsole(
        spec, rom.toKotlinxPath(), dataDirectory.toKotlinxPath(),
        onVideo = {},
        onAudio = { samples, frames -> onShadowAudio(samples, frames) },
        role = ConsoleRole.SHADOW,
    ).also { shadow = it }

    private fun onShadowAudio(samples: ShortArray, frames: Int) {
        if (silenced) return
        capturing?.let { it.add(samples, frames * 2); return }
        if (soundOn) audio.play(samples, frames)
    }

    /** Plays one pause on the shadow until the console thread ends or cancels it. */
    private fun play(session: Session) {
        val run = try {
            ShadowRun(shadowConsole(), resync!!, silence = { silenced = it }).takeIf { it.begin(session.paused) }
        } catch (error: Throwable) {
            session.failure = error.message ?: error.toString()
            null
        }
        lock.withLock {
            if (run == null) {
                session.failure = session.failure ?: "the shadow rejected the paused state"
                session.phase = Phase.ENDED
            } else if (session.phase == Phase.STARTING) session.phase = Phase.PLAYING
            changed.signalAll()
        }
        if (run == null) return
        pacer.reset()
        while (true) {
            when (lock.withLock { session.phase }) {
                Phase.CANCELLED -> return
                Phase.ENDING -> break
                else -> {
                    run.step()
                    pacer.frameDone(fastForward = false)
                }
            }
        }
        val end = try {
            run.end(afterFrame = { pacer.frameDone(fastForward = false) }, settleFrames = ShadowRun.SETTLE_FRAMES)
        } catch (error: Throwable) {
            session.failure = error.message ?: error.toString()
            ShadowEnd.NotSafe(run.frames)
        }
        if (end is ShadowEnd.Safe) {
            val tail = SampleBuffer().also { capturing = it }
            try {
                run.step()
            } finally {
                capturing = null
            }
            session.tail = tail.toArray()
        }
        lock.withLock {
            session.end = end
            if (session.phase == Phase.ENDING) session.phase = Phase.ENDED
            changed.signalAll()
        }
    }

    // endregion

    // region console thread

    /**
     * Called by the console thread when the game is about to freeze (the frame before the first [onIdle] of a pause):
     * true when the pause had better start one frame later, the game then emulating one more frame through its normal
     * emulation, as if the pause had been asked then ([PauseStart]: while the game is changing its song, with
     * [MusicDuringPausesSettings.waitForSongChange]; then for a frame a pause couldn't be resynced from). While it waits
     * for a song change, the game runs on (sound heard, the AI's next action starting at once if it comes), so what it
     * shows may move on a little after the pause was asked.
     */
    fun delaysPause(main: LibretroConsole): Boolean {
        if (session != null || !wanted) return false
        val state = main.saveState()
        val pauseStart = pauseStart!!
        val reason = pauseStart.putOff(state, waitForSongChange = settings().waitForSongChange)
        if (reason == PauseStart.Reason.SONG_CHANGE && pauseStart.songChangeWaitExhausted) {
            log("the song change didn't come within ${pauseStart.maxSongChangeWait} frames: pausing anyway")
        }
        val delayed = reason != null
        pauseCandidate = if (delayed) null else Triple(main.frame, main.revision, state)
        return delayed
    }

    /** Called by the console thread while the game is paused (every few ms): starts or stops the shadow. */
    fun onIdle(main: LibretroConsole) {
        val wanted = wanted
        val current = session
        if (current == null && wanted) {
            val candidate = pauseCandidate?.takeIf { (frame, revision) -> frame == main.frame && revision == main.revision }
            val started = Session(candidate?.third ?: main.saveState())
            pauseCandidate = null
            pauseStart?.reset()
            session = started
            lock.withLock {
                pending = started
                changed.signalAll()
            }
        } else if (current != null && !wanted) {
            cancel(current)
            session = null
        }
    }

    /**
     * Called by the console thread before the game emulates a frame (free run or agent action): if the shadow played
     * during the pause, resyncs the game's music with it; otherwise does nothing.
     */
    fun beforeMainFrame(main: LibretroConsole) {
        pauseStart?.reset() // the game runs: a pause coming later is another one
        val current = session ?: return
        session = null
        val ended = lock.withLock {
            when (current.phase) {
                Phase.STARTING, Phase.CANCELLED -> {
                    // The shadow hasn't played anything yet: nothing to resync, and no reason to wait.
                    current.phase = Phase.CANCELLED
                    if (pending === current) pending = null
                    false
                }
                else -> {
                    if (current.phase == Phase.PLAYING) current.phase = Phase.ENDING
                    changed.signalAll()
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(END_TIMEOUT_MILLIS)
                    while (current.phase == Phase.ENDING && System.nanoTime() < deadline) {
                        changed.awaitNanos(deadline - System.nanoTime())
                    }
                    if (current.phase != Phase.ENDED) current.phase = Phase.CANCELLED
                    current.phase == Phase.ENDED
                }
            }
        }
        if (!ended) return
        val result = current.failure?.let { ResyncResult.Refused(ResyncRefusal.ShadowFailed(it)) }
            ?: resync!!.resume(main, current.paused, current.end)
        lastResult = result
        onResume?.invoke(result)
        when (result) {
            is ResyncResult.Resynced -> Unit
            is ResyncResult.Refused -> {
                if (result.reason != ResyncRefusal.NothingPlayed) log("resumed without resync: ${result.reason.message}")
                if (result.reason == ResyncRefusal.MainRamChangedDuringPause) restore(main, current.paused)
                if (result.reason is ResyncRefusal.ShadowFailed) broken = true
                jumpBack(current)
            }
            is ResyncResult.Aborted -> {
                log("resync aborted: ${result.why}; music during pauses is off until restart")
                broken = true
                jumpBack(current)
            }
        }
    }

    /** The game's state was replaced during the pause (state loaded, reset): the shadow's music isn't its anymore. */
    fun discard() {
        session?.let(::cancel)
        session = null
        pauseCandidate = null
        pauseStart?.reset()
    }

    private fun cancel(current: Session) = lock.withLock {
        current.phase = Phase.CANCELLED
        if (pending === current) pending = null
        changed.signalAll()
    }

    /**
     * Nothing may change the game's main RAM during a pause (states loaded and resets discard the session first):
     * if it did, the shadow wasn't isolated from the game. The paused state is loaded back, and the feature disabled.
     */
    private fun restore(main: LibretroConsole, paused: ByteArray) {
        broken = true
        val restored = resync!!.restorePaused(main, paused) // the same main RAM check as the resync's
        log("the game's RAM changed during the pause: paused state restored ($restored); music during pauses is off until restart")
    }

    /** The game resumes from its paused state: the music jumps back, so fade the shadow's sound out and the game's in. */
    private fun jumpBack(current: Session) {
        if (current.end.let { it is ShadowEnd.NotSafe && it.frames == 0 }) return
        audio.playFadingOut(current.tail, current.tail.size / 2)
        audio.fadeIn()
    }

    // endregion

    override fun close() {
        closed = true
        lock.withLock {
            session?.let { it.phase = Phase.CANCELLED }
            changed.signalAll()
        }
        worker.join(2_000)
    }

    private fun log(message: String) = println("[music during pauses] $message")

    private companion object {
        /**
         * The shadow reaches a safe frame within 3 frames (~50 ms), plus up to [ShadowRun.SETTLE_FRAMES] when a sound its game
         * started still plays; past this, resume without it.
         */
        const val END_TIMEOUT_MILLIS = 1_000L
    }
}

/**
 * The "Music during pauses" settings, one value read by [ShadowAudio] at every pause (the persisted source is the
 * player's settings, see [me.nathanfallet.aiplayspokemon.agent.PlayerSettings.musicDuringPausesSettings]).
 */
data class MusicDuringPausesSettings(
    /** "Music during pauses": the shadow plays while the game is paused (only when the sound is on). */
    val enabled: Boolean = true,
    /**
     * "Wait for the song change": when the game is about to change its song as it pauses (it is fading the old one
     * out, e.g. right after walking into a route with its own music), the pause starts once the new song plays, up to
     * [PauseStart.MAX_SONG_CHANGE_WAIT_FRAMES] later ([ShadowAudio.delaysPause]). Off, such a pause starts at once and,
     * lasting past the change, resumes with the music jumping back to the old song's fade.
     */
    val waitForSongChange: Boolean = true,
)
