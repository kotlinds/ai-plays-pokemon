package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * The OPTIONS screen (src/options_app.c, overlay 54, launched from the start menu as the field's sub-application).
 *
 * One entry per row, in the game's fixed order; the id carries the row and the value being edited (typed, never the
 * shown text): `setting:text_speed:slow|mid|fast`, `setting:battle_scene:on|off`, `setting:battle_style:shift|set`,
 * `setting:sound:stereo|mono`, `setting:button_mode:<n>`, `setting:frame:<n>`, and the last row
 * `setting:exit:confirm|quit` (A there leaves, saving the options on CONFIRM). UP / DOWN change the row (wrapping),
 * LEFT / RIGHT the value (`OptionsApp_HandleKeyInput`); B leaves without saving.
 *
 * `OptionsApp_Data` (0x32C bytes): `exitState` +0x04 (2 = taking input), bitfield +0x10 (bits 0-1 `unk10_0`: 0 while
 * editing, 1 confirm, 2 quit; bits 2-4 the current row), `menuEntries[7]` at +0x84, 0x54 bytes each, `value` at +2.
 */
internal object HgssOptionsScreen : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        val om = subApp(mem) ?: return null
        if (mem.s32(om + A.OM_OVY_ID) != OVY_OPTIONS) return null
        if (mem.s32(om + A.OM_EXEC_STATE) != 2) return Screen.Animation(AnimationKind.TRANSITION)
        val data = mem.ptr(om + A.OM_DATA) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val flags = mem.u32(data + FLAGS)
        if (mem.u32(data + EXIT_STATE) != INPUT_EXIT_STATE || flags and 3L != 0L) return Screen.Animation(AnimationKind.TRANSITION)
        val row = ((flags shr 2) and 7L).toInt()
        val entries = Row.entries.map { r ->
            val value = mem.u16(data + MENU_ENTRIES + r.ordinal * MENU_ENTRY_SIZE + 2)
            Entry("setting:${r.id}:${r.valueId(value)}", "${r.label}: ${r.valueLabel(value)}")
        }
        return Screen.ListMenu(
            kind = MenuKind.OTHER,
            entries = entries,
            cursor = if (row in entries.indices) Cursor.At(row) else Cursor.Hidden,
            topology = Topology.vertical(entries.size, wrap = true),
            cancel = CancelBehavior.CLOSES,
        )
    }

    /** The field's sub-application, only while the field is the running top-level app (sFieldSysPtr dangles otherwise). */
    private fun subApp(mem: HgssMemory): Long? {
        val v = mem.version
        val om = mem.ptr(v.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return null
        val init = mem.fn(om + A.OM_INIT)
        if (init != v.fnFieldContinueAppInit && init != v.fnFieldNewGameAppInit) return null
        val fs = mem.ptr(om + A.OM_DATA) ?: mem.ptr(v.fieldSystemPtr) ?: return null
        return mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) }
    }

    /** The rows of the screen, in order (`MENU_ENTRY_*`), with their values' ids. */
    enum class Row(val id: String, val label: String, private val values: List<String>?) {
        TEXT_SPEED("text_speed", "TEXT SPEED", listOf("slow", "mid", "fast")),
        BATTLE_SCENE("battle_scene", "BATTLE SCENE", listOf("on", "off")),
        BATTLE_STYLE("battle_style", "BATTLE STYLE", listOf("shift", "set")),
        SOUND("sound", "SOUND", listOf("stereo", "mono")),
        BUTTON_MODE("button_mode", "BUTTON MODE", null),
        FRAME("frame", "FRAME", null),
        EXIT("exit", "EXIT", listOf("quit", "confirm")),
        ;

        fun valueId(value: Int) = values?.getOrNull(value) ?: value.toString()

        fun valueLabel(value: Int) = when (this) {
            FRAME -> "TYPE ${value + 1}"
            EXIT -> if (value == 1) "CONFIRM" else "QUIT"
            else -> valueId(value).uppercase()
        }
    }

    /** Overlay of the options app (`APP_BY_OVERLAY`). */
    private const val OVY_OPTIONS = 54
    private const val EXIT_STATE = 0x04L
    private const val INPUT_EXIT_STATE = 2L
    private const val FLAGS = 0x10L
    private const val MENU_ENTRIES = 0x84L
    private const val MENU_ENTRY_SIZE = 0x54L
}
