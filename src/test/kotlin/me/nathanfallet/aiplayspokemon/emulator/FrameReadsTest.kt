package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.RamMemory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The readings of the game made while an action steps the console ([ConsoleHost.observe] through [FrameReads]): served
 * between two frames, so a `lookup` made during a long `act` reads a consistent party at once, instead of racing the
 * emulator or waiting for the end of the action.
 */
class FrameReadsTest {
    /**
     * A console thread running an action of [frames] frames: each frame writes a "party" (its size, then its six
     * species) one byte at a time, so the RAM is inconsistent in the middle of a frame; between two frames it serves
     * the readings. [started] is set once it runs.
     */
    private fun action(reads: FrameReads, frames: Int, started: java.util.concurrent.CountDownLatch, frameMillis: Long = 0): Thread {
        val ram = ByteArray(16)
        return thread(name = "console") {
            started.countDown()
            for (frame in 1..frames) {
                val value = (frame % 6 + 1).toByte()
                ram[0] = value
                for (slot in 1..6) {
                    ram[slot] = value
                    Thread.yield()
                }
                if (frameMillis > 0) Thread.sleep(frameMillis)
                reads.serve { RamMemory(ram) }
            }
        }
    }

    /** The party as one reading sees it: its size and its six species. */
    private fun party(memory: dev.kotlinds.pokemonclient.Memory) = (0..6).map { memory.read8(RAM + it) }

    @Test
    fun aReadingDuringAnActionSeesAConsistentParty() = runBlocking {
        val reads = FrameReads()
        val started = java.util.concurrent.CountDownLatch(1)
        val console = action(reads, frames = 200_000, started)
        started.await()
        val parties = (1..200).map { async(kotlinx.coroutines.Dispatchers.Default) { reads.read(::party) } }.awaitAll()
        console.join()
        parties.forEach { party -> assertEquals(1, party.toSet().size, "a party read mid-frame: $party") }
    }

    @Test
    fun aReadingDuringALongActionIsAnsweredWithinAFrameNotAtItsEnd() = runBlocking {
        val reads = FrameReads()
        val started = java.util.concurrent.CountDownLatch(1)
        // A 3 s action (180 frames of ~16 ms, a go_to).
        val console = action(reads, frames = 180, started, frameMillis = 16)
        started.await()
        val asked = System.currentTimeMillis()
        val party = reads.read(::party)
        val answered = System.currentTimeMillis() - asked
        assertTrue(console.isAlive, "the action still runs")
        assertTrue(answered < 500, "answered after $answered ms")
        assertEquals(1, party.toSet().size)
        console.join()
    }

    @Test
    fun aReadingThatFailsFailsAloneAndTheOthersAreServed() = runBlocking {
        val reads = FrameReads()
        val started = java.util.concurrent.CountDownLatch(1)
        val console = action(reads, frames = 100_000, started)
        started.await()
        val failing = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { reads.read { error("no party") } } }
        val fine = async(kotlinx.coroutines.Dispatchers.Default) { reads.read(::party) }
        assertFailsWith<IllegalStateException> { failing.await().getOrThrow() }
        assertEquals(7, fine.await().size)
        console.join()
    }

    private companion object {
        const val RAM = 0x02000000L
    }
}
