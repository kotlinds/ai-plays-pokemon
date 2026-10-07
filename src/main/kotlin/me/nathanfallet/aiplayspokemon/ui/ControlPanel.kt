package me.nathanfallet.aiplayspokemon.ui

import me.nathanfallet.aiplayspokemon.mcp.AgentActivity
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.agent.Decision
import me.nathanfallet.aiplayspokemon.agent.ControlMode
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.agent.PlayerState
import me.nathanfallet.aiplayspokemon.agent.RunStats
import me.nathanfallet.aiplayspokemon.agent.PokemonPlayer
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import dev.kotlinds.pokemonclient.view.StateView

/** The right-hand panel: AI controls, what the AI sees, and what it decided. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlPanel(controller: AppController, modifier: Modifier = Modifier) {
    val player by controller.player.collectAsState()
    val backend by controller.backend.collectAsState()
    val status by controller.emulator.status.collectAsState()
    val panel by controller.panel.collectAsState()

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI plays Pokémon", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${controller.game?.name ?: "Unsupported game"} · ${controller.emulator.info.name} · ${"%.0f".format(status.measuredFps)} fps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Emulator controls (wrapping: they don't all fit on one line in a narrow panel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::togglePause) { Text(if (status.running) "Pause game" else "Resume game") }
            FilterChip(
                selected = status.fastForward,
                onClick = { controller.emulator.setFastForward(!status.fastForward) },
                label = { Text("Fast forward") },
            )
            FilterChip(
                selected = !status.muted,
                onClick = { controller.emulator.setMuted(!status.muted) },
                label = { Text("Sound") },
            )
            MusicDuringPausesChip(controller)
            WaitForSongChangeChip(controller)
        }

        // Which AI plays: Jev, or an LLM (provider + model)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecisionBackend.entries.forEach { entry ->
                FilterChip(
                    selected = backend == entry,
                    onClick = { controller.selectBackend(entry) },
                    label = { Text(entry.label) },
                )
            }
        }
        if (backend == DecisionBackend.LLM) LlmSelector(controller)
        ExperimentSettings(controller, backend)

        val currentPlayer = player
        when {
            controller.game == null -> Text("This ROM isn't supported by the agent yet: you can still play it yourself.")
            backend == DecisionBackend.MCP -> McpSection(controller)
            currentPlayer == null -> ApiKeyForm(controller.missingApiKey ?: "the model", controller::setApiKey)
            else -> PlayerSection(currentPlayer)
        }

        HorizontalDivider()
        val playerState = player?.state?.collectAsState()?.value
        AgentViewSection(panel, playerState?.lastRequestState)
        HorizontalDivider()
        KeyboardHelp()
    }
}

/**
 * "Music during pauses": the music goes on while the game is paused, and the game resumes in sync with it. Disabled
 * (with the reason as its label) when the ROM / core / platform doesn't support it.
 */
@Composable
private fun MusicDuringPausesChip(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val unavailable = controller.emulator.musicDuringPausesUnavailable
    FilterChip(
        selected = settings.musicDuringPauses && unavailable == null,
        enabled = unavailable == null,
        onClick = { controller.updateSettings { it.copy(musicDuringPauses = !it.musicDuringPauses) } },
        label = { Text(if (unavailable == null) "Music during pauses" else "Music during pauses (unavailable)") },
    )
}

/**
 * "Wait for the song change": with music during pauses, a pause asked while the game changes its song starts once the
 * new song plays, so the music doesn't jump back at resume. Only meaningful (enabled) with music during pauses.
 */
@Composable
private fun WaitForSongChangeChip(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val available = settings.musicDuringPauses && controller.emulator.musicDuringPausesUnavailable == null
    FilterChip(
        selected = settings.waitForSongChange && available,
        enabled = available,
        onClick = { controller.updateSettings { it.copy(waitForSongChange = !it.waitForSongChange) } },
        label = { Text("Wait for the song change") },
    )
}

