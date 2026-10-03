package me.nathanfallet.aiplayspokemon.emulator

/**
 * Whether the person watching is really playing: keys held or the screen touched, or released less than
 * [graceMillis] ago (so an agent doesn't grab the game between two presses). The game merely running in real time
 * is NOT a human playing: "pause while thinking" lifts the freeze before every agent action.
 *
 * Thread-safe: inputs come from the UI thread, checks from the agents' coroutines.
 */
class HumanActivity(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    @Volatile private var keysHeld = false
    @Volatile private var touching = false
    @Volatile private var lastActiveAt = Long.MIN_VALUE

    private val active get() = keysHeld || touching

    /** The keys the human holds changed ([held]: at least one). */
    fun keys(held: Boolean) = update { keysHeld = held }

    /** The human touches the screen, or stopped. */
    fun touch(touching: Boolean) = update { this.touching = touching }

    /** Shortcut for both inputs at once (tests, simple callers). */
    fun input(active: Boolean) = update {
        keysHeld = active
        touching = false
    }

    @Synchronized
    private fun update(change: () -> Unit) {
        val wasActive = active
        change()
        // The grace period starts when the last input is released.
        if (wasActive || active) lastActiveAt = clock()
    }

    /** True while the human holds keys, or let go of them less than [graceMillis] ago. */
    fun isPlaying(): Boolean = active || (lastActiveAt != Long.MIN_VALUE && clock() - lastActiveAt < graceMillis)

    companion object {
        const val DEFAULT_GRACE_MILLIS = 2_000L
    }
}
