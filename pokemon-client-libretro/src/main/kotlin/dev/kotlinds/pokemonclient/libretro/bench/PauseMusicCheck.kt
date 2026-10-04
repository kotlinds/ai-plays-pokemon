package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.libretro.ConsoleRole
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import dev.kotlinds.pokemonclient.libretro.sound.ShadowEnd
import dev.kotlinds.pokemonclient.libretro.sound.ShadowRun
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverState
import dev.kotlinds.pokemonclient.libretro.sound.SoundFixtures
import dev.kotlinds.pokemonclient.libretro.sound.SoundResync
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Collects the audio of a console while [target] is set (bench only). */
class AudioTap {
    /** Where samples go now (null: dropped). */
    var target: ShortList? = null

    /** Drops the samples while set (priming frames). */
    var muted = false

    fun onAudio(samples: ShortArray, frames: Int) {
        if (!muted) target?.add(samples, frames * 2)
    }
}

/** A growable list of interleaved stereo samples. */
class ShortList {
    var data = ShortArray(1 shl 16)
        private set
    var size = 0
        private set

    fun add(samples: ShortArray, count: Int) {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        samples.copyInto(data, size, 0, count)
        size += count
    }

    fun addSilence(count: Int) = add(ShortArray(count), count)

    fun addAll(other: ShortList) = add(other.data, other.size)

    /** Stereo frames. */
    val frames: Int get() = size / 2
}

/**
 * Bench checks of music during pauses, on the real implementation ([ShadowRun] on a [ConsoleRole.SHADOW] console,
 * [SoundResync] on the bench's console), headless and unpaced:
 * - `pausemusic:<name>[:<play s>,<pause s>,<resume s>]` (default 5,5,10): plays, pauses (the shadow plays), resumes
 *   with the resync, and writes `<name>_app.wav` (what the app plays), `<name>_today.wav` (silence during the pause,
 *   then the music jumps back) and `<name>_ideal.wav` (as if the game never paused) to the out dir; prints the guards'
 *   decision, the continuity at resume (correlation with the ideal continuation) and the main RAM differences
 *   with "today" after the resume;
 * - `pausemusicstats:<pauses>[:<pause frames>]`: that many short pauses one after the other (the game runs 7 frames
 *   between two), and the share of each outcome and the mean continuity.
 */