/** Provider chips and model id for the LLM backend. */
@Composable
private fun LlmSelector(controller: AppController) {
    val llm by controller.llm.collectAsState()
    var model by remember(llm) { mutableStateOf(llm.model) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LlmProvider.entries.forEach { provider ->
            FilterChip(
                selected = llm.provider == provider,
                onClick = { controller.selectLlmProvider(provider) },
                label = { Text(provider.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model id") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { controller.setLlmModel(model) }, enabled = model.isNotBlank() && model != llm.model) {
            Text("Use")
        }
    }
    if (llm.provider == LlmProvider.OLLAMA || llm.provider == LlmProvider.CLAUDE_CODE) FilterChip(
        selected = llm.thinking,
        onClick = { controller.setLlmThinking(!llm.thinking) },
        label = { Text(if (llm.provider == LlmProvider.OLLAMA) "Think before answering (slower)" else "High effort (slower)") },
    )
    if (llm.provider == LlmProvider.CLAUDE_CODE) Text(
        "Uses the Claude account Claude Code is logged in with (no API key); counts towards its usage limits.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The experiment knobs: control mode (pure / assisted / hybrid) and the options to compare.
 * In hybrid mode, the model selected above decides and the LLM settings define the planner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentSettings(controller: AppController, backend: DecisionBackend) {
    val settings by controller.settings.collectAsState()
    Text("Mode", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Hybrid needs our own loop (fast model + planner), so it isn't offered to an external agent.
        ControlMode.entries.filter { backend != DecisionBackend.MCP || it != ControlMode.HYBRID }.forEach { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = { controller.updateSettings { it.copy(mode = mode) } },
                label = { Text(mode.label) },
            )
        }
    }
    Text(settings.mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Knowledge", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KnowledgeLevel.entries.forEach { level ->
            FilterChip(
                selected = settings.knowledge == level,
                onClick = { controller.updateSettings { it.copy(knowledge = level) } },
                label = { Text(KNOWLEDGE_LABELS.getValue(level)) },
            )
        }
    }
    Text(settings.knowledge.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (settings.mode == ControlMode.HYBRID) Text(
        "Planner: the LLM configured in the LLM tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Options that only make sense for some backends are shown only there.
    val llmInLoop = backend == DecisionBackend.LLM || settings.mode == ControlMode.HYBRID
    val toggles = listOfNotNull(
        Triple("Pause while thinking", settings.pauseWhileThinking) { s: PlayerSettings, v: Boolean -> s.copy(pauseWhileThinking = v) },
        Triple("Sequences (LLM)", settings.allowSequences) { s: PlayerSettings, v: Boolean -> s.copy(allowSequences = v) }.takeIf { llmInLoop },
        // Our LLM reasons before answering; an MCP agent must give `reasoning` with each act.
        Triple(if (backend == DecisionBackend.MCP) "Reasoning required (MCP)" else "Reasoning (LLM)", settings.reasoning) { s: PlayerSettings, v: Boolean -> s.copy(reasoning = v) }
            .takeIf { llmInLoop || backend == DecisionBackend.MCP },
        Triple("Notes (LLM)", settings.modelNotes) { s: PlayerSettings, v: Boolean -> s.copy(modelNotes = v) }.takeIf { llmInLoop },
        Triple("Explored map", settings.exploredMap) { s: PlayerSettings, v: Boolean -> s.copy(exploredMap = v) },
        Triple("Solve movement puzzles", settings.solvePuzzles) { s: PlayerSettings, v: Boolean -> s.copy(solvePuzzles = v) }
            .takeIf { settings.mode != ControlMode.PURE },
        // Every mode: the map view of the state lists exits too (without destinations when on).
        Triple("Hide where exits lead", settings.hideDestinations) { s: PlayerSettings, v: Boolean -> s.copy(hideDestinations = v) },
        Triple("Sample probabilities", settings.sampleProbabilities) { s: PlayerSettings, v: Boolean -> s.copy(sampleProbabilities = v) }
            .takeIf { backend == DecisionBackend.JEV },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        toggles.forEach { (label, value, update) ->
            FilterChip(
                selected = value,
                onClick = { controller.updateSettings { update(it, !value) } },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun ApiKeyForm(service: String, onSubmit: (String) -> Unit) {
    var key by remember(service) { mutableStateOf("") }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API key needed", fontWeight = FontWeight.Bold)
            Text(
                "Paste your $service API key. It is saved in ~/.ai-plays-pokemon/config.properties.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSubmit(key) }, enabled = key.isNotBlank()) { Text("Save") }
        }
    }
}

@Composable
private fun PlayerSection(player: PokemonPlayer) {
    val state by player.state.collectAsState()
    var objective by remember(player) { mutableStateOf(player.objective) }

    Button(
        onClick = player::toggle,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = if (state.playing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors(),
    ) {
        Text(if (state.playing) "⏸  Take back control" else "▶  Let ${player.model.name} play", fontSize = 16.sp)
    }
    if (state.thinking) Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    state.error?.let { Text("Stopped: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    OutlinedTextField(
        value = objective,
        onValueChange = {
            objective = it
            player.objective = it
        },
        label = { Text("Objective") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall,
    )

    Stats(state)
    state.note?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
    state.plannerGoal?.let { Text("Planner goal: $it", style = MaterialTheme.typography.bodySmall) }
    Milestones(state.milestones)
    state.lastDecision?.let { DecisionCard(it) }
    DecisionHistory(state.history, Modifier.height(180.dp))
}

@Composable
private fun Stats(state: PlayerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("Decisions", state.decisions.toString())
        Stat("Presses", state.presses.toString())
        Stat("Avg latency", "${state.averageLatencyMillis} ms")
        Stat("Tokens", state.inputTokens.toString())
        if (state.costUsd > 0) Stat("Cost", "$" + "%.2f".format(state.costUsd))
    }
}

/** Lets an external agent (Claude Code...) play through MCP instead of our own loop. */
@Composable
private fun McpSection(controller: AppController) {
    val server by controller.mcp.collectAsState()
    Text(
        if (server != null) "MCP server running: an external agent plays through it." else "Starting the MCP server…",
        fontWeight = FontWeight.Bold,
    )
    val driver by controller.emulator.driver.collectAsState()
    Text(
        "Driving the game: " + when (val d = driver) {
            is ConsoleHost.Driver.Agent -> "agent (${d.label})"
            ConsoleHost.Driver.Human -> "you / free run"
            ConsoleHost.Driver.Idle -> "nobody (paused)"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    var port by remember { mutableStateOf(controller.mcpPort.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("MCP port") }, singleLine = true, modifier = Modifier.width(120.dp))
        OutlinedButton(onClick = { port.toIntOrNull()?.let(controller::setMcpPort) }, enabled = port.toIntOrNull() != controller.mcpPort) {
            Text("Apply")
        }
    }
    server?.let { running ->
        val activity by running.activity.collectAsState()
        val blind by running.blindUses.collectAsState()
        SelectionContainer {
            Text(
                "Connect an agent, e.g.: claude mcp add --transport http pokemon ${running.url}\n" +
                    "Then ask it to play. Calls: ${activity.calls}" +
                    (activity.shown?.let { shown ->
                        val head = if (shown.phase == AgentActivity.Phase.IN_PROGRESS) "In progress" else "Last"
                        "\n$head: ${shown.action}" + (shown.result?.let { " → $it" } ?: "") + (shown.reasoning?.let { "\n“$it”" } ?: "")
                    } ?: "") +
                    (if (blind.isEmpty()) "" else "\nUndecoded screens used blindly: " +
                        blind.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Milestones reached during this run (also saved in ~/.ai-plays-pokemon/runs/ to compare configurations). */
@Composable
private fun Milestones(milestones: List<RunStats.Milestone>) {
    if (milestones.isEmpty()) return
    Column {
        Text("Milestones", fontWeight = FontWeight.Bold)
        milestones.forEach {
            Text(
                "${it.name} · ${it.decisions} decisions · ${it.presses} presses · ${it.seconds}s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The last decision: the model's probability for every button when it gives them (Jev does, which
 * makes its decisions "explainable"), or its short thought (LLMs).
 */
@Composable
private fun DecisionCard(decision: Decision) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "#${decision.number} → ${decision.actions.joinToString(" → ")}${if (decision.byPlanner) "  (planner)" else ""}",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "confidence ${decision.confidence?.let(::percent) ?: "n/a"} · ${decision.latencyMillis} ms · ${decision.model}",
                style = MaterialTheme.typography.bodySmall,
            )
            decision.thought?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
            decision.problem?.let { Text("Failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            // A sequence ending early by its own rules is normal: said, not shown as an error.
            decision.stop?.let { Text("Sequence ended: $it", style = MaterialTheme.typography.bodySmall) }
            if (decision.confidence != null) decision.probabilities.forEach { (option, probability) ->
                ProbabilityBar(option, probability)
            }
        }
    }
}

@Composable
private fun ProbabilityBar(option: String, probability: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(option, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth(probability.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(percent(probability), Modifier.width(44.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DecisionHistory(history: List<Decision>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(history, key = { it.number }) { decision ->
            Text(
                "#${decision.number}  ${decision.actions.joinToString(",").padEnd(10)} ${(decision.confidence?.let(::percent) ?: "").padStart(4)}  ${StateView.summary(decision.state)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/**
 * What the AI receives: the full state of the last request (screen + memory) while our own loop plays, otherwise
 * exactly what an agent would get now ([AppController.panel]: `GameSession.describe()` with the current options).
 */
@Composable
private fun AgentViewSection(panel: PanelView?, lastRequestState: JsonObject?) {
    var showJson by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("What the AI sees", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "Hide JSON" else "Show JSON") }
    }
    Text(panel?.summary ?: "Nothing yet", style = MaterialTheme.typography.bodySmall)
    val json = lastRequestState ?: panel?.view
    if (showJson && json != null) {
        SelectionContainer {
            Text(
                prettyJson.encodeToString(JsonObject.serializer(), json),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.height(260.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun KeyboardHelp() {
    Column {
        KeyboardMapping.help.chunked(2).forEach { row ->
            Row {
                row.forEach { (key, action) ->
                    Text(
                        "$key: $action",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun percent(value: Double) = "${(value * 100).toInt()}%"


/** Short names of the knowledge levels, for the chips. */
private val KNOWLEDGE_LABELS = mapOf(
    KnowledgeLevel.NONE to "Screen only",
    KnowledgeLevel.POKEDEX to "Pokédex",
    KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH to "+ Walkthrough",
)
