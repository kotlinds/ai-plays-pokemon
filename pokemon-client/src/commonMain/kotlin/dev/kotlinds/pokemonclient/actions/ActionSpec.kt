package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.GameState
import kotlinx.serialization.json.JsonObject

/**
 * One action type as agents see it: its wire name, description, parameters, when it is available, how to parse
 * it from JSON and how to enumerate its concrete instances (for models that pick among a list, like Jev).
 */
interface ActionSpec<A : GameAction> {
    /** Wire name, e.g. `attack` (the JSON `type`). */
    val name: String

    /** What the action does, for the agent. */
    val description: String

    /** Parameters, for the JSON schema. */
    val parameters: List<Parameter>

    /** Modes where the action is offered. */
    val modes: Set<ActionMode>

    /** Whether the action can be used now, and with which parameter values. */
    fun availability(state: GameState): Availability

    /** Parses the JSON parameters (already validated as an object with this `type`). */
    fun parse(json: JsonObject): A

    /** Concrete instances worth offering now (empty when the action takes free parameters only). */
    fun enumerate(state: GameState): List<A> = emptyList()
}

/** Which actions an agent may use. */
enum class ActionMode {
    /** Raw buttons and touches only, like a human holding the console. */
    PURE,

    /** Every action, carried out by the server (navigation, menus, battles...). */
    ASSISTED,
}

/** One parameter of an action. */
data class Parameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    /** Allowed values when the set is fixed (enums). */
    val values: List<String> = emptyList(),
)

/** JSON types of parameters. [ARRAY] is a list of objects (e.g. several item uses). */
enum class ParameterType { STRING, INTEGER, BOOLEAN, ARRAY }

/** Whether an action is usable now. */
sealed interface Availability {
    /**
     * Usable; [choices] lists valid values per parameter when they depend on the situation (moves, Pokémon...).
     * Not [listed]: accepted (typically chained in a sequence) but not worth offering on this screen (e.g.
     * advance_dialogue on a choice, a no-op; keep_battling while the messages before the question still scroll).
     */
    data class Available(val choices: Map<String, List<Choice>> = emptyMap(), val listed: Boolean = true) : Availability

    /** Visible but not usable now, with the typed reason (e.g. Fly when nobody knows it). */
    data class Unavailable(val reason: UnavailableReason, val detail: String, val hint: String? = null) : Availability

    /** Meaningless in this situation (attack outside a battle): not listed. */
    data object Hidden : Availability
}

/** A valid value of a parameter, with what it is. */
data class Choice(val value: String, val label: String)