class PauseMusicCheck(
    private val main: LibretroConsole,
    private val mainTap: AudioTap,
    private val spec: LibretroCoreSpec,
    private val rom: Path,
    private val data: Path,
    private val out: Path,
) : AutoCloseable {
    private val shadowTap = AudioTap()
    private val shadowDelegate = lazy {
        val started = System.nanoTime()
        LibretroConsole(spec, rom, data, onVideo = {}, onAudio = shadowTap::onAudio, role = ConsoleRole.SHADOW).also {
            println("  shadow ${it.coreName} ready in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
    private val shadow: LibretroConsole by shadowDelegate

    /** Closes the shadow (deletes its throwaway directory). */
    override fun close() {
        if (shadowDelegate.isInitialized()) shadow.close()
    }
    private val layout = SoundDriverLayout.forRom(rom) ?: error("music during pauses doesn't support this ROM")
    private val resync = SoundResync(layout, spec.soundSplicer)
    private val rate get() = main.sampleRate.roundToInt()

    private fun seconds(s: Double) = (s * main.fps).roundToInt()

    fun check(arg: String) {
        val name = arg.substringBefore(':')
        val (play, pause, resume) = arg.substringAfter(':', "5,5,10").split(',').map { it.toDouble() }
        val played = ShortList().also { mainTap.target = it }
        main.step(seconds(play))
        mainTap.target = null

        // Pause: the main console stays frozen at P; the shadow plays from P.
        var t = System.nanoTime()
        val paused = main.saveState()
        val tSave = ms(t)
        val pausedRam = mainRam()
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        val during = ShortList().also { shadowTap.target = it }
        t = System.nanoTime()
        check(run.begin(paused)) { "shadow rejected the paused state" }
        val tLoad = ms(t)

        t = System.nanoTime()
        repeat(seconds(pause)) { run.step() }
        val msPerFrame = ms(t) / seconds(pause)
        t = System.nanoTime()
        val end = run.end()
        val tEnd = ms(t)
        shadowTap.target = null
        println("  P: ${describe(paused)}")
        println("  shadow end: ${(end as? ShadowEnd.Safe)?.let { "frame ${it.frames}, " + describe(it.state) } ?: end}")

        // The ideal reference: the shadow simply goes on from where it stopped (as if the game had never paused).
        val ideal = ShortList().also { shadowTap.target = it }
        if (end is ShadowEnd.Safe) shadow.step(seconds(resume))
        shadowTap.target = null

        // Resume: the real resync on the main console.
        t = System.nanoTime()
        val result = resync.resume(main, paused, end)
        val tResync = ms(t)
        println("  result: $result")
        println("  timings: main save ${tSave}ms, shadow load ${tLoad}ms, shadow ${"%.2f".format(msPerFrame)} ms/frame, safe end ${tEnd}ms, resync ${tResync}ms")
        check(result is ResyncResult.Resynced || mainRam().contentEquals(pausedRam)) { "main RAM changed although nothing was loaded" }
        if (result is ResyncResult.Resynced) check(mainRam().contentEquals(pausedRam)) { "main RAM changed by the resync" }

        val resumed = ShortList().also { mainTap.target = it }
        main.step(seconds(resume))
        mainTap.target = null
        val mainAfter = mainRam()

        // "Today" reference, on the shadow: the game resumes from P.
        val today = ShortList().also { shadowTap.target = it }
        shadow.loadState(paused)
        shadow.step(seconds(resume))
        val todayRam = shadowRam()
        shadowTap.target = null

        writeWav("${name}_app", played, during, resumed)
        writeWav("${name}_today", played, ShortList().also { it.addSilence(during.size) }, today)
        writeWav("${name}_ideal", played, during, ideal)
        if (end is ShadowEnd.Safe) {
            val lag = rate / 100 // 10 ms
            println("  continuity at resume (corr with ideal, per 0.5 s): " + (0 until 4).joinToString(" ") { "%+.3f".format(correlation(resumed, ideal, it * rate / 2, rate / 2)) })
            println("  same, best lag within 10 ms:                       " + (0 until 4).joinToString(" ") { "%+.3f".format(correlation(resumed, ideal, it * rate / 2, rate / 2, lag)) })
            println("  today, best lag within 10 ms:                      " + (0 until 4).joinToString(" ") { "%+.3f".format(correlation(today, ideal, it * rate / 2, rate / 2, lag)) })
            println("  spectral similarity with ideal, first 2 s: resumed ${"%.3f".format(spectral(resumed, ideal, 0, 2 * rate))}, today ${"%.3f".format(spectral(today, ideal, 0, 2 * rate))}")
            println("  loudness (rms) resumed / ideal, first 0.5 s: ${"%.2f".format(rms(resumed, rate / 2) / rms(ideal, rate / 2))}")
            println("  jump at resume |shadow last - main first| ${jump(during, resumed)} (today ${jump(during, today)}; median step ${medianStep(during)})")
        }
        println("  main RAM after ${resume}s vs today: ${ramDiff(mainAfter, todayRam, paused)}")
    }

    /**
     * `pausemusicfixtures:<prefix>[:<frames>]`: test fixtures (see [SoundFixtures]) of the current state (`<prefix>_p`)
     * and of the shadow's safe end after that many frames (`<prefix>_s`); then steps the game until a frame where the
     * sound thread runs (`<prefix>_busy`) and one where a DMA is mid-burst if the core is melonDS (`<prefix>_dma`).
     */
    fun fixtures(arg: String) {
        val prefix = arg.substringBefore(':')
        val frames = arg.substringAfter(':', "120").toInt()
        val paused = main.saveState()
        SoundFixtures.write(out.resolve("${prefix}_p.state.gz"), spec.soundSplicer, layout, paused)
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        check(run.begin(paused))
        repeat(frames) { run.step() }
        val end = run.end() as? ShadowEnd.Safe ?: error("no safe frame")
        SoundFixtures.write(out.resolve("${prefix}_s.state.gz"), spec.soundSplicer, layout, end.state)
        for (i in 0 until 600) {
            val state = main.saveState()
            if (SoundDriverState.read(spec.soundSplicer.locate(state), layout).soundThreadRunning) {
                SoundFixtures.write(out.resolve("${prefix}_busy.state.gz"), spec.soundSplicer, layout, state)
                break
            }
            main.step(1)
        }
        if (spec == LibretroCoreSpec.MELONDS) for (i in 0 until 600) {
            val state = main.saveState()
            if (spec.soundSplicer.primingState(state) != null) {
                SoundFixtures.write(out.resolve("${prefix}_dma.state.gz"), spec.soundSplicer, layout, state)
                break
            }
            main.step(1)
        }
        println("  fixtures written to $out")
    }

    /** `pausemusicload:<state file>`: the shadow loads a state file (priming if needed) and runs 60 frames. */
    fun load(arg: String) {
        val bytes = Files.readAllBytes(out.resolve(arg))
        println("  priming needed: ${spec.soundSplicer.primingState(bytes) != null}")
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        println("  begin=${run.begin(bytes)}")
        repeat(60) { run.step() }
        println("  ran ${run.frames} frames")
    }

    fun stats(arg: String) {
        val pauses = arg.substringBefore(':').toInt()
        val pauseFrames = arg.substringAfter(':', "60").toInt()
        val outcomes = HashMap<String, Int>()
        val continuity = mutableListOf<Double>()
        val continuityLag = mutableListOf<Double>()
        val spectralSimilarity = mutableListOf<Double>()
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        repeat(pauses) {
            main.step(7)
            val paused = main.saveState()
            run.begin(paused)
            repeat(pauseFrames) { run.step() }
            val end = run.end()
            val ideal = ShortList().also { shadowTap.target = it }
            shadow.step(30)
            shadowTap.target = null
            val result = resync.resume(main, paused, end)
            val key = when (result) {
                is ResyncResult.Resynced -> "resynced"
                is ResyncResult.Refused -> result.reason::class.simpleName!!
                is ResyncResult.Aborted -> "ABORTED ${result.why}"
            }
            outcomes[key] = (outcomes[key] ?: 0) + 1
            if (result is ResyncResult.Resynced && end is ShadowEnd.Safe) {
                val after = ShortList().also { mainTap.target = it }
                main.step(30)
                mainTap.target = null
                continuity += correlation(after, ideal, 0, rate / 4)
                continuityLag += correlation(after, ideal, 0, rate / 4, rate / 100)
                spectralSimilarity += spectral(after, ideal, 0, 30 * rate / 60)
            }
        }
        println("  outcomes over $pauses pauses of $pauseFrames frames: ${outcomes.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }}")
        if (continuity.isNotEmpty()) {
            println("  continuity (corr with ideal, first 0.25 s): mean ${"%.3f".format(continuity.average())}, min ${"%.3f".format(continuity.min())}")
            println("  continuity (best lag within 10 ms): mean ${"%.3f".format(continuityLag.average())}, min ${"%.3f".format(continuityLag.min())}, " +
                "median ${"%.3f".format(continuityLag.sorted()[continuityLag.size / 2])}")
            println("  spectral similarity with ideal (first 0.5 s): mean ${"%.3f".format(spectralSimilarity.filter { !it.isNaN() }.average())}")
        }
    }

    private fun describe(state: ByteArray): String = runCatching {
        val s = SoundDriverState.read(spec.soundSplicer.locate(state), layout)
        "players ${s.players.map { p -> "#${p.index} flags ${p.flags} ${p.tracks.size} tracks" }}, sound thread running ${s.soundThreadRunning}, " +
            "queued ${s.queuedCommandLists}, in flight ${s.commandsInFlight?.map { "0x%02X".format(it) }}, locked ${s.lockedChannels}"
    }.getOrElse { "unreadable: ${it.message}" }

    private fun mainRam() = ByteArray(main.memorySize(MemoryRegion.MAIN_RAM)).also { main.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }
    private fun shadowRam() = ByteArray(shadow.memorySize(MemoryRegion.MAIN_RAM)).also { shadow.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

    /**
     * Differing bytes: in the SND shared work (written by the ARM7), in HeartGold US's known sound / clock buffers
     * (NNS driver-info copies of the ARM7 work at 0x021DD460, 2 x 0x11E0; `sRTCWork` at 0x021D1048, the real-time
     * clock), and the rest as merged ranges.
     */
    private fun ramDiff(a: ByteArray, b: ByteArray, paused: ByteArray): String {
        val state = spec.soundSplicer.locate(paused)
        val shared = state.arm7(layout.sharedWorkPointer) - SoundDriverLayout.MAIN_RAM
        var inShared = 0
        var inMirrors = 0
        var inClock = 0
        val ranges = mutableListOf<IntArray>()
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            if (i - shared in 0 until 0x280) { inShared++; continue }
            if (layout == SoundDriverLayout.HEARTGOLD_US && i in 0x1DD460 until 0x1DF820) { inMirrors++; continue }
            if (layout == SoundDriverLayout.HEARTGOLD_US && i in 0x1D1048 until 0x1D10A0) { inClock++; continue }
            val last = ranges.lastOrNull()
            if (last != null && i - last[1] <= 16) last[1] = i else ranges += intArrayOf(i, i)
        }
        val other = ranges.sumOf { it[1] - it[0] + 1 }
        return "$inShared bytes in SNDSharedWork, $inMirrors in NNS driver-info copies, $inClock in the RTC work, " +
            "${ranges.size} other ranges (~$other bytes): " + ranges.take(12).joinToString { "%08X-%08X".format(it[0] + SoundDriverLayout.MAIN_RAM, it[1] + SoundDriverLayout.MAIN_RAM) }
    }

    /**
     * Correlation of [x] (shifted by up to [maxLag] samples either way, best one) with [y], over [length] samples from
     * [from]: the sequencer ticks of the resumed game keep the paused game's timer phase, so notes may start up to one
     * tick (~5 ms) apart from the ideal continuation, which a plain correlation punishes although it isn't audible.
     */
    private fun correlation(x: ShortList, y: ShortList, from: Int, length: Int, maxLag: Int = 0): Double =
        (-(maxLag / 4) * 4..maxLag step 4).maxOf { lag -> if (from + lag < 0) -2.0 else correlationAt(x, y, from, length, lag).takeUnless { it.isNaN() } ?: -2.0 }
            .takeIf { it > -2.0 } ?: Double.NaN

    private fun correlationAt(x: ShortList, y: ShortList, from: Int, length: Int, lag: Int): Double {
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        val end = minOf(from + length, x.frames - maxOf(lag, 0), y.frames)
        for (i in from until end) {
            val a = (x.data[2 * (i + lag)] + x.data[2 * (i + lag) + 1]) / 2.0
            val b = (y.data[2 * i] + y.data[2 * i + 1]) / 2.0
            xy += a * b; xx += a * a; yy += b * b
        }
        return if (xx == 0.0 || yy == 0.0) Double.NaN else xy / sqrt(xx * yy)
    }

    /**
     * Spectral similarity of [x] and [y] over [length] samples from [from]: the mean, over 2048-sample windows, of the
     * correlation of their magnitude spectra (phase-blind, closer to what is heard than the waveform correlation).
     */
    private fun spectral(x: ShortList, y: ShortList, from: Int, length: Int): Double {
        val n = 2048
        val values = (from until minOf(from + length, x.frames, y.frames) - n step n).map { start ->
            val a = magnitudes(x, start, n)
            val b = magnitudes(y, start, n)
            val ma = a.average(); val mb = b.average()
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
            if (aa == 0.0 || bb == 0.0) Double.NaN else ab / sqrt(aa * bb)
        }.filter { !it.isNaN() }
        return if (values.isEmpty()) Double.NaN else values.average()
    }

    /** Magnitude spectrum (Hann window, radix-2 FFT) of [n] mono samples of [x] from [start]. */
    private fun magnitudes(x: ShortList, start: Int, n: Int): DoubleArray {
        val re = DoubleArray(n) { i -> (x.data[2 * (start + i)] + x.data[2 * (start + i) + 1]) / 2.0 * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / n)) }
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var size = 2
        while (size <= n) {
            val angle = -2 * Math.PI / size
            for (k in 0 until n step size) for (m in 0 until size / 2) {
                val wr = kotlin.math.cos(angle * m); val wi = kotlin.math.sin(angle * m)
                val tr = re[k + m + size / 2] * wr - im[k + m + size / 2] * wi
                val ti = re[k + m + size / 2] * wi + im[k + m + size / 2] * wr
                re[k + m + size / 2] = re[k + m] - tr; im[k + m + size / 2] = im[k + m] - ti
                re[k + m] += tr; im[k + m] += ti
            }
            size *= 2
        }
        return DoubleArray(n / 2) { kotlin.math.ln(1 + sqrt(re[it] * re[it] + im[it] * im[it])) }
    }

    private fun rms(x: ShortList, length: Int): Double {
        val n = minOf(length * 2, x.size)
        return sqrt((0 until n).sumOf { x.data[it].toDouble() * x.data[it] } / maxOf(n, 1))
    }

    private fun jump(before: ShortList, after: ShortList): Int =
        if (before.size < 2 || after.size < 2) 0
        else (abs(before.data[before.size - 2] - after.data[0]) + abs(before.data[before.size - 1] - after.data[1])) / 2

    private fun medianStep(x: ShortList): Int {
        val n = minOf(x.frames - 1, rate)
        if (n <= 0) return 0
        val from = x.frames - 1 - n
        return (from until from + n).map { abs(x.data[2 * it + 2] - x.data[2 * it]) }.sorted()[n / 2]
    }

    private fun writeWav(name: String, vararg parts: ShortList) {
        val all = ShortList().also { list -> parts.forEach(list::addAll) }
        val pcm = ByteBuffer.allocate(all.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> for (i in 0 until all.size) b.putShort(all.data[i]) }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 4); putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size)
        }.array()
        Files.write(out.resolve("$name.wav"), ByteArrayOutputStream().also { it.write(header); it.write(pcm) }.toByteArray())
        println("  wrote $name.wav (${"%.1f".format(all.frames.toDouble() / rate)} s)")
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0
}
