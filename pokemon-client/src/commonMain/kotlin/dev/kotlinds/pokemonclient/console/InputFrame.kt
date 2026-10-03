package dev.kotlinds.pokemonclient.console

/** Buttons of a Nintendo DS (a superset of the GBA's). */
enum class Button {
    A, B, X, Y, L, R, START, SELECT, UP, DOWN, LEFT, RIGHT;

    /** True for the D-pad directions. */
    val isDirection: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

/**
 * A point on the touch screen, in pixels of that screen: x in 0 until 256, y in 0 until 192 on the DS
 * (0, 0 is the top-left corner of the bottom screen).
 */
data class TouchPoint(val x: Int, val y: Int)

/** What is held during one frame: buttons, and the touch screen when [touch] isn't null. */
data class InputFrame(
    val buttons: Set<Button> = emptySet(),
    val touch: TouchPoint? = null,
) {
    companion object {
        /** Nothing held. */
        val NONE = InputFrame()

        /** Only [button] held. */
        fun of(button: Button) = InputFrame(setOf(button))
    }
}
