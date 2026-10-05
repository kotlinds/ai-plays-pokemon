package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology

/** `NamingScreenType` (HGSS include/launch_application.h, Platinum include/applications/naming_screen.h): who is being named. */
enum class NameScreenType(val purpose: String) {
    PLAYER("player"), POKEMON("pokemon"), BOX("box"), RIVAL("rival"), NUMBER("number"), GROUP("group");

    companion object {
        fun of(value: Int) = entries.getOrNull(value)
    }
}

/**
 * Where a game keeps its naming keyboard: the per-ROM addresses of the Gen 4 naming screen (src/naming_screen.c in
 * HGSS, src/applications/naming_screen.c in Platinum).
 */
data class Gen4NamingAddresses(
    /** `NamingScreen_VBlankCB` / `NamingScreen_VBlankCallback`, without the Thumb bit: installed in `gSystem` while it runs. */
    val vblankCallback: Long,
    /** The static pointer to the app data, set at init and never cleared (HGSS `sAppData`, Platinum `sNamingScreenDummy`). */
    val appData: Long,
    /**
     * Offset of the app's `delayUpdateCounter` when the game has one (Platinum: NMS_APP_STATE_INITIAL_DELAY counts 6
     * frames after the fade-in, keys ignored, then it is back to 0); null when not read (HGSS).
     */
    val delayCounterOffset: Long? = null,
    /**
     * Where the cursor sprite's draw flag is, when the game hides the cursor until the first D-pad press (Platinum:
     * `uiSprites[NMS_SPRITE_CURSOR]` → `Sprite.draw`; NamingScreen_ProcessDirectionInputs ignores the move of that
     * press): the offset of the `Sprite *` in the app data, then of `draw` in the sprite. Null when not read (HGSS).
     */
    val cursorSprite: Pair<Long, Long>? = null,
)

/**
 * The naming keyboard of the Generation 4 games, wherever it was opened from: detected with the vblank callback it
 * installs in `gSystem` (the only test that also covers the in-battle nickname screen and the professor's intro).
 * `NamingScreenAppData` (HGSS) and `NamingScreen` (Platinum) have the same layout up to the fields read here
 * ([Offsets], compiled with both decomps), and the same key codes.
 *
 * Entries: one per cell of the 13 × 6 grid of the current page (index = `y * 13 + x`, the game's cursor), read from
 * the live `keyboard` array. Multi-cell buttons (tabs, BACK, OK) appear once per cell with the same id.
 */
object Gen4NamingKeyboard {

    /** Offsets in the naming screen's data (HGSS src/naming_screen.c:83, Platinum src/applications/naming_screen.c:158). */
    object Offsets {
        const val TYPE = 0x00L
        /** Max characters: 7 player / rival, 10 Pokémon, 8 box. */
        const val MAX_LEN = 0x0CL
        const val CURSOR_X = 0x1CL
        const val CURSOR_Y = 0x20L
        /** TRUE for a few frames once the buffer is full, until the cursor is moved to OK by the game. */
        const val IGNORE_INPUT = 0x34L
        /** `u16 keyboard[6][13]`: the live layout of the current page, row 0 = tabs / BACK / OK. */
        const val KEYBOARD = 0x3AL
        /** `u16 entryBuf[32]`: typed characters, valid in `[0, textCursorPos)`. */
        const val ENTRY_BUF = 0xD8L
        const val TEXT_CURSOR_POS = 0x158L
        /** `String *battleMsgString`: the "transferred to the PC" message after a capture with a full party. */
        const val BATTLE_MSG_STRING = 0x180L
        /** 0..3 page slide animation, 4 idle (NMS_STATE_PROCESS_INPUTS), 5..7 the "transferred to the PC" message. */
        const val STATE = 0x45CL
        /** 0 UPPER, 1 lower, 2 Others, 3 JP (unused), 4 number pad. */
        const val PAGE = 0x460L
    }

    const val STATE_IDLE = 4
    const val STATE_WAIT_BATTLE_MESSAGE = 6
    const val STATE_DELAY_AND_FADE_OUT = 7
    const val COLUMNS = 13
    const val ROWS = 6
    const val ENTRY_BUF_SIZE = 32

    // Codes of the non-character cells of `keyboard` (NamingScreenControlChars).
    const val KEY_SKIP = 0xD004
    const val KEY_BUTTON_START = 0xE001
    const val KEY_PAGE_UPPER = 0xE002
    const val KEY_PAGE_LOWER = 0xE003
    const val KEY_PAGE_OTHERS = 0xE004
    const val KEY_BACK = 0xE007
    const val KEY_OK = 0xE008

    /** The data of the naming keyboard when it runs (its vblank callback is installed), else null. */
    fun data(mem: Gen4Memory, gSystem: Long, addresses: Gen4NamingAddresses): Long? {
        if (mem.fn(gSystem + Gen4Structs.SYS_VBLANK_CALLBACK) != addresses.vblankCallback) return null
        return mem.ptr(addresses.appData)
    }

