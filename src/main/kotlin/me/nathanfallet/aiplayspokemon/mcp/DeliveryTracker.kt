package me.nathanfallet.aiplayspokemon.mcp

import kotlinx.serialization.Serializable

/**
 * Tells whether the answer of an MCP call reached the agent, so that what it carried (messages, events) is only
 * forgotten once delivered ([confirm], see `EventFeed`), and given again otherwise (`messages_repeated`).
 *
 * The verdict on a call is given when the next call arrives ([arrived]), with everything known by then. Its answer is
 * taken as lost when:
 * - the client cancelled it (`notifications/cancelled`: MCP clients send it when their request times out);
 * - another call arrived while it was still running, at least [minClientTimeoutMillis] after it started (the client
 *   gave up waiting and moved on; a call made in parallel arrives right away, not seconds later). Its start, not its
 *   last sign of life: a long `go_to` sends progress until its end, and a client that gave up without cancelling (an
 *   HTTP timeout) must still be noticed;
 * - it was answered more than [clientTimeoutMillis] after its last sign of life (the default timeout of MCP clients).
 * A sign of life is the call's start, or a progress notification sent for it while the game moved (clients may
 * restart their timeout on each one); a mere heartbeat ("still running") is not one: sent every few seconds to the
 * end of every call, it would keep every answer alive and none would ever be taken as lost ([ToolCalls]). A call answered late but received (a long chain that ended in `not_done`) is therefore confirmed: the
 * old rule ("answered more than 50 s after the start = lost") repeated the messages of such calls.
 *
 * Between the two, a call answered more than [clientTimeoutMillis] after its START, kept alive by progress, the next
 * call arriving only after it ended, is uncertain ([uncertain] instead of [confirm]): a client that restarts its
 * timeout on progress got it, one that doesn't (or gave up without cancelling: an HTTP timeout) didn't, and nothing
 * here tells them apart. Its messages are not repeated (most clients got them), but the outcome of the acts is given
 * once more (NOTES, map randomizer run: after a client timeout on a long chain, a flee then Teleport through the menus
 * then three go_to, ended before the agent's next call, `previous_calls` was missing and the agent never learned
 * which steps ran).
 *
 * Only `get_state` and `act` take part: their answers carry the messages, events and outcomes. `lookup` and
 * `screenshot` are reads outside them (no lock, see `GameMcpServer`): they never give, take or change a verdict.
 *
 * Every verdict other than "confirmed" (lost, uncertain) is told to [onVerdict] with the call's tool and durations
 * ([DeliveryVerdict], logged by [DeliveryLog]): measured over the next runs, now that every long call sends
 * heartbeats, to decide whether these timing rules can go.
 *
 * Every method runs under the server's call lock, except [alive] (progress, from the call's own ticker).
 */
