package me.nathanfallet.aiplayspokemon.agent.actions

import me.nathanfallet.aiplayspokemon.agent.GameController
import me.nathanfallet.aiplayspokemon.agent.actions.Pathfinder.Point
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.MenuState
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PointOfInterest

/**
 * Everything the AI can do, as a closed set of typed actions.
 *
 * Models only see an [id] and a [description] (that's what goes over the wire); the [id] is turned
 * back into the typed action once, by [me.nathanfallet.aiplayspokemon.agent.AgentSession.Turn.resolve],
 * so an invalid or out-of-date id is rejected before anything is pressed.
 *
 * - [PressButton] and [Wait]: what a human does with the controller (pure mode).
 * - [TakeExit], [Interact], [Explore], [ChooseOption]: assisted actions, carried out by code
 *   (pathfinding, facing, cursor moves) while the AI still decides what to do.
 */
sealed interface AgentAction {
    /** Option id sent to the model, e.g. "up" or "exit_1". */
    val id: String

    /** What the model reads about this option. */
    val description: String

    /** Carries the action out and returns what's on screen afterwards. */
    suspend fun execute(controller: GameController): ActionOutcome
}

/** The observation after an action, plus a note when the action couldn't complete as planned. */
data class ActionOutcome(val observation: Observation, val problem: String? = null)

/** A raw button press: exactly what a human does with the controller. */
data class PressButton(val button: Button) : AgentAction {
    override val id = button.name.lowercase()
    override val description = DESCRIPTIONS.getValue(button)

    override suspend fun execute(controller: GameController) = ActionOutcome(controller.press(button))

    private companion object {
        val DESCRIPTIONS = mapOf(
            Button.A to "A button: confirm, talk to or examine what you are facing, advance text.",
            Button.B to "B button: cancel, go back, close a menu or a screen.",
            Button.X to "X button: open or close the main menu in the overworld.",
            Button.Y to "Y button: use the item registered to it (nothing if none).",
            Button.L to "L button: rarely used shortcut.",
            Button.R to "R button: rarely used shortcut.",
            Button.START to "START button: start the game on the title screen; sorts items in the bag.",
            Button.SELECT to "SELECT button: use the registered key item; swaps items in menus.",
            Button.UP to "D-pad up: turn to face north, or walk one tile north if already facing it; moves a menu cursor up.",
            Button.DOWN to "D-pad down: turn to face south, or walk one tile south if already facing it; moves a menu cursor down.",
            Button.LEFT to "D-pad left: turn to face west, or walk one tile west if already facing it; moves a menu cursor left.",
            Button.RIGHT to "D-pad right: turn to face east, or walk one tile east if already facing it; moves a menu cursor right.",
        )
    }
}

/** Pressing nothing for a moment (animations, cutscenes, text appearing). */
data object Wait : AgentAction {
    override val id = "wait"
    override val description = "Press nothing for a moment: an animation, a transition or a scene is playing."
    override suspend fun execute(controller: GameController) = ActionOutcome(controller.idle())
}

/** Walks to an exit (door, stairs, mat) and takes it. */
data class TakeExit(val index: Int, val exit: PointOfInterest, val steps: Int) : AgentAction {
    override val id = "exit_$index"
    override val description = "Walk to the ${exit.label} ($steps steps) and go through it."

    override suspend fun execute(controller: GameController): ActionOutcome {
        val start = controller.observe()
        val outcome = Navigation.walk(controller) { it.x == exit.x && it.y == exit.y }
        if (outcome.problem != null || outcome.observation.location?.mapId != start.location?.mapId) return outcome
        // Standing on the warp without changing map: some exits need a press towards the edge.
        val direction = exit.exitDirection ?: outcome.observation.location?.facing ?: return outcome
        var observation = outcome.observation
        repeat(2) {
            if (observation.location?.mapId != start.location?.mapId) return ActionOutcome(observation)
            observation = controller.press(Navigation.button(direction))
        }
        return ActionOutcome(observation)
    }
}

/** Walks next to a person / object / item, faces it and presses A. */
data class Interact(val index: Int, val target: PointOfInterest, val steps: Int) : AgentAction {
    override val id = when (target.kind) {
        PointOfInterest.Kind.PERSON -> "talk_$index"
        PointOfInterest.Kind.ITEM -> "pick_up_$index"
        else -> "examine_$index"
    }
    override val description = when (target.kind) {
        PointOfInterest.Kind.PERSON -> "Walk next to ${target.label} ($steps steps) and talk to them."
        PointOfInterest.Kind.ITEM -> "Walk to the ${target.label} ($steps steps) and pick it up."
        else -> "Walk to the ${target.label} ($steps steps) and examine it."
    }

    override suspend fun execute(controller: GameController): ActionOutcome {
        val adjacent = Navigation.neighbours(target).toSet()
        val outcome = Navigation.walk(controller) { it in adjacent }
        if (outcome.problem != null) return outcome
        val location = outcome.observation.location ?: return outcome
        val direction = Direction.entries.firstOrNull { location.x + it.dx == target.x && location.y + it.dy == target.y }
            ?: return ActionOutcome(outcome.observation, "couldn't get next to the ${target.label}")
        if (location.facing != direction) controller.press(Navigation.button(direction))
        return ActionOutcome(controller.press(Button.A))
    }
}

/** Walks as far as possible in a direction. */
data class Explore(val direction: Direction, val target: Point, val steps: Int) : AgentAction {
    override val id = "explore_${direction.name.lowercase()}"
    override val description =
        "Walk $steps steps towards the farthest reachable spot to the ${direction.name.lowercase()} (x ${target.x}, y ${target.y})."

    override suspend fun execute(controller: GameController) = Navigation.walk(controller) { it == target }
}

/** Presses A until the current message ends (stops at a choice, a battle, or when the screen changes). */
data object AdvanceDialogue : AgentAction {
    override val id = "advance_dialogue"
    override val description = "Press A until this message ends (stops if a choice appears)."

    override suspend fun execute(controller: GameController): ActionOutcome {
        var observation = controller.observe()
        val mode = observation.mode
        repeat(MAX_PRESSES) {
            if (observation.mode != mode || observation.menu != null || observation.dialogue == null) return ActionOutcome(observation)
            observation = controller.press(Button.A)
        }
        return ActionOutcome(observation, "the message didn't end after $MAX_PRESSES presses")
    }

    private const val MAX_PRESSES = 60
}

/** Moves a menu cursor to an option and confirms it. */
data class ChooseOption(val index: Int, val option: String, val menu: MenuState) : AgentAction {
    override val id = "choose_$index"
    override val description = "Choose “$option” in the ${menu.kind} menu" + if (menu.cursor == index) " (already highlighted)." else "."

    override suspend fun execute(controller: GameController): ActionOutcome {
        var observation = controller.observe()
        repeat(MAX_MENU_MOVES) {
            val current = observation.menu ?: return ActionOutcome(observation, "the menu closed")
            val cursor = current.cursor ?: return ActionOutcome(observation, "the cursor position is unknown")
            if (cursor == index) return ActionOutcome(controller.press(Button.A))
            observation = controller.press(Navigation.cursorButton(current, cursor, index))
        }
        return ActionOutcome(observation, "couldn't move the cursor to the option")
    }

    private companion object {
        const val MAX_MENU_MOVES = 12
    }
}
