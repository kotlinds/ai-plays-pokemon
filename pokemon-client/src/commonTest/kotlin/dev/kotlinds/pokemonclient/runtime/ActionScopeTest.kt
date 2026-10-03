package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A fake game that reads its keys every [pollEvery] frames and moves a cursor once per new press, plus once
 * more when a direction stays held [repeatDelay] frames (like the DS games' key auto-repeat).
 */
private class FakeConsole(val pollEvery: Int = 2, val repeatDelay: Int = 16) : ConsolePort {
    override val platform = Platform.NINTENDO_DS
    override var frame = 0L
    override val revision get() = frame
    val ram = ByteArray(16)
    var cursor = 0
    private var held: Set<Button> = emptySet()
    private var heldFor = 0

    override fun step(frames: Int, input: InputFrame) = repeat(frames) {
        frame++
        if (frame % pollEvery == 0L) {
            val pressed = input.buttons - held
            if (Button.DOWN in pressed) cursor++
            heldFor = if (Button.DOWN in input.buttons) heldFor + pollEvery else 0
            if (heldFor >= repeatDelay) { cursor++; heldFor = 0 }
            held = input.buttons
            ram[0] = (if (Button.DOWN in held) 1 else 0).toByte()
        }
    }

    override fun memorySize(region: MemoryRegion) = 0x400000
    override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) {
        into.fill(0); ram.copyInto(into, 0, 0, minOf(ram.size, length))
    }
    override fun framebuffer(): Frame? = null
    override fun saveState() = ByteArray(0)
    override fun loadState(state: ByteArray) = true
}

/** The fake game's "held keys" byte sits at the start of the RAM (bus address 0x02000000). */
private val fakeProbe = InputProbe { memory -> if (memory.read8(0x02000000) != 0) setOf(Button.DOWN) else emptySet() }

class ActionScopeTest {

    @Test
    fun oneTapIsOneCursorStep() {
        val console = FakeConsole()
        val scope = ActionScope(console, fakeProbe)
        repeat(5) { assertTrue(scope.tap(Button.DOWN).registered) }
        assertEquals(5, console.cursor)
    }

    @Test
    fun tapsStayFarBelowTheAutoRepeatDelay() {
        val console = FakeConsole(pollEvery = 3)
        val scope = ActionScope(console, fakeProbe)
        val result = scope.tap(Button.DOWN)
        assertTrue(result.heldFrames <= 3, "held ${result.heldFrames} frames")
        assertEquals(1, console.cursor)
    }

    @Test
    fun aButtonTheGameNeverReadsIsReportedAsNotRegistered() {
        val scope = ActionScope(FakeConsole(), InputProbe { emptySet() })
        assertFalse(scope.tap(Button.DOWN).registered)
    }

    @Test
    fun interruptionStopsBeforeTheNextFrame() {
        val console = FakeConsole()
        var human = false
        val scope = ActionScope(console, fakeProbe, interruption = { if (human) Interruption.HUMAN else null })
        scope.step(3)
        human = true
        val error = assertFailsWith<ActionInterruptedException> { scope.step(10) }
        assertEquals(Interruption.HUMAN, error.reason)
        assertEquals(3L, console.frame)
    }

    @Test
    fun stepUntilStopsOnTheFrameTheConditionHolds() {
        val console = FakeConsole()
        val scope = ActionScope(console, fakeProbe)
        val met = scope.stepUntil(20, InputFrame.of(Button.DOWN)) { it.read8(0x02000000) != 0 }
        assertTrue(met)
        assertEquals(2L, console.frame)
    }
}
