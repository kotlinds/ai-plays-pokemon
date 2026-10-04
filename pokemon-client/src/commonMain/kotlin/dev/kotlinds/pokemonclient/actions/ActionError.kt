package dev.kotlinds.pokemonclient.actions

/**
 * Why an action didn't happen or stopped: always explicit, never a silent no-op. Agents get the [code] and the
 * details, so they can decide what to do next.
 */
sealed interface ActionError {
    /** Machine-readable error code (stable, used in agent APIs). */
    val code: String

    /** One human-readable sentence. */
    val message: String

    /** The action can't be done in the current situation ([reason]), with a [hint] on how to get there. */
    data class Unavailable(val reason: UnavailableReason, val detail: String, val hint: String? = null) : ActionError {
        override val code = "NOT_AVAILABLE"
        override val message get() = detail + (hint?.let { ". $it" } ?: "")
    }

    /** A parameter is invalid; [allowed] lists valid values when there are few. */
    data class InvalidParameter(val parameter: String, val value: String, val allowed: List<String> = emptyList()) : ActionError {
        override val code = "INVALID_PARAM"
        override val message get() = "Invalid $parameter `$value`" + if (allowed.isEmpty()) "" else ". Allowed: ${allowed.joinToString()}"
    }

    /** The game shows something else than the screen the action works on. */
    data class UnexpectedScreen(val expected: String, val actual: String) : ActionError {
        override val code = "UNEXPECTED_SCREEN"
        override val message get() = "Expected $expected, but the screen is $actual"
    }

    /** The entry to choose isn't on the screen. */
    data class NotOnScreen(val target: String, val screen: String, val entries: List<String>) : ActionError {
        override val code = "NOT_ON_SCREEN"
        override val message get() = "No $target on $screen (entries: ${entries.joinToString()})"
    }

    /** The entry is shown but the game refuses it (fainted Pokémon, no PP...). */
    data class NotSelectable(val target: String, val label: String) : ActionError {
        override val code = "NOT_SELECTABLE"
        override val message get() = "$label can't be chosen for $target"
    }

    /** The move to forget is an HM: the game never lets an HM be forgotten (only a Move Deleter can). */
    data class HmCannotForget(val move: String) : ActionError {
        override val code = "HM_CANNOT_FORGET"
        override val message get() = "$move is an HM move: it can't be forgotten. Choose another move to forget, or keep the old moves (no `forget`)"
    }

    /**
     * One step of a batch (several item uses in one action) failed: the steps before it are done ([done]), the ones
     * after it were not tried. [code] is the failed step's own code.
     */
    data class BatchStepFailed(val step: Int, val stepKey: String, val done: List<String>, val cause: ActionError) : ActionError {
        override val code get() = cause.code
        override val message get() = "Step ${step + 1} ($stepKey) failed: ${cause.message}" +
            if (done.isEmpty()) "" else ". Done before it: ${done.joinToString("; ")}"
    }

    /** The cursor could not be brought onto the target (the verification rule refused to confirm). */
    data class VerificationFailed(val step: String, val expected: String, val actual: String, val attempts: Int) : ActionError {
        override val code = "VERIFICATION_FAILED"
        override val message get() = "Couldn't select $step: expected $expected, the cursor is on $actual (after $attempts correction(s))"
    }

    /** No sequence of D-pad presses reaches the target from where the cursor is. */
    data class Unreachable(val step: String, val target: String) : ActionError {
        override val code = "UNREACHABLE"
        override val message get() = "$target can't be reached with the D-pad for $step"
    }

    /** Something happened before the action could finish (a battle started, the human took over...). */
    data class Interrupted(val by: InterruptionCause, val performed: String) : ActionError {
        override val code = "INTERRUPTED"
        override val message get() = "Interrupted by ${by.name.lowercase().replace('_', ' ')} after: $performed"
    }

    /** The game didn't reach the expected point in time. */
    data class Timeout(val detail: String) : ActionError {
        override val code = "TIMEOUT"
        override val message get() = detail
    }

    /** The state changed since the agent last looked: the action was refused, see the new state. */
    data class StaleState(val expectedVersion: Long, val actualVersion: Long) : ActionError {
        override val code = "STALE_STATE"
        override val message get() = "Something happened since your last look (state $expectedVersion → $actualVersion): read the new state first"
    }

    /** A human is playing right now. */
    data object HumanDriving : ActionError {
        override val code = "HUMAN_DRIVING"
        override val message = "A human is playing right now: wait until they stop"
    }

    /** The person watching paused the game: nothing can be done until they resume it (the state can still be read). */
    data object PausedByHuman : ActionError {
        override val code = "PAUSED"
        override val message = "The game is paused by the person watching: wait until they resume it"
    }

    /** This game doesn't support the action yet. */
    data class Unsupported(val action: String) : ActionError {
        override val code = "UNSUPPORTED"
        override val message get() = "$action isn't supported for this game yet"
    }
}

/** Why an action is unavailable (typed, so agents can react without parsing text). */
enum class UnavailableReason {
    WRONG_SCREEN,

    /** The story hasn't unlocked it yet (the start menu at the very start of a new game). */
    NOT_UNLOCKED_YET,
    NOT_IN_BATTLE,
    IN_BATTLE,
    TRAINER_BATTLE,
    TRAPPED,
    TARGET_FAINTED,
    TARGET_IS_EGG,
    ALREADY_ACTIVE,
    NO_PP,
    NO_STOCK,
    UNKNOWN_MOVE,
    UNKNOWN_ITEM,
    UNKNOWN_POKEMON,
    PARTY_FULL,
    HM_CANNOT_FORGET,
    MAIL_BLOCKS_DEPOSIT,
    NEEDS_BADGE,
    NO_POKEMON_KNOWS_MOVE,
    NOT_FLYABLE_HERE,
    NOT_VISITED,
    OTHER_REGION,
    NOT_FACING_WATER,
    NO_PATH,
    NOT_ENOUGH_MONEY,

    /** The game refused it: "It won't have any effect". */
    NO_EFFECT,

    /** It would leave the party without a Pokémon able to battle. */
    LAST_POKEMON,
}

/** What interrupted an action. */
enum class InterruptionCause { WILD_BATTLE, TRAINER_SIGHT, PHONE_CALL, SCRIPT, UNKNOWN_SCREEN, HUMAN }
