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
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the MCP server runs the work of one tool call that reads or changes the game:
 * - the verdict on the previous call's answer first ([DeliveryTracker.arrived]), then the work;
 * - the work always runs to its end, even when the client cancels the call (its timeout): a half-done action would
 *   leave the console mid-menu, and the console thread finishes what it started anyway. The cancellation only marks
 *   the answer as lost, so what it carried is given again with the next one;
 * - while it runs, when the client gave a `progressToken`, a `notifications/progress` is sent each time the game made
 *   progress ([ProgressClock]: a new message, a step, a menu moving), at most every [period]: clients that restart
 *   their timeout on progress then only time out on a call that is really stuck.
 *
 * With the SDK's Streamable HTTP endpoint (JSON answers), notifications about a request are sent on the client's
 * standalone SSE stream (the GET it opens after initializing), where clients match them to the call by token.
 */
class ToolCalls(
    private val progress: ProgressClock,
    val delivery: DeliveryTracker,
    private val period: Duration = 500.milliseconds,
) {
    /**
     * Runs [block] for [request] (under the server's call lock), arrived at [arrival]. [connection] sends the progress
     * notifications.
     */
    suspend fun <T> run(connection: ClientConnection, request: CallToolRequest, arrival: Long, block: suspend () -> T): T {
        delivery.arrived(arrival)
        val call = delivery.started()
        val job = currentCoroutineContext().job
        val token = request.meta?.progressToken
        val requestId = currentRequestHandlerExtra()?.requestId
        val result = withContext(NonCancellable) {
            coroutineScope {
                val ticker = token?.let {
                    launch {
                        val first = progress.ticks
                        var sent = first
                        while (true) {
                            delay(period)
                            val ticks = progress.ticks
                            if (ticks == sent) continue
                            sent = ticks
                            try {
                                val params = ProgressNotificationParams(token, (ticks - first).toDouble(), message = progress.note)
                                connection.notification(ProgressNotification(params), requestId)
                                delivery.alive(call)
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
}
