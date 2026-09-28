package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.agent.Decision
import me.nathanfallet.aiplayspokemon.agent.PlayerState
import me.nathanfallet.aiplayspokemon.agent.PokemonPlayer
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.game.Observation

/** The right-hand panel: AI controls, what the AI sees, and what it decided. */
@Composable
fun ControlPanel(controller: AppController, modifier: Modifier = Modifier) {
    val player by controller.player.collectAsState()
    val backend by controller.backend.collectAsState()
    val status by controller.emulator.status.collectAsState()
    val observation by controller.observation.collectAsState()

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI plays Pokémon", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${controller.game?.name ?: "Unsupported game"} · ${controller.emulator.info.name} · ${"%.0f".format(status.measuredFps)} fps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Emulator controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        val currentPlayer = player
        when {
            controller.game == null -> Text("This ROM isn't supported by the agent yet: you can still play it yourself.")
            currentPlayer == null -> ApiKeyForm(controller.missingApiKey ?: "the model", controller::setApiKey)
            else -> PlayerSection(currentPlayer)
        }

        HorizontalDivider()
        ObservationSection(observation)
        HorizontalDivider()
        KeyboardHelp()
    }
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
    state.lastDecision?.let { DecisionCard(it) }
    DecisionHistory(state.history, Modifier.height(180.dp))
}

@Composable
private fun Stats(state: PlayerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("Decisions", state.decisions.toString())
        Stat("Avg latency", "${state.averageLatencyMillis} ms")
        Stat("Input tokens", state.inputTokens.toString())
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
 * The last decision: Jev's probability for every button (what makes it "explainable"), or the
 * LLM's short thought.
 */
@Composable
private fun DecisionCard(decision: Decision) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "#${decision.number} → ${decision.press.id}",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "confidence ${decision.confidence?.let(::percent) ?: "n/a"} · ${decision.latencyMillis} ms · ${decision.model}",
                style = MaterialTheme.typography.bodySmall,
            )
            decision.thought?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
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
                "#${decision.number}  ${decision.press.id.padEnd(7)} ${(decision.confidence?.let(::percent) ?: "").padStart(4)}  ${decision.observation.summary}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/** What the RAM reader currently understands of the game, i.e. exactly what the AI receives. */
@Composable
private fun ObservationSection(observation: Observation?) {
    var showJson by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("What the AI sees", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "Hide JSON" else "Show JSON") }
    }
    Text(observation?.summary ?: "Nothing yet", style = MaterialTheme.typography.bodySmall)
    if (showJson && observation != null) {
        SelectionContainer {
            Text(
                prettyJson.encodeToString(JsonObject.serializer(), observation.state),
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

private val prettyJson = Json { prettyPrint = true }
