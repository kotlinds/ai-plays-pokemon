package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.runtime.ProgressClock
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.currentRequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How the MCP server runs the work of one tool call that reads or changes the game:
 * - the verdict on the previous call's answer first ([DeliveryTracker.arrived]), then the work;
 * - the work always runs to its end, even when the client cancels the call (its timeout): a half-done action would
 *   leave the console mid-menu, and the console thread finishes what it started anyway. The cancellation only marks
 *   the answer as lost, so what it carried is given again with the next one;
 * - while it runs, when the client gave a `progressToken`, a `notifications/progress` is sent every [period] (about
 *   5 s) for the whole call, a heartbeat: clients that restart their timeout on progress then never time out on a
 *   call that is still running. Its message is the clock's latest note when the game made progress meanwhile
 *   ([ProgressClock]: the last text shown, or how far a long action has got, "go_to Seafoam Islands 1F: 120/480
 *   tiles, Route 20", see `ActionProgress`), else that the call is still running and for how long ("attack(move:33):
 *   still running, 15 s"): an attack's turn, a cutscene or the Hall of Fame change nothing a reading sees for a
 *   while, and clients timed out on them when only progress was sent (race Claude vs Codex: `attack`, Ho-Oh's
 *   cutscene, `watch_hall_of_fame`, a 63 s chain).
 *
 * With the SDK's Streamable HTTP endpoint (JSON answers), notifications about a request are sent on the client's
 * standalone SSE stream (the GET it opens after initializing), where clients match them to the call by token.
 */
class ToolCalls(
    private val progress: ProgressClock,
    val delivery: DeliveryTracker,
    private val period: Duration = DEFAULT_PERIOD,
) {
    /**
     * Runs [block] for [request] (under the server's call lock), arrived at [arrival]. [connection] sends the progress
     * notifications; [label] names the call in the heartbeats (the action's key, "get_state").
     */
    suspend fun <T> run(connection: ClientConnection, request: CallToolRequest, arrival: Long, label: String? = null, block: suspend () -> T): T {
        delivery.arrived(arrival)
        val call = delivery.started()
        val job = currentCoroutineContext().job
        val token = request.meta?.progressToken
        val requestId = currentRequestHandlerExtra()?.requestId
        val result = withContext(NonCancellable) {
            coroutineScope {
                val ticker = token?.let {
                    launch {
                        val started = TimeSource.Monotonic.markNow()
                        val first = progress.ticks
                        var sent = first
                        var beats = 0
                        while (true) {
                            delay(period)
                            val ticks = progress.ticks
                            val moved = ticks != sent
                            sent = ticks
                            beats++
                            try {
                                // Only a note of this call (not the previous call's last message or trip), when the
                                // game moved since the last one; else the heartbeat.
                                val note = progress.note.takeIf { moved && progress.noteTick > first }
                                val message = note ?: heartbeat(label, started.elapsedNow())
                                val params = ProgressNotificationParams(token, beats.toDouble(), message = message)
                                connection.notification(ProgressNotification(params), requestId)
                                // A sign of life only when the game moved: a heartbeat is sent all the same (so a
                                // client restarting its timeout on progress keeps waiting), but counting it would make
                                // every call look alive to its end, and an answer arriving after the client gave up
                                // without cancelling (an HTTP timeout) would never be taken as lost (DeliveryTracker).
                                if (moved) delivery.alive(call)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // No stream to send it on (the client didn't open one, or it closed): the call goes on.
                            }
                        }
                    }
                }
                try {
                    block()
                } finally {
                    ticker?.cancel()
                }
            }
        }
        delivery.answered(call, cancelled = job.isCancelled)
        return result
    }

    companion object {
        /** The message of a heartbeat: the call still runs, nothing new to tell ("attack(move:33): still running, 15 s"). */
        internal fun heartbeat(label: String?, elapsed: Duration): String =
            (label?.let { "$it: " } ?: "") + "still running, ${elapsed.inWholeSeconds} s"

        /**
         * How often a long call says how it goes: often enough for a client's timeout (60 s by default), rarely enough
         * not to flood the agent's transcript (one line per notification in some clients).
         */
        val DEFAULT_PERIOD: Duration = 5.seconds
    }
}