class DeliveryTracker(
    private val confirm: () -> Unit,
    /** The verdict on an answer that may not have arrived (see above): what it carried is only partly confirmed. */
    private val uncertain: () -> Unit = confirm,
    /** Told every verdict "lost" or "uncertain" (the measurement, see above). */
    private val onVerdict: (DeliveryVerdict) -> Unit = {},
    private val clientTimeoutMillis: Long = DEFAULT_CLIENT_TIMEOUT_MILLIS,
    private val minClientTimeoutMillis: Long = MIN_CLIENT_TIMEOUT_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * One call of [tool] (its label: `get_state`, the act's actions): when it started, its last sign of life, when it
     * was answered, and why its answer is lost (null: not lost).
     */
    class Call internal constructor(internal val tool: String, internal val started: Long) {
        @Volatile internal var lastSignOfLife = started
        internal var answeredAt: Long? = null
        internal var lostBecause: DeliveryVerdict.Reason? = null
    }

    /** The last call whose answer has no verdict yet. */
    private var pending: Call? = null

    /** Verdicts given so far (confirmed ones too): the measurement's denominator. */
    private var judged = 0

    /** A new call arrived at [at] (taken before waiting for the call lock): gives the verdict on the previous one. */
    @Synchronized
    fun arrived(at: Long = now()) {
        val previous = pending ?: return
        val answered = previous.answeredAt
        if (answered != null && answered > at && at - previous.started >= minClientTimeoutMillis && previous.lostBecause == null) {
            previous.lostBecause = DeliveryVerdict.Reason.NEXT_CALL_WHILE_RUNNING
        }
        if (answered == null) return // still running (only possible without the lock): judged when it ends
        judged++
        val lost = previous.lostBecause
        when {
            lost != null -> report(previous, answered, at, lost)
            answered - previous.started > clientTimeoutMillis -> {
                uncertain()
                report(previous, answered, at, DeliveryVerdict.Reason.KEPT_ALIVE_PAST_TIMEOUT)
            }
            else -> confirm()
        }
        pending = null
    }

    private fun report(call: Call, answered: Long, nextArrival: Long, reason: DeliveryVerdict.Reason) = onVerdict(
        DeliveryVerdict(
            tool = call.tool,
            reason = reason,
            verdict = reason.verdict,
            ranMillis = answered - call.started,
            silentMillis = answered - call.lastSignOfLife,
            nextCallAfterMillis = nextArrival - call.started,
            judged = judged,
        ),
    )

    /** A call of [tool] starts (its answer carries what happened since the last confirmed one). */
    @Synchronized
    fun started(tool: String): Call = Call(tool, now()).also { pending = it }

    /** A progress notification telling the game moved was sent for [call]: the client may restart its timeout. */
    fun alive(call: Call) {
        call.lastSignOfLife = now()
    }

    /** [call] was answered; [cancelled] when the client cancelled it meanwhile (its answer is never sent). */
    @Synchronized
    fun answered(call: Call, cancelled: Boolean) {
        val at = now()
        call.answeredAt = at
        call.lostBecause = when {
            cancelled -> DeliveryVerdict.Reason.CANCELLED
            at - call.lastSignOfLife > clientTimeoutMillis -> DeliveryVerdict.Reason.SILENT_PAST_TIMEOUT
            else -> null
        }
    }

    companion object {
        /** MCP clients give up on a request after 60 s by default (the TypeScript SDK's DEFAULT_REQUEST_TIMEOUT_MSEC). */
        const val DEFAULT_CLIENT_TIMEOUT_MILLIS = 60_000L

        /** No client times out sooner than this: a call arriving earlier during another one was made in parallel. */
        const val MIN_CLIENT_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * A verdict "lost" or "uncertain" on the answer of a call ([DeliveryTracker]), with what it was decided on: the
 * measurement that tells, after a few runs with heartbeats on every long call, whether the timing rules still catch
 * anything. One JSON line each in the run's delivery log ([DeliveryLog]).
 */
@Serializable
data class DeliveryVerdict(
    /** The call's label: `get_state`, or the act's actions (`go_to → attack`). */
    val tool: String,
    val reason: Reason,
    val verdict: Verdict,
    /** From the call's start (once it held the call lock) to its answer. */
    val ranMillis: Long,
    /** From its last sign of life (its start, or progress of the game) to its answer. */
    val silentMillis: Long,
    /** From its start to the arrival of the next call, the one that gave this verdict (negative: arrived while queued). */
    val nextCallAfterMillis: Long,
    /** Verdicts given so far in this server's session, confirmed ones included (this one counted). */
    val judged: Int,
) {
    enum class Verdict {
        /** Taken as not received: its messages and outcome are given again. */
        LOST,

        /** Maybe not received: its outcome is given again, not its messages. */
        UNCERTAIN,
    }

    /** Which rule gave the verdict. */
    enum class Reason(val verdict: Verdict) {
        /** The client cancelled the call (`notifications/cancelled`, sent on its timeout). */
        CANCELLED(Verdict.LOST),

        /** The next call arrived while it still ran, long enough after its start: the client gave up and moved on. */
        NEXT_CALL_WHILE_RUNNING(Verdict.LOST),

        /** Answered more than the client timeout after its last sign of life. */
        SILENT_PAST_TIMEOUT(Verdict.LOST),

        /** Answered more than the client timeout after its start, kept alive by progress: depends on the client. */
        KEPT_ALIVE_PAST_TIMEOUT(Verdict.UNCERTAIN),
    }

    /** One line for the console ("[delivery] lost (CANCELLED): go_to(warp:3) ran 72 s, ..."). */
    val summary: String
        get() = "[delivery] ${verdict.name.lowercase()} (${reason.name}): $tool ran ${ranMillis / 1000} s, " +
            "silent for the last ${silentMillis / 1000} s, next call ${nextCallAfterMillis / 1000} s after its start " +
            "($judged answers judged so far)"
}
