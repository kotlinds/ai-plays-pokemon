package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.Interruption
import dev.kotlinds.pokemonclient.state.GameState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.reflect.KClass

/** An action type: its spec (what agents see) and its recipe (how it is done). */
class ActionDefinition<A : GameAction>(
    val type: KClass<A>,
    val spec: ActionSpec<A>,
    val plan: ActionPlan<A>,
)

/** An action usable now, as listed to agents. */
data class AvailableAction(val name: String, val description: String, val choices: Map<String, List<Choice>>)

/** An action listed but not usable now, with why. */
data class UnavailableAction(val name: String, val reason: UnavailableReason, val detail: String, val hint: String?)

/**
 * The single source of truth of the actions: every consumer (the MCP server, our LLM loop, Jev, the pure-buttons
 * mode) gets its JSON schema, the list of what is possible now, and executes through here. Execution always
 * checks availability first, then runs the recipe, and turns interruptions into typed errors.
 *
 * @param definitions the common recipes ([CommonActions.definitions]) with the game's own overrides applied.
 */
class ActionRegistry(private val definitions: List<ActionDefinition<*>>) {

    private val byName = definitions.associateBy { it.spec.name }

    /** Actions of [mode], usable now, with their valid parameter values. */
    fun available(state: GameState, mode: ActionMode): List<AvailableAction> = definitions
        .filter { mode in it.spec.modes }
        .mapNotNull { def ->
            (def.spec.availability(state) as? Availability.Available)?.takeIf { it.listed }?.let { AvailableAction(def.spec.name, def.spec.description, it.choices) }
        }

    /** Actions of [mode] shown but not usable now, with the reason (e.g. "nobody knows Fly"). */
    fun unavailable(state: GameState, mode: ActionMode): List<UnavailableAction> = definitions
        .filter { mode in it.spec.modes }
        .mapNotNull { def ->
            (def.spec.availability(state) as? Availability.Unavailable)?.let { UnavailableAction(def.spec.name, it.reason, it.detail, it.hint) }
        }

    /** Every concrete action worth offering now, by canonical key (for models that pick from a list). */
    fun enumerate(state: GameState, mode: ActionMode): Map<String, GameAction> = definitions
        .filter { mode in it.spec.modes && (it.spec.availability(state) as? Availability.Available)?.listed == true }
        .flatMap { it.spec.enumerate(state) }
        .associateBy { it.key }

    /** Parses a wire action `{"type": "...", ...}` into a typed action, or a typed error. */
    fun parse(json: JsonObject, mode: ActionMode): Result<GameAction> {
        val type = json["type"]?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(ActionException(ActionError.InvalidParameter("type", "missing", byName.keys.toList())))
        val def = byName[type]?.takeIf { mode in it.spec.modes }
            ?: return Result.failure(ActionException(ActionError.InvalidParameter("type", type, definitions.filter { mode in it.spec.modes }.map { it.spec.name })))
        // A misspelled parameter would otherwise be ignored silently (e.g. `count` for `tiles`): refuse it, listing the valid ones.
        val known = def.spec.parameters.map { it.name }
        json.keys.firstOrNull { it != "type" && it !in known }?.let { unknown ->
            return Result.failure(ActionException(ActionError.InvalidParameter("parameter", unknown, known)))
        }
        return runCatching { def.spec.parse(json) }.recoverCatching { error ->
            throw (error as? ActionException) ?: ActionException(ActionError.InvalidParameter(type, json.toString()))
        }
    }

    /**
     * Executes [action] with the console leased to [scope]: checks it is available now, runs its recipe, and
     * reports interruptions (a battle starting, the human taking over...) as typed errors. [settings]: what the
     * application lets the recipes do by themselves (solve movement puzzles, use hidden knowledge).
     */
    fun execute(action: GameAction, scope: ActionScope, game: PokemonGame, settings: ActionSettings = ActionSettings()): ActionOutcome {
        val def = definitions.firstOrNull { it.type.isInstance(action) }
            ?: return ActionOutcome.Failed(ActionError.Unsupported(action.key))
        val context = PlanContext(scope, game, settings = settings)
        when (val availability = def.spec.availability(context.state())) {
            is Availability.Unavailable -> {
                // Fly refused here: name the nearest place where it works (computed only when asked, it routes).
                val hint = if (availability.reason == UnavailableReason.NOT_FLYABLE_HERE) FlyHints.nearestFlyable(context) ?: availability.hint else availability.hint
                return ActionOutcome.Failed(ActionError.Unavailable(availability.reason, availability.detail, hint))
            }
            Availability.Hidden -> return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "${def.spec.name} isn't possible on this screen"))
            is Availability.Available -> Unit
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            (def.plan as ActionPlan<GameAction>).run(action, context)
        } catch (interrupted: ActionInterruptedException) {
            val cause = if (interrupted.reason == Interruption.HUMAN) InterruptionCause.HUMAN else InterruptionCause.SCRIPT
            ActionOutcome.Failed(ActionError.Interrupted(cause, action.key))
        } catch (error: ActionException) {
            ActionOutcome.Failed(error.error)
        }
    }

    /** JSON Schema of the wire actions of [mode] (one object per type, discriminated by `type`). */
    fun jsonSchema(mode: ActionMode): JsonObject = buildJsonObject {
        putJsonArray("oneOf") {
            definitions.filter { mode in it.spec.modes }.forEach { def ->
                add(buildJsonObject {
                    put("type", "object")
                    put("description", def.spec.description)
                    putJsonObject("properties") {
                        putJsonObject("type") { put("const", def.spec.name) }
                        def.spec.parameters.forEach { p ->
                            putJsonObject(p.name) {
                                put("type", p.type.name.lowercase())
                                if (p.type == ParameterType.ARRAY) putJsonObject("items") { put("type", "object") }
                                put("description", p.description)
                                if (p.values.isNotEmpty()) put("enum", JsonArray(p.values.map(::JsonPrimitive)))
                            }
                        }
                    }
                    put("required", JsonArray((listOf("type") + def.spec.parameters.filter { it.required }.map { it.name }).map(::JsonPrimitive)))
                })
            }
        }
    }

    companion object {
        /** The common actions, with [overrides] replacing the recipe of some action types for one game. */
        fun of(overrides: Map<KClass<out GameAction>, ActionPlan<out GameAction>> = emptyMap()): ActionRegistry =
            ActionRegistry(CommonActions.definitions.map { def ->
                @Suppress("UNCHECKED_CAST")
                val override = overrides[def.type] as ActionPlan<GameAction>?
                if (override == null) def else ActionDefinition(def.type as KClass<GameAction>, def.spec as ActionSpec<GameAction>, override)
            })
    }
}

/** Carries a typed [ActionError] out of parsing or a recipe. */
class ActionException(val error: ActionError) : RuntimeException(error.message)
