package me.nathanfallet.aiplayspokemon.mcp

/**
 * Tells whether the answer of an MCP call reached the agent, so that what it carried (messages, events) is only
 * forgotten once delivered ([confirm], see `EventFeed`), and given again otherwise (`messages_repeated`).
 *
 * The verdict on a call is given when the next call arrives ([arrived]), with everything known by then. Its answer is
 * taken as lost when:
 * - the client cancelled it (`notifications/cancelled`: MCP clients send it when their request times out);
 * - another call arrived while it was still running, at least [minClientTimeoutMillis] after its last sign of life (the
 *   client gave up waiting and moved on; a call made in parallel arrives right away, not seconds later);
 * - it was answered more than [clientTimeoutMillis] after its last sign of life (the default timeout of MCP clients).
 * A sign of life is the call's start, or a progress notification sent for it (clients may restart their timeout on
 * each one). A call answered late but received (a long chain that ended in `not_done`) is therefore confirmed: the
 * old rule ("answered more than 50 s after the start = lost") repeated the messages of such calls.
 *
 * Every method runs under the server's call lock, except [alive] (progress, from the call's own ticker).
 */
class DeliveryTracker(
    private val confirm: () -> Unit,
    private val clientTimeoutMillis: Long = DEFAULT_CLIENT_TIMEOUT_MILLIS,
    private val minClientTimeoutMillis: Long = MIN_CLIENT_TIMEOUT_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One call: when it started, its last sign of life, when it was answered, and whether its answer is lost. */
    class Call internal constructor(internal val started: Long) {
        @Volatile internal var lastSignOfLife = started
        internal var answeredAt: Long? = null
        internal var lost = false
    }

    /** The last call whose answer has no verdict yet. */
    private var pending: Call? = null

    /** A new call arrived at [at] (taken before waiting for the call lock): gives the verdict on the previous one. */
    @Synchronized
    fun arrived(at: Long = now()) {
        val previous = pending ?: return
        val answered = previous.answeredAt
        if (answered != null && answered > at && at - previous.lastSignOfLife >= minClientTimeoutMillis) previous.lost = true
        if (answered == null) return // still running (only possible without the lock): judged when it ends
        if (!previous.lost) confirm()
        pending = null
    }

    /** A call starts (its answer carries what happened since the last confirmed one). */
    @Synchronized
    fun started(): Call = Call(now()).also { pending = it }

    /** A progress notification was sent for [call]: the client may restart its timeout. */
    fun alive(call: Call) {
        call.lastSignOfLife = now()
    }

    /** [call] was answered; [cancelled] when the client cancelled it meanwhile (its answer is never sent). */
    @Synchronized
    fun answered(call: Call, cancelled: Boolean) {
        val at = now()
        call.answeredAt = at
        if (cancelled || at - call.lastSignOfLife > clientTimeoutMillis) call.lost = true
    }

    companion object {
        /** MCP clients give up on a request after 60 s by default (the TypeScript SDK's DEFAULT_REQUEST_TIMEOUT_MSEC). */
        const val DEFAULT_CLIENT_TIMEOUT_MILLIS = 60_000L

        /** No client times out sooner than this: a call arriving earlier during another one was made in parallel. */
        const val MIN_CLIENT_TIMEOUT_MILLIS = 10_000L
    }
}
