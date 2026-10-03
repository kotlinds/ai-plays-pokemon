package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState

/**
 * Root composable: the game on the left, the control panel on the right.
 *
 * The panel can be collapsed (the arrow in the thin strip between them, never over the game): the window then
 * shrinks to the game alone,
 * handy to sit next to an MCP agent's terminal.
 */
@Composable
fun App(controller: AppController, window: WindowState) {
    var collapsed by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                EmulatorScreen(controller.emulator, Modifier.weight(1f).fillMaxHeight())
                // A thin strip of its own between the game and the panel: the toggle never covers the game.
                Box(Modifier.width(TOGGLE_WIDTH).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    IconButton(
                        onClick = {
                            collapsed = !collapsed
                            val width = window.size.width + if (collapsed) -PANEL_WIDTH else PANEL_WIDTH
                            window.size = DpSize(width, window.size.height)
                        },
                        modifier = Modifier.padding(top = 4.dp).size(TOGGLE_WIDTH),
                    ) {
                        // › hides the panel (it goes right), ‹ brings it back.
                        Text(if (collapsed) "‹" else "›", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (!collapsed) {
                    ControlPanel(
                        controller,
                        Modifier.width(PANEL_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

private val PANEL_WIDTH = 400.dp

/** Width of the strip holding the panel toggle. */
private val TOGGLE_WIDTH = 28.dp
