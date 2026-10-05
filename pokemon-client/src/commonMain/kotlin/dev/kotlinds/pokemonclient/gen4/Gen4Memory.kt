package dev.kotlinds.pokemonclient.gen4

import dev.kotlinds.pokemonclient.Memory

/**
 * Safe typed reads of a Generation 4 game's main RAM (Diamond / Pearl / Platinum, HeartGold / SoulSilver), shared by
 * the screen decoders: every read outside main RAM returns 0 / null instead of failing, so a decoder reading a stale
 * pointer gets "nothing" rather than garbage.
 *
 * The engine structures read here (`String`, `SysTaskManager`) have the same layout in every Gen 4 game
 * ([Gen4Structs]); only the address of `gSystem` ([gSystem]) differs per ROM.
 */
open class Gen4Memory(val memory: Memory, private val gSystem: Long) {

    fun inRam(addr: Long, size: Long = 1) = addr >= Gen4Structs.MAIN_RAM_START && addr + size <= Gen4Structs.MAIN_RAM_END

    fun u8(addr: Long): Int = if (inRam(addr)) memory.read8(addr) and 0xFF else 0
    fun u16(addr: Long): Int = if (inRam(addr, 2)) memory.read16(addr) and 0xFFFF else 0
    fun s8(addr: Long): Int = u8(addr).toByte().toInt()
    fun s16(addr: Long): Int = u16(addr).toShort().toInt()
    fun u32(addr: Long): Long = if (inRam(addr, 4)) memory.read32(addr) and 0xFFFFFFFFL else 0L
    fun s32(addr: Long): Int = u32(addr).toInt()

    /** Follows a pointer stored at [addr]; null if NULL, outside main RAM or misaligned. */
    fun ptr(addr: Long, align: Int = 4): Long? {
        val p = u32(addr)
        if (p == 0L || !inRam(p, 4) || p % align != 0L) return null
        return p
    }

    /** A function pointer stored at [addr], without the Thumb bit. */
    fun fn(addr: Long): Long = u32(addr) and Gen4Structs.THUMB_MASK

    fun bytes(addr: Long, size: Int): ByteArray? =
        if (size >= 0 && inRam(addr, size.toLong())) memory.readBytes(addr, size) else null

    fun chars(addr: Long, count: Int): IntArray = IntArray(count) { u16(addr + 2L * it) }

    /**
     * Reads a game `String` object (include/pm_string.h, include/string_gf.h) if its magic matches. [allowFreed] also
     * reads one that String_Delete / String_Free just marked invalid: some screens free their message once printed,
     * while it stays on screen.
     */
    fun gameString(strPtr: Long?, allowFreed: Boolean = false): String? {
        if (strPtr == null) return null
        val magic = u32(strPtr + Gen4Structs.STR_MAGIC)
        if (magic != Gen4Structs.STRING_MAGIC && !(allowFreed && magic == Gen4Structs.STRING_INVAL)) return null
        val max = u16(strPtr + Gen4Structs.STR_MAXSIZE)
        val size = u16(strPtr + Gen4Structs.STR_SIZE)
        if (size > max || size > 2048) return null
        return Gen4Text.decode(chars(strPtr + Gen4Structs.STR_DATA, size))
    }

    /** Text stored inline as game characters (terminated by 0xFFFF), e.g. names. */
    fun inlineText(addr: Long, maxChars: Int): String = Gen4Text.decode(chars(addr, maxChars))

    /**
     * The tasks of the game's main task queue (`gSystem.mainTaskQueue` / `mainTaskMgr`, src/sys_task.c): pairs of
     * (function address without the Thumb bit, data pointer). Many screens (battle inputs, mart, save prompt...) are a
     * task: finding a known function here tells which screen is running.
     */
    fun mainTasks(): List<Pair<Long, Long?>> {
        val queue = ptr(gSystem + Gen4Structs.SYS_MAIN_TASK_QUEUE) ?: return emptyList()
        // SysTaskQueue: u16 limit, u16 activeCount, SysTask headSentinel (+4); SysTask: queue, prev, next (+8),
        // priority, data (+0x10), func (+0x14), runNow (include/sys_task.h). A circular list from the sentinel.
        val sentinel = queue + 4
        val tasks = mutableListOf<Pair<Long, Long?>>()
        var node = ptr(sentinel + 8)
        while (node != null && node != sentinel && tasks.size < MAX_TASKS) {
            tasks += fn(node + Gen4Structs.SYSTASK_FUNC) to ptr(node + Gen4Structs.SYSTASK_DATA)
            node = ptr(node + 8)
        }
        return tasks
    }

    /** Data of the first main task running [function] (address without the Thumb bit), if any. */
    fun mainTaskData(function: Long): Long? = mainTasks().firstOrNull { it.first == function }?.second

    /**
     * The `TextPrinter` of printer [printerId], from the array of printer tasks at [printerTasks] (`sTextPrinterTasks`,
     * src/text.c: one `SysTask *` per printer id, NULL once the printer is done), or null when it isn't running.
     */
    fun textPrinter(printerTasks: Long, printerId: Int): Long? =
        if (printerId !in 0 until Gen4Structs.MAX_TEXT_PRINTERS) null
        else ptr(printerTasks + 4L * printerId)?.let { ptr(it + Gen4Structs.SYSTASK_DATA) }

