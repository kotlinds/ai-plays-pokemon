package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.GameAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure ↔ Assisted within one MCP session: `act`'s schema is listed with the mode of the moment the client connected
 * (one server per session, see ReasoningRequiredTest), but its validation ([GameMcpServer.actSteps], what the `act`
 * tool runs on the call's arguments) reads the mode at every call: a switch applies at the next call, whatever schema
 * the client listed. (The handler around it needs a running console: not built in these tests.)
 */
class ActModeTest {
    private val registry = ActionRegistry.of()
    private val attack = JsonObject(mapOf("type" to JsonPrimitive("attack"), "move" to JsonPrimitive("move:33")))
    private val press = JsonObject(mapOf("type" to JsonPrimitive("press"), "button" to JsonPrimitive("a")))

    private fun types(mode: ActionMode) = registry.jsonSchema(mode)["oneOf"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject["type"]!!.jsonObject["const"]!!.jsonPrimitive.content }

    @Test
    fun theSchemaListedAtConnectionIsTheModesOfThatMoment() {
        assertTrue("attack" in types(ActionMode.ASSISTED))
        assertTrue("attack" !in types(ActionMode.PURE))
        assertTrue("press" in types(ActionMode.PURE) && "press" in types(ActionMode.ASSISTED))
    }

    /** The arguments of an `act` call: [action], then [then]. */
    private fun call(action: JsonObject?, vararg then: JsonObject) = JsonObject(buildMap {
        action?.let { put("action", it) }
        if (then.isNotEmpty()) put("then", JsonArray(then.toList()))
        put("reasoning", JsonPrimitive("test"))
    })

    @Test
    fun validationFollowsTheModeAtEachCall() {
        // Connected in Assisted, then switched to Pure: the same call is now refused, with Pure's actions.
        assertIs<GameAction.Attack>(GameMcpServer.actSteps(registry, call(attack), ActionMode.ASSISTED).getOrThrow().single())
        val refused = GameMcpServer.actSteps(registry, call(press, attack), ActionMode.PURE).exceptionOrNull()?.message.orEmpty()
        assertTrue("attack" in refused && "press" in refused && refused.endsWith("Call get_state for the valid actions."), refused)
        // Connected in Pure, then switched to Assisted: accepted although the client's schema doesn't list it.
        assertEquals(listOf(GameAction.Press::class, GameAction.Attack::class), GameMcpServer.actSteps(registry, call(press, attack), ActionMode.ASSISTED).getOrThrow().map { it::class })
    }

    @Test
    fun theCallsArgumentsAreReadAsBefore() {
        // No `action`: the same error as before, whatever the mode.
        for (mode in ActionMode.entries) {
            assertEquals("`action` must be an object like {\"type\": \"press\", \"button\": \"a\"}", GameMcpServer.actSteps(registry, call(null), mode).exceptionOrNull()?.message)
        }
        // `then` is cut at ChainRunner.MAX_THEN steps.
        val steps = GameMcpServer.actSteps(registry, call(press, *Array(ChainRunner.MAX_THEN + 3) { press }), ActionMode.PURE).getOrThrow()
        assertEquals(1 + ChainRunner.MAX_THEN, steps.size)
    }
}
