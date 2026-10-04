package me.nathanfallet.aiplayspokemon.mcp

/**
 * What the external agent is doing, for the people watching (the panel today, the stream overlay later): the
 * action in progress with its reasoning as soon as the call arrives, then, once it's over, the last action with its
 * result. The comment is therefore shown while the action happens, not after.
 */
data class AgentActivity(
    val calls: Int = 0,
    /** The action running now, if any. */
    val current: Step? = null,
    /** The last finished action. */
    val last: Step? = null,
) {
    /** One `act` call: the actions (`heal → save_game`), the agent's one-sentence reasoning, and how it ended. */
    data class Step(val action: String, val reasoning: String?, val ok: Boolean? = null, val result: String? = null)

    enum class Phase { IN_PROGRESS, LAST }

    /** What to display: the action in progress, else the last one; null before the first call. */
    data class Shown(val phase: Phase, val action: String, val reasoning: String?, val result: String?)

    val shown: Shown?
        get() = current?.let { Shown(Phase.IN_PROGRESS, it.action, it.reasoning, null) }
            ?: last?.let { Shown(Phase.LAST, it.action, it.reasoning, it.result) }

    /** A call arrived: shown at once as in progress. */
    fun started(action: String, reasoning: String?) = copy(current = Step(action, reasoning))

    /** The call is over: [result] is null when it went well, else the error code. */
    fun finished(ok: Boolean, result: String?) =
        copy(calls = calls + 1, current = null, last = (current ?: Step("?", null)).copy(ok = ok, result = result))
}
