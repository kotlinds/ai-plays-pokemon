package me.nathanfallet.aiplayspokemon.agent

import me.nathanfallet.aiplayspokemon.emulator.Button

/**
 * The options the model chooses from: the console's buttons, nothing more.
 *
 * The model gets exactly what a human player has: a controller. No pathfinding, no "talk to this
 * person" macros: it has to understand the situation and work out which button makes progress. The
 * descriptions are what a player would read in the game's manual, not hints about the current state.
 *
 * One decision = one press: the button is held for [HOLD_FRAMES] (long enough to walk one tile in the
 * overworld, short enough to move a menu cursor by exactly one entry), then released, and the game
 * gets [SETTLE_FRAMES] to react before the next decision.
 */
enum class ButtonPress(val id: String, val button: Button, val description: String) {
    A("a", Button.A, "A button: confirm, talk to or interact with what you are facing, advance text."),
    B("b", Button.B, "B button: cancel, go back, close a menu; hold to run once you have running shoes."),
    X("x", Button.X, "X button: open or close the main menu in the overworld."),
    Y("y", Button.Y, "Y button: use the item registered to it (nothing if none)."),
    L("l", Button.L, "L button: rarely used shortcut."),
    R("r", Button.R, "R button: rarely used shortcut."),
    START("start", Button.START, "START button: start the game on the title screen; sorts items in the bag."),
    SELECT("select", Button.SELECT, "SELECT button: use the registered key item; swaps items in menus."),
    UP("up", Button.UP, "D-pad up: walk north one tile (or turn to face north), or move a menu cursor up."),
    DOWN("down", Button.DOWN, "D-pad down: walk south one tile (or turn to face south), or move a menu cursor down."),
    LEFT("left", Button.LEFT, "D-pad left: walk west one tile (or turn to face west), or move a menu cursor left."),
    RIGHT("right", Button.RIGHT, "D-pad right: walk east one tile (or turn to face east), or move a menu cursor right."),
    ;

    companion object {
        /** Frames the button is held (the DS runs at ~60 frames per second). */
        const val HOLD_FRAMES = 16

        /** Frames to wait after releasing, so the result of the press is visible in the next observation. */
        const val SETTLE_FRAMES = 8

        fun fromId(id: String): ButtonPress? = entries.firstOrNull { it.id == id }
    }
}
