package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Root composable: the game on the left, the control panel on the right. */
@Composable
fun App(controller: AppController) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                EmulatorScreen(controller.emulator, Modifier.weight(1f).fillMaxHeight())
                ControlPanel(
                    controller,
                    Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
