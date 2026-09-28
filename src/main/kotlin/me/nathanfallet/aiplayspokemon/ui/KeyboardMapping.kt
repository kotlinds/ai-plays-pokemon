package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.ui.input.key.Key
import me.nathanfallet.aiplayspokemon.emulator.Button

/** Keyboard → DS buttons, using the same defaults as melonDS / most emulators. */
object KeyboardMapping {

    val buttons: Map<Key, Button> = mapOf(
        Key.DirectionUp to Button.UP,
        Key.DirectionDown to Button.DOWN,
        Key.DirectionLeft to Button.LEFT,
        Key.DirectionRight to Button.RIGHT,
        Key.X to Button.A,
        Key.Z to Button.B,
        Key.S to Button.X,
        Key.A to Button.Y,
        Key.Q to Button.L,
        Key.W to Button.R,
        Key.Enter to Button.START,
        Key.Backspace to Button.SELECT,
    )

    /** Save state slots: F1..F4 load, Shift+F1..F4 save. */
    val stateSlots: Map<Key, Int> = mapOf(Key.F1 to 1, Key.F2 to 2, Key.F3 to 3, Key.F4 to 4)

    /** Shown in the UI. */
    val help = listOf(
        "Arrows" to "D-pad",
        "X / Z" to "A / B",
        "S / A" to "X / Y",
        "Q / W" to "L / R",
        "Enter / ⌫" to "Start / Select",
        "Mouse" to "Touch screen",
        "Space" to "AI play / pause",
        "P" to "Pause emulator",
        "F" to "Fast forward",
        "M" to "Mute",
        "F1-F4" to "Load state (⇧ to save)",
    )
}