    private companion object {
        const val MAX_TASKS = 256
    }
}

/**
 * Engine structures with the same layout in every Generation 4 game (NitroSDK / Game Freak engine: overlay manager,
 * `gSystem`, `String`, `SysTask`, text printer, script manager, map objects, location). Checked in both decomps:
 * pret/pokeheartgold and pret/pokeplatinum (offsets compiled with the decomp's compiler, `offsetof`).
 */
object Gen4Structs {
    const val MAIN_RAM_START = 0x02000000L
    const val MAIN_RAM_END = 0x02400000L // exclusive (4 MB)
    const val THUMB_MASK = 0xFFFFFFFEL

    // struct System (include/system.h): gSystem
    /** `vblankCallback` (Callback): apps install their own (the naming keyboard: NamingScreen_VBlankCallback). */
    const val SYS_VBLANK_CALLBACK = 0x00L
    const val SYS_MAIN_TASK_QUEUE = 0x18L
    const val SYS_VBLANK_COUNTER = 0x2CL
    /** `heldKeysRaw`: the buttons physically held, as read by the game this frame (PAD_* bits). */
    const val SYS_HELD_KEYS_RAW = 0x38L
    const val SYS_HELD_KEYS = 0x44L
    const val SYS_NEW_KEYS = 0x48L

    // ApplicationManager / OverlayManager (include/overlay_manager.h): {init, main, exit, overlayID}, execState...
    const val OM_INIT = 0x00L
    const val OM_MAIN = 0x04L
    const val OM_EXEC_STATE = 0x10L  // 0 load, 1 init, 2 main, 3 exit
    const val OM_PROC_STATE = 0x14L  // the app's own state machine (*state)
    const val OM_ARGS = 0x18L
    const val OM_DATA = 0x1CL
    const val OM_EXEC_MAIN = 2

    // The top-level application (`sApplication` in Platinum src/main.c, `_02111868` in HGSS src/main.c)
    const val APP_OVERLAY_ID = 0x00L
    const val APP_MANAGER = 0x04L

    // String (include/pm_string.h / string_gf.h)
    const val STR_MAXSIZE = 0x00L
    const val STR_SIZE = 0x02L
    const val STR_MAGIC = 0x04L
    const val STR_DATA = 0x08L
    const val STRING_MAGIC = 0xB6F8D2ECL
    const val STRING_INVAL = 0xB6F8D2EDL

    // SysTask (include/sys_task_manager.h)
    const val SYSTASK_DATA = 0x10L
    const val SYSTASK_FUNC = 0x14L

    // TextPrinter (include/render_text.h): template.toPrint.raw (+0) points into the String being printed
    const val TP_CURRENT_CHAR = 0x00L
    const val TP_STATE = 0x28L
    /** RENDER_STATE_CLEAR / RENDER_STATE_START_SCROLL (src/render_text.c): waiting for A at a page break. */
    val TEXT_PRINTER_WAIT_STATES = setOf(2, 3)
    const val MAX_TEXT_PRINTERS = 8

    // FieldTask / TaskManager (include/field_task.h)
    const val FIELD_TASK_FUNC = 0x04L
    const val FIELD_TASK_ENV = 0x0CL

    // ScriptManager / ScriptEnvironment (include/script_manager.h)
    const val SCRIPT_MANAGER_MAGIC = 0x3643FL
    const val SM_MAGIC = 0x00L
    const val SM_MESSAGE_ID = 0x05L        // u8: text printer of the field message
    const val SM_MSG_BOX_OPEN = 0x08L
    const val SM_ACTIVE_CONTEXTS = 0x09L
    const val SM_CONTEXTS = 0x38L          // ScriptContext *[NUM_SCRIPT_CONTEXTS]
    const val SC_STATE = 0x01L             // 0 stopped, 1 bytecode, 2 paused on a native wait
    const val SC_NATIVE = 0x04L            // shouldResume / native_ptr

    // Location (include/location.h)
    const val LOC_MAP_ID = 0x00L
    const val LOC_X = 0x08L
    const val LOC_Z = 0x0CL
    const val LOC_DIRECTION = 0x10L

    // PlayerAvatar (include/player_avatar.h) and MapObject (src/map_object.c)
    const val PA_MAP_OBJECT = 0x30L
    const val MO_LOCAL_ID = 0x08L
    const val MO_FACING = 0x28L
    const val MO_PREVIOUS_X = 0x58L
    const val MO_PREVIOUS_Z = 0x60L
    const val MO_X = 0x64L
    const val MO_Y = 0x68L
    const val MO_Z = 0x6CL
    /** VecFx32: at rest x = X*16*4096 + 8*4096 (tile center). */
    const val MO_POSITION_VECTOR = 0x70L

    /** `facingDirection` values: DIR_NORTH 0, DIR_SOUTH 1, DIR_WEST 2, DIR_EAST 3. */
    val DIRECTIONS = listOf(dev.kotlinds.pokemonclient.Direction.NORTH, dev.kotlinds.pokemonclient.Direction.SOUTH,
        dev.kotlinds.pokemonclient.Direction.WEST, dev.kotlinds.pokemonclient.Direction.EAST)
}
