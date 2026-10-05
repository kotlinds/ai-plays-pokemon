package dev.kotlinds.pokemonclient.platinum

import dev.kotlinds.pokemonclient.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource

/**
 * The field of Platinum (basic support): where the player is, and whether they can walk or a script runs a message.
 *
 * `FieldSystem` (include/field/field_system.h) differs from HGSS's, but what is read through it is Gen 4 shared
 * ([S]): the `Location` (save), the player's `MapObject` (position, height, facing), the `FieldTask` stack, the
 * `ScriptManager` (message box, text printer, script contexts).
 */
internal object PlatinumField {

    /** FieldSystem offsets (offsetof with the decomp's compiler). */
    private const val FS_PROCESS_MANAGER = 0x00L
    private const val FS_TASK = 0x10L
    private const val FS_LOCATION = 0x1CL
    private const val FS_PLAYER_AVATAR = 0x3CL
    private const val FS_RUNNING_FIELD_MAP = 0x68L

    /** FieldProcessManager: `parent` (the field map overlay), `child` (an application launched from the field), `pause`. */
    private const val PM_CHILD = 0x04L
    private const val PM_PAUSE = 0x08L

    /** ScriptManager.msgBuf (Platinum include/script_manager.h:162; HGSS has another field there). */
    private const val SM_MSG_BUF = 0x44L
    private const val SCRIPT_CONTEXTS = 2
    private const val CONTEXT_WAITING = 2

    /** The field system while the field app runs, else null (`sFieldSystem` stays set after the field app ends). */
    fun fieldSystem(mem: PlatinumMemory, top: PlatinumTopApp): Long? =
        if (top.app == PlatinumApp.FIELD) mem.ptr(mem.version.fieldSystemPtr) else null

    /** The player's position: the live `MapObject` when the map runs, the saved `Location` otherwise. */
    fun position(mem: PlatinumMemory, fs: Long, zoneName: (Int) -> String?): FieldState? {
        val loc = mem.ptr(fs + FS_LOCATION) ?: return null
        val mapId = mem.s32(loc + S.LOC_MAP_ID)
        var x = mem.s32(loc + S.LOC_X)
        var z = mem.s32(loc + S.LOC_Z)
        var facing = mem.s32(loc + S.LOC_DIRECTION)
        var height = 0
        var moving = false
        val mo = mem.ptr(fs + FS_PLAYER_AVATAR)?.let { mem.ptr(it + S.PA_MAP_OBJECT) }
        if (mo != null && mem.s32(fs + FS_RUNNING_FIELD_MAP) != 0) {
            x = mem.s32(mo + S.MO_X)
            z = mem.s32(mo + S.MO_Z)
            height = mem.s32(mo + S.MO_Y)
            facing = mem.s32(mo + S.MO_FACING)
            // At rest the position vector is exactly the tile center (src/map_object.c).
            val px = mem.s32(mo + S.MO_POSITION_VECTOR)
            val pz = mem.s32(mo + S.MO_POSITION_VECTOR + 8)
            moving = px != x * 16 * 4096 + 8 * 4096 || pz != z * 16 * 4096 + 8 * 4096 ||
                mem.s32(mo + S.MO_PREVIOUS_X) != x || mem.s32(mo + S.MO_PREVIOUS_Z) != z
        }
        return FieldState(
            mapId = mapId, mapName = zoneName(mapId) ?: "zone $mapId", x = x, y = z, height = height,
            facing = S.DIRECTIONS.getOrNull(facing), movement = MovementMode.WALK, moving = moving,
        )
    }

