package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Why a chain of actions (an agent's action and its `then` steps) stopped before its end although no step failed.
 * Typed, so an agent can react without parsing text: the steps left are given back as not done.
 */
sealed interface ChainStop {
    /** Machine-readable reason (stable, used in agent APIs). */
    val code: String

    /** One sentence for the agent. */
    val message: String

    /** Nothing changed in the game for [idleSeconds]: the chain is stuck, better look at the state. */
    data class Idle(val idleSeconds: Long) : ChainStop {
        override val code = "IDLE"
        override val message get() = "nothing changed in the game for $idleSeconds s: check the state, then go on"
    }

    /** The call had already run [elapsedSeconds] (the safety cap, whatever the progress). */
    data class TimeCap(val elapsedSeconds: Long) : ChainStop {
        override val code = "TIME_CAP"
        override val message get() = "the call had already run $elapsedSeconds s: check the state, then go on"
    }

    /** The opponent at [position] is another Pokémon than when the chain started (switched by the AI, sent after a K.O.). */
    data class FoeChanged(val position: BattlerRef, val before: String?, val now: String?) : ChainStop {
        override val code = "FOE_CHANGED"
        override val message get() = "the opponent at ${position.wire} changed (${before ?: "nobody"} → ${now ?: "nobody"}): " +
            "the next steps were chosen against the previous one, choose again"
    }

    /** The opponent at [position], there when the chain started, fainted. */
    data class FoeFainted(val position: BattlerRef, val name: String) : ChainStop {
        override val code = "FOE_FAINTED"
        override val message get() = "the opponent's $name (${position.wire}) fainted: choose the next steps against what comes next"
    }

    /** One of the player's Pokémon that was battling when the chain started fainted. */
    data class OwnFainted(val mon: MonId, val name: String) : ChainStop {
        override val code = "OWN_FAINTED"
        override val message get() = "your $name ($mon) fainted: choose what to do next"
    }
}

/** How long a chain may go on: [idle] without any progress in the game, [total] in all whatever the progress. */
data class ChainLimits(val idle: Duration, val total: Duration)

/**
 * The battle as a chain found it, to stop the chain when what its steps were chosen for is gone: an opponent replaced
 * or fainted, or one of the player's battling Pokémon fainted. A switch the chain itself makes isn't a reason (only
 * faints count on the player's side).
 */
class BattleWatch private constructor(
    private val foes: Map<BattlerRef, BattlerState>,
    private val own: Map<MonId, String>,
) {
    /** Why the chain must stop in [now] (null: go on). Nothing once the battle is over (the next step decides). */
    fun check(now: GameState): ChainStop? {
        val battle = now.battle ?: return null
        val current = battle.battlers.filter { !it.ref.isPlayerSide }.associateBy { it.ref }
        for (ref in (foes.keys + current.keys).distinct().sortedBy { it.ordinal }) {
            val was = foes[ref]
            val isNow = current[ref]
            when {
                was == null && isNow == null -> Unit
                // A Pokémon fainted earlier in the chain's battle and its spot was empty: someone new came in.
                was == null -> if (isNow!!.hp > 0) return ChainStop.FoeChanged(ref, null, name(isNow))
                isNow == null -> return ChainStop.FoeChanged(ref, name(was), null)
                !same(was, isNow) -> return ChainStop.FoeChanged(ref, name(was), name(isNow))
                isNow.hp == 0 -> return ChainStop.FoeFainted(ref, name(was))
            }
        }
        val partyHp = now.party.associate { it.id to it.hp }
        val battlerHp = battle.battlers.mapNotNull { b -> b.mon?.let { it to b.hp } }.toMap()
        own.forEach { (mon, name) ->
            if ((battlerHp[mon] ?: partyHp[mon]) == 0) return ChainStop.OwnFainted(mon, name)
        }
        return null
    }

    companion object {
        /** The battle of [state] (null out of battle). Only Pokémon still standing are watched. */
        fun of(state: GameState): BattleWatch? {
            val battle = state.battle ?: return null
            val foes = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }.associateBy { it.ref }
            val own = battle.battlers.filter { it.ref.isPlayerSide && it.hp > 0 }.mapNotNull { b -> b.mon?.let { it to name(b) } }.toMap()
            return BattleWatch(foes, own)
        }

        private fun name(b: BattlerState) = b.nickname ?: b.species.name

        /** The same Pokémon: by personality when the game tells it, else by what is shown (species, level, nickname, max HP). */
        private fun same(a: BattlerState, b: BattlerState): Boolean =
            if (a.personality != null && b.personality != null) a.personality == b.personality
            else a.species.id == b.species.id && a.level == b.level && a.nickname == b.nickname && a.maxHp == b.maxHp
    }
}