    /**
     * The keyboard's screen when it runs, else null. [fading]: a screen fade is running (the keyboard ignores keys).
     */
    fun decode(mem: Gen4Memory, gSystem: Long, addresses: Gen4NamingAddresses, fading: Boolean): Screen? {
        val data = data(mem, gSystem, addresses) ?: return null
        val O = Offsets
        // After a capture with a full party: "X was transferred to BOX 1 in Bill's PC!" printed on the keyboard,
        // then the screen fades out by itself.
        val pageSwitch = mem.s32(data + O.STATE)
        if (pageSwitch == STATE_WAIT_BATTLE_MESSAGE || pageSwitch == STATE_DELAY_AND_FADE_OUT) {
            mem.gameString(mem.ptr(data + O.BATTLE_MSG_STRING))?.takeIf { it.isNotBlank() }?.let { text ->
                val awaiting = if (pageSwitch == STATE_WAIT_BATTLE_MESSAGE) Awaiting.TEXT_PRINTING else Awaiting.ANIMATION
                return Screen.Dialogue(TextSource.MENU, null, text, awaiting)
            }
        }
        val delaying = addresses.delayCounterOffset?.let { mem.s32(data + it) != 0 } == true
        val ready = pageSwitch == STATE_IDLE && mem.s32(data + O.IGNORE_INPUT) == 0 && !fading && !delaying
        if (!ready) return Screen.Animation(AnimationKind.TRANSITION)

        val cells = IntArray(COLUMNS * ROWS) { mem.u16(data + O.KEYBOARD + 2L * it) }
        val x = mem.s32(data + O.CURSOR_X)
        val y = mem.s32(data + O.CURSOR_Y)
        val length = mem.u16(data + O.TEXT_CURSOR_POS).coerceIn(0, ENTRY_BUF_SIZE)
        val type = NameScreenType.of(mem.s32(data + O.TYPE))
        return Screen.Keyboard(
            purpose = type?.purpose ?: "other",
            page = pageName(mem.s32(data + O.PAGE)),
            buffer = Gen4Text.decode(mem.chars(data + O.ENTRY_BUF, length)),
            maxLength = mem.s32(data + O.MAX_LEN),
            entries = cells.map(::entry),
            cursor = if (x in 0 until COLUMNS && y in 0 until ROWS && !cursorHidden(mem, data, addresses)) Cursor.At(y * COLUMNS + x) else Cursor.Hidden,
            topology = topology(cells),
            cancel = CancelBehavior.NONE,
        )
    }

    private fun cursorHidden(mem: Gen4Memory, data: Long, addresses: Gen4NamingAddresses): Boolean {
        val (spriteOffset, drawOffset) = addresses.cursorSprite ?: return false
        val sprite = mem.ptr(data + spriteOffset) ?: return false
        return mem.u8(sprite + drawOffset) == 0
    }

    private fun pageName(page: Int) = when (page) {
        0 -> "upper"
        1 -> "lower"
        2 -> "others"
        4 -> "numpad"
        else -> "page$page"
    }

    /** Ids from the key codes only: the labels are for display. */
    private fun entry(code: Int): Entry = when (code) {
        KEY_PAGE_UPPER -> Entry("page:upper", "UPPER")
        KEY_PAGE_LOWER -> Entry("page:lower", "lower")
        KEY_PAGE_OTHERS -> Entry("page:others", "Others")
        KEY_BACK -> Entry("option:back", "BACK")
        KEY_OK -> Entry("option:ok", "OK")
        KEY_SKIP -> Entry("key:skip", "", selectable = false)
        else -> {
            val char = Gen4Charmap.table[code]?.takeIf { code < KEY_BUTTON_START }
            if (char != null) Entry("key:$char", char) else Entry("key:0x${code.toString(16)}", "?", selectable = false)
        }
    }

    /**
     * `NamingScreen_MoveKeyboardCursor` (HGSS naming_screen.c:1396): add the delta with wrap-around on both axes (row 0
     * included), then keep stepping the same way while the cell is SKIP or the same button we started on (so a
     * multi-cell button is crossed in one press). The `prevY == 0` branch is unreachable: vertical moves out of row 0
     * never land on SKIP cells.
     */
    fun topology(cells: IntArray): Topology {
        val next = { from: Int, button: Button ->
            val delta = when (button) {
                Button.UP -> 0 to -1
                Button.DOWN -> 0 to 1
                Button.LEFT -> -1 to 0
                Button.RIGHT -> 1 to 0
                else -> null
            }
            delta?.let { (dx, dy) ->
                val start = cells[from]
                var x = (from % COLUMNS + dx).mod(COLUMNS)
                var y = (from / COLUMNS + dy).mod(ROWS)
                var guard = 0
                while ((cells[y * COLUMNS + x] == KEY_SKIP || (cells[y * COLUMNS + x] == start && start > KEY_BUTTON_START)) && guard++ < COLUMNS * ROWS) {
                    x = (x + dx).mod(COLUMNS)
                    y = (y + dy).mod(ROWS)
                }
                y * COLUMNS + x
            }
        }
        // Precomputed (memory buffers are reused per frame: never read RAM lazily).
        val links = cells.indices.associateWith { from ->
            DPAD.mapNotNull { b -> next(from, b)?.takeIf { it in cells.indices && it != from }?.let { b to it } }.toMap()
        }
        return Topology.of(links)
    }

    private val DPAD = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)
}