    /**
     * What the field waits for: free to walk (no field task, the map running, not paused: the input check of
     * src/field_system.c:236), a script's message (field text printer, A waits of the script context), or something
     * not decoded yet.
     */
    fun screen(mem: PlatinumMemory, fs: Long, field: FieldState?): Screen {
        val pm = mem.ptr(fs + FS_PROCESS_MANAGER)
        if (pm != null && mem.ptr(pm + PM_CHILD) != null) return Screen.Unknown("an application launched from the field", Awaiting.INPUT)
        val task = mem.ptr(fs + FS_TASK)
        if (task != null) return task(mem, task)
        if (mem.s32(fs + FS_RUNNING_FIELD_MAP) == 0 || (pm != null && mem.s32(pm + PM_PAUSE) != 0) || mem.fading) {
            return Screen.Animation(AnimationKind.TRANSITION)
        }
        return Screen.Overworld(awaiting = if (field?.moving == true) Awaiting.ANIMATION else Awaiting.INPUT)
    }

    private fun task(mem: PlatinumMemory, task: Long): Screen {
        val v = mem.version
        if (mem.fn(task + S.FIELD_TASK_FUNC) in v.fnMapTransitionTasks) return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.fn(task + S.FIELD_TASK_FUNC) != v.fnFieldTaskRunScript) return Screen.Unknown("field task 0x${mem.fn(task + S.FIELD_TASK_FUNC).toString(16)} (start menu, scene...)", Awaiting.INPUT)
        val sm = mem.ptr(task + S.FIELD_TASK_ENV)?.takeIf { mem.u32(it + S.SM_MAGIC) == S.SCRIPT_MANAGER_MAGIC }
            ?: return Screen.Animation(AnimationKind.CUTSCENE)
        val waits = (0 until SCRIPT_CONTEXTS).mapNotNull { i ->
            mem.ptr(sm + S.SM_CONTEXTS + 4L * i)?.takeIf { mem.u8(it + S.SC_STATE) == CONTEXT_WAITING }?.let { mem.fn(it + S.SC_NATIVE) }
        }
        val boxOpen = mem.u8(sm + S.SM_MSG_BOX_OPEN) != 0
        if (v.fnScrWaitForYesNoResult in waits) return Screen.Unknown("a script's yes / no (not decoded yet for Platinum)", Awaiting.INPUT)
        // Signs (and tips like "The X Button opens the menu!") print in a signpost window, not the message box
        // (ScrCmd_GetSignpostInput / WaitScrollingSignpostInput, src/scrcmd.c).
        if (v.fnScrSignpostInput in waits || v.fnScrSignpostPrinting in waits) {
            val printer = mem.textPrinter(v.textPrinterTasks, mem.u8(sm + S.SM_MESSAGE_ID))
            val awaiting = if (v.fnScrSignpostInput in waits || printer?.let { mem.u8(it + S.TP_STATE) in S.TEXT_PRINTER_WAIT_STATES } == true) Awaiting.INPUT else Awaiting.TEXT_PRINTING
            return Screen.Dialogue(TextSource.SIGN, null, visiblePage(mem, mem.ptr(sm + SM_MSG_BUF), printer), awaiting)
        }
        if (!boxOpen) return Screen.Animation(AnimationKind.CUTSCENE, "script running (waits ${waits.joinToString { "0x" + it.toString(16) }})")
        val printer = mem.textPrinter(v.textPrinterTasks, mem.u8(sm + S.SM_MESSAGE_ID))
        val text = visiblePage(mem, mem.ptr(sm + SM_MSG_BUF), printer)
        val awaitsA = waits.any { it == v.fnScrCheckABPress || it == v.fnScrCheckABXPadPress || it == v.fnScrCheckABPadPress || it == v.fnScrDecrementABPressTimer }
        val awaiting = when {
            awaitsA -> Awaiting.INPUT
            printer != null -> if (mem.u8(printer + S.TP_STATE) in S.TEXT_PRINTER_WAIT_STATES) Awaiting.INPUT else Awaiting.TEXT_PRINTING
            else -> Awaiting.ANIMATION
        }
        return Screen.Dialogue(TextSource.FIELD, null, text, awaiting)
    }
}
