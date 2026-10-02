package me.nathanfallet.aiplayspokemon.decision.claudecode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmAnswerFormat
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [DecisionModel] backed by Claude through the Claude Code CLI in headless mode (`claude -p`).
 *
 * This uses the Claude account the CLI is logged in with (e.g. a Claude subscription) instead of an
 * API key. Each decision starts the CLI once (a few seconds of overhead), with every tool disabled
 * and a JSON schema forcing the answer format ([LlmAnswerFormat]).
 *
 * Calls count towards the account's usage limits.
 */
class ClaudeCodeDecisionModel(
    /** A Claude Code model alias or id: "sonnet", "opus", "haiku", "claude-opus-5-5"... */
    private val modelId: String,
    /** Optional effort level ("low", "medium", "high"...); null keeps the CLI default. */
    private val effort: String? = null,
    /**
     * Model used when [modelId]'s safeguards refuse a request: these false positives happen now and
     * then on perfectly normal game states ("This sometimes happens with safe, normal conversations").
     */
    private val fallbackModelId: String? = "claude-sonnet-5",
    private val executable: String = "claude",
) : DecisionModel {

    override val name = "Claude Code · $modelId${effort?.let { " ($it effort)" } ?: ""}"

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        // Safeguard refusals are intermittent: retry once, then switch to the fallback model.
        val attempts = listOfNotNull(modelId, modelId, fallbackModelId?.takeIf { it != modelId })
        var refusal: SafeguardRefusal? = null
        for (model in attempts) {
            try {
                return run(request, model)
            } catch (error: SafeguardRefusal) {
                refusal = error
            }
        }
        throw DecisionException(refusal?.message ?: "Claude Code refused the request", retryable = true)
    }

    private class SafeguardRefusal(message: String) : Exception(message)

    private suspend fun run(request: ChoiceRequest, model: String): ChoiceResult = withContext(Dispatchers.IO) {
        val command = buildList {
            add(executable)
            add("-p")
            addAll(listOf("--model", model))
            addAll(listOf("--output-format", "json"))
            add("--no-session-persistence")
            add("--strict-mcp-config") // no MCP servers
            addAll(listOf("--setting-sources", "")) // no user/project settings, hooks or CLAUDE.md
            addAll(listOf("--tools", "")) // no tools: just answer
            addAll(listOf("--system-prompt", LlmAnswerFormat.instructions(request)))
            addAll(listOf("--json-schema", LlmAnswerFormat.schema(request).toString()))
            effort?.let { addAll(listOf("--effort", it)) }
            add("State:\n${json.encodeToString(JsonObject.serializer(), request.state)}")
        }
        val process = try {
            ProcessBuilder(command)
                .directory(File(System.getProperty("java.io.tmpdir"))) // keep it away from any project
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        } catch (error: Exception) {
            throw DecisionException("Can't run `$executable`: is Claude Code installed? (${error.message})", retryable = false, cause = error)
        }
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw DecisionException("Claude Code timed out", retryable = true)
        }

        val result = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: throw DecisionException("Claude Code failed: ${(errors.ifBlank { output }).take(300)}", retryable = process.exitValue() != 1)
        if (result["is_error"]?.jsonPrimitive?.contentOrNull == "true") {
            val message = result["result"]?.jsonPrimitive?.contentOrNull ?: output.take(300)
            if (message.contains("safeguards", ignoreCase = true)) throw SafeguardRefusal("Claude Code: $message")
            val fatal = listOf("login", "auth", "not logged", "invalid model").any { message.contains(it, ignoreCase = true) }
            throw DecisionException("Claude Code: $message", retryable = !fatal)
        }

        val answer = try {
            (result["structured_output"] as? JsonObject)?.let { LlmAnswerFormat.parse(it, request) }
                ?: LlmAnswerFormat.parse(result["result"]?.jsonPrimitive?.contentOrNull.orEmpty(), request)
        } catch (invalid: LlmAnswerFormat.InvalidAnswer) {
            throw DecisionException("Claude Code gave an invalid answer: ${invalid.message}", retryable = true)
        }
        val usage = result["usage"]?.jsonObject
        val inputTokens = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
            .sumOf { usage?.get(it)?.jsonPrimitive?.intOrNull ?: 0 }
        ChoiceResult(
            choice = answer.choice,
            probabilities = request.options.keys.associateWith { if (it == answer.choice) 1.0 else 0.0 },
            confidence = null,
            model = "claude-code/$model",
            inputTokens = inputTokens,
            thought = answer.reasoning,
            then = answer.then,
            note = answer.note,
            costUsd = result["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
