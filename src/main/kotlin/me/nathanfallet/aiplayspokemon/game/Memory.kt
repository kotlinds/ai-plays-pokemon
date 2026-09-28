package me.nathanfallet.aiplayspokemon.game

interface Memory {
    /** addr is an ARM9 bus address in main RAM (0x02000000..0x023FFFFF); little-endian. Returns 0 when out of range. */
    fun read8(addr: Long): Int
    fun read16(addr: Long): Int
    fun read32(addr: Long): Long
    fun readBytes(addr: Long, size: Int): ByteArray
}
