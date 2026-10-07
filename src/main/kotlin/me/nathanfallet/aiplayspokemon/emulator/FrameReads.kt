package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.Memory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reads of the game served on the console thread **between two frames**, as soon as the frame being emulated is done:
 * during the free run, while the game is frozen, and also in the middle of an agent's action (a lease steps frames
 * one after the other, and each frame end serves the reads waiting). What [ConsoleHost.observe] goes through.
 *
 * Why between two frames: the RAM is only consistent there (a frame writes the party, the map, the battle bit by bit),
 * and every reading of the game runs on the console thread, the only one that touches the core. Why not after the
 * action: a `lookup` made while a minute-long `go_to` runs would wait for the whole trip; it now waits one frame.
 *
 * No copy of the RAM is published at every frame (4 MB, 60 times a second, for reads that come a few times a minute):
 * the pending reads run on the frame they asked for, with that frame's RAM, read once for all of them.
 */
internal class FrameReads {
    private val pending = ConcurrentLinkedQueue<(Memory) -> Unit>()

    /** Runs [block] on the RAM of the next frame end (any thread but the console one), and returns its result. */
    suspend fun <T> read(block: (Memory) -> T): T {
        val result = CompletableDeferred<T>()
        pending.add { memory -> result.completeWith(runCatching { block(memory) }) }
        return result.await()
    }

    /**
     * Console thread, between two frames: serves the waiting reads with [memory] (the main RAM of the frame just done,
     * read only when a read is waiting).
     */
    fun serve(memory: () -> Memory) {
        if (pending.isEmpty()) return
        val ram = memory()
        while (true) pending.poll()?.invoke(ram) ?: break
    }
}
