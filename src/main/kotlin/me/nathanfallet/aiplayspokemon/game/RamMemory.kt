package me.nathanfallet.aiplayspokemon.game

/**
 * [Memory] over a snapshot of the Nintendo DS main RAM (4 MB, as returned by the emulator).
 *
 * Game code uses ARM9 bus addresses: main RAM starts at 0x02000000 and is mirrored every 4 MB up to
 * 0x02FFFFFF, so an address maps to `(addr - 0x02000000) & 0x3FFFFF` in the snapshot.
 */
class RamMemory(private val ram: ByteArray) : Memory {

    private fun offset(addr: Long, size: Int): Int? {
        if (addr !in MAIN_RAM_START..MAIN_RAM_END) return null
        val offset = ((addr - MAIN_RAM_START) and (ram.size - 1).toLong()).toInt()
        return if (offset + size <= ram.size) offset else null
    }

    override fun read8(addr: Long): Int {
        val o = offset(addr, 1) ?: return 0
        return ram[o].toInt() and 0xFF
    }

    override fun read16(addr: Long): Int {
        val o = offset(addr, 2) ?: return 0
        return (ram[o].toInt() and 0xFF) or ((ram[o + 1].toInt() and 0xFF) shl 8)
    }

    override fun read32(addr: Long): Long {
        val o = offset(addr, 4) ?: return 0
        return (ram[o].toLong() and 0xFF) or
            ((ram[o + 1].toLong() and 0xFF) shl 8) or
            ((ram[o + 2].toLong() and 0xFF) shl 16) or
            ((ram[o + 3].toLong() and 0xFF) shl 24)
    }

    override fun readBytes(addr: Long, size: Int): ByteArray {
        val o = offset(addr, size) ?: return ByteArray(size)
        return ram.copyOfRange(o, o + size)
    }

    private companion object {
        const val MAIN_RAM_START = 0x02000000L
        const val MAIN_RAM_END = 0x02FFFFFFL
    }
}
