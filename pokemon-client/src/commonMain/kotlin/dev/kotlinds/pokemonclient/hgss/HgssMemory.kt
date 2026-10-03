package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

/**
 * Safe typed reads of HeartGold / SoulSilver RAM, shared by the screen decoders: every read outside main RAM
 * returns 0 / null instead of failing, so a decoder reading a stale pointer gets "nothing" rather than garbage.
 *
 * @param version the address table of the running ROM.
 */
class HgssMemory(val memory: Memory, val version: HgssVersion) {

    fun inRam(addr: Long, size: Long = 1) = addr >= A.MAIN_RAM_START && addr + size <= A.MAIN_RAM_END

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
    fun fn(addr: Long): Long = u32(addr) and A.THUMB_MASK

    fun bytes(addr: Long, size: Int): ByteArray? =
        if (size >= 0 && inRam(addr, size.toLong())) memory.readBytes(addr, size) else null

    fun chars(addr: Long, count: Int): IntArray = IntArray(count) { u16(addr + 2L * it) }

    /** Reads a game `String` object (include/pm_string.h) if its magic matches. */
    fun gameString(strPtr: Long?): String? {
        if (strPtr == null) return null
        if (u32(strPtr + A.STR_MAGIC) != A.STRING_MAGIC) return null
        val max = u16(strPtr + A.STR_MAXSIZE)
        val size = u16(strPtr + A.STR_SIZE)
        if (size > max || size > 2048) return null
        return HgssText.decode(chars(strPtr + A.STR_DATA, size))
    }

    /** Text stored inline as game characters (terminated by 0xFFFF), e.g. names. */
    fun inlineText(addr: Long, maxChars: Int): String = HgssText.decode(chars(addr, maxChars))

    /**
     * The tasks of the game's main task queue (`gSystem.mainTaskQueue`, src/sys_task.c): pairs of (function
     * address without the Thumb bit, data pointer). Many screens (battle inputs, mart, save prompt...) are a task:
     * finding a known function here tells which screen is running.
     */
    fun mainTasks(): List<Pair<Long, Long?>> {
        val queue = ptr(version.gSystem + SYS_MAIN_TASK_QUEUE) ?: return emptyList()
        // SysTaskQueue: u16 limit, u16 activeCount, SysTask headSentinel (+4); SysTask: queue, prev, next (+8),
        // priority, data (+0x10), func (+0x14), runNow (include/sys_task.h). A circular list from the sentinel.
        val sentinel = queue + 4
        val tasks = mutableListOf<Pair<Long, Long?>>()
        var node = ptr(sentinel + 8)
        while (node != null && node != sentinel && tasks.size < MAX_TASKS) {
            tasks += fn(node + 0x14) to ptr(node + 0x10)
            node = ptr(node + 8)
        }
        return tasks
    }

    /** Data of the first main task running [function] (address without the Thumb bit), if any. */
    fun mainTaskData(function: Long): Long? = mainTasks().firstOrNull { it.first == function }?.second

    private companion object {
        /** `SysTaskQueue *mainTaskQueue` in `struct System` (include/system.h). */
        const val SYS_MAIN_TASK_QUEUE = 0x18L
        const val MAX_TASKS = 256
    }
}

/**
 * One family of screens decoded straight from RAM (battle menus, bag, party, keyboard...). Returns the screen when
 * one of its screens is up, null otherwise. Decoders are tried in order before the generic mapping.
 */
fun interface HgssScreenDecoder {
    fun decode(mem: HgssMemory, state: HgssState): dev.kotlinds.pokemonclient.state.Screen?
}