/** What a chain did: the steps [performed] (keys) with their [details], the step that [failed], the steps [skipped] and why ([stop]). */
data class ChainResult(
    val performed: List<String>,
    val details: List<String>,
    val failed: Pair<GameAction, ActionError>?,
    val skipped: List<GameAction>,
    /** Why the chain stopped early without a failure (null when it ran to its end or a step failed). */
    val stop: ChainStop?,
)

/**
 * Runs a chain of actions as one sequence (see [ActionChains]): item uses merged into one bag session, a spare
 * `advance_dialogue` skipped, and stops:
 * - at the first failed step;
 * - before a step, when the battle changed under the chain ([BattleWatch]: the foe replaced or fainted, one of the
 *   player's Pokémon fainted);
 * - before a step, when the game made no progress for [ChainLimits.idle] ([idle], from the recorder's
 *   `ProgressClock`) or the chain has run [ChainLimits.total] in all: long chains go on while things keep happening.
 *
 * [observe] reads the state, [execute] carries out one step (with its index: the first one is checked against the
 * agent's version), [onStep] is told before each step starts (progress for remote agents).
 */
class ChainRunner(
    private val observe: suspend () -> GameState,
    private val execute: suspend (action: GameAction, index: Int) -> ActionOutcome,
    private val limits: ChainLimits? = null,
    private val idle: () -> Duration = { Duration.ZERO },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onStep: (index: Int, total: Int, action: GameAction) -> Unit = { _, _, _ -> },
) {
    suspend fun run(requested: List<GameAction>): ChainResult {
        require(requested.isNotEmpty()) { "no action" }
        val start = observe()
        val actions = ActionChains.coalesce(requested, inBattle = start.battle != null)
        val watch = BattleWatch.of(start)
        val started = timeSource.markNow()
        val performed = mutableListOf<String>()
        val details = mutableListOf<String>()
        for ((index, action) in actions.withIndex()) {
            if (index > 0) {
                stopBefore(watch, started)?.let { stop -> return ChainResult(performed, details, null, actions.drop(index), stop) }
            }
            onStep(index, actions.size, action)
            when (val outcome = execute(action, index)) {
                is ActionOutcome.Done -> {
                    performed += action.key
                    outcome.detail?.let { details += if (actions.size > 1) "${action.key}: $it" else it }
                }
                is ActionOutcome.Failed -> {
                    // A spare advance_dialogue (the dialogue ended sooner than expected): nothing to read, go on.
                    if (index > 0 && ActionChains.isSpareAdvance(action, outcome.error, observe())) {
                        details += "${action.key}: skipped, no dialogue left"
                        continue
                    }
                    return ChainResult(performed, details, action to outcome.error, actions.drop(index + 1), null)
                }
            }
        }
        return ChainResult(performed, details, null, emptyList(), null)
    }

    /** Why the next step mustn't start (see the class). */
    private suspend fun stopBefore(watch: BattleWatch?, started: kotlin.time.TimeMark): ChainStop? {
        watch?.check(observe())?.let { return it }
        val limits = limits ?: return null
        val elapsed = started.elapsedNow()
        if (elapsed >= limits.total) return ChainStop.TimeCap(elapsed.inWholeSeconds)
        val quiet = idle()
        if (quiet >= limits.idle) return ChainStop.Idle(quiet.inWholeSeconds)
        return null
    }
}
