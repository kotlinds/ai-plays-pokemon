package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import dev.kotlinds.pokemonclient.actions.ChainRunner
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult

/**
 * How a generative model (LLM) answers a [ChoiceRequest], shared by every LLM backend.
 *
 * Generative models write text, so we ask for one JSON object:
 * `{"reasoning": "...", "note": "...", "choice": "<option id>", "then": ["<option id>", ...]}`
 * - `reasoning` (when [ChoiceRequest.reasoning]) comes first so the model thinks before choosing;
 * - `note` (when [ChoiceRequest.allowNote]) is its persistent goal/plan, shown back next time;
 * - `then` (when [ChoiceRequest.allowSequence]) are further options to execute after `choice`.
 *
 * Parsing is strict: only the `choice` field counts (normalized for harmless variations like
 * "A button" or "D-pad up"). An invalid answer is reported back to the model, never guessed.
 */
object LlmAnswerFormat {

    /** Text appended to the system prompt: the options and the expected answer. */
    fun instructions(request: ChoiceRequest): String = buildString {
        appendLine(request.instructions)
        appendLine()
        appendLine("Options (`choice` must be exactly one of these ids):")
        request.options.forEach { (id, description) -> appendLine("- $id: $description") }
        appendLine()
        appendLine("Reply with only this JSON object, no other text:")
        append("{")
        val fields = buildList {
            if (request.reasoning) add("\"reasoning\": \"<think step by step: what you see, what you want, how to get there>\"")
            else add("\"reasoning\": \"<one short sentence>\"")
            if (request.allowNote) add("\"note\": \"<your updated note to yourself: current goal and plan, max 3 sentences>\"")
            add("\"choice\": \"<option id>\"")
            if (request.allowSequence) add("\"then\": [<up to ${ChainRunner.MAX_THEN} more option ids to do right after, only if you are sure; [] otherwise>]")
        }
        append(fields.joinToString(", "))
        append("}")
    }

    /** JSON schema of the answer, for backends supporting structured output. */
    fun schema(request: ChoiceRequest): JsonObject = buildJsonObject {
        put("type", "object")
        val ids = buildJsonArray { request.options.keys.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("properties") {
            putJsonObject("reasoning") { put("type", "string") }
            if (request.allowNote) putJsonObject("note") { put("type", "string") }
            putJsonObject("choice") {
                put("type", "string")
                put("enum", ids)
            }
            if (request.allowSequence) putJsonObject("then") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                    put("enum", ids)
                }
                put("maxItems", ChainRunner.MAX_THEN)
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("reasoning"))
            add(JsonPrimitive("choice"))
        }
    }

    data class Answer(val choice: String, val reasoning: String?, val note: String?, val then: List<String>) {
        /**
         * The decision this answer makes for [request], as every LLM backend reports it: all the probability on the
         * choice (a generative model gives no distribution), no confidence, and what the backend measured ([model]
         * that answered, [inputTokens], [costUsd]).
         */
        fun toChoiceResult(request: ChoiceRequest, model: String, inputTokens: Int, costUsd: Double? = null) = ChoiceResult(
            choice = choice,
            probabilities = request.options.keys.associateWith { if (it == choice) 1.0 else 0.0 },
            confidence = null,
            model = model,
            inputTokens = inputTokens,
            thought = reasoning,
            then = then,
            note = note,
            costUsd = costUsd,
        )
    }

    /** Parses an answer, or throws [InvalidAnswer] with a message meant for the model. */
    fun parse(text: String, request: ChoiceRequest): Answer {
        val obj = extractJsonObject(text) ?: throw InvalidAnswer("The answer must be a JSON object with a `choice` field.")
        return parse(obj, request)
    }

    fun parse(obj: JsonObject, request: ChoiceRequest): Answer {
        val options = request.options.keys
        val rawChoice = obj["choice"]?.jsonPrimitive?.contentOrNull ?: throw InvalidAnswer("The `choice` field is missing.")
        val choice = normalize(rawChoice, options)
            ?: throw InvalidAnswer("`$rawChoice` is not one of the option ids: ${options.joinToString(", ")}.")
        val then = (obj["then"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.let { id -> normalize(id, options) } }
            .take(ChainRunner.MAX_THEN)
        return Answer(
            choice = choice,
            reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            note = obj["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            then = if (request.allowSequence) then else emptyList(),
        )
    }

    /** "A", "a button", "D-pad Up", "`up`" → "a" / "up" when they match an option id. */
    fun normalize(value: String, options: Set<String>): String? {
        val cleaned = value.trim().trim('`', '"', '\'', '.').lowercase()
        if (cleaned in options) return cleaned
        val simplified = cleaned.removePrefix("d-pad").removePrefix("dpad").removeSuffix("button").trim(' ', '-', '_')
        return simplified.takeIf { it in options }
    }

    /** The last top-level `{...}` block of the text (models sometimes wrap JSON in prose or fences). */
    private fun extractJsonObject(text: String): JsonObject? {
        var depth = 0
        var start = -1
        var last: String? = null
        var inString = false
        var escaped = false
        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) last = text.substring(start, i + 1)
                }
            }
        }
        return last?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    }

    private val json = Json { isLenient = true }

    class InvalidAnswer(message: String) : Exception(message)
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()
