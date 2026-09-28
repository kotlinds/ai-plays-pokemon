package me.nathanfallet.aiplayspokemon.game

import kotlin.test.Test
import kotlin.test.assertEquals

class RamMemoryTest {

    private val ram = ByteArray(4 * 1024 * 1024).also {
        it[0x10] = 0x78
        it[0x11] = 0x56
        it[0x12] = 0x34
        it[0x13] = 0xF2.toByte()
    }
    private val memory = RamMemory(ram)

    @Test
    fun `reads little-endian values at ARM9 addresses`() {
        assertEquals(0x78, memory.read8(0x02000010))
        assertEquals(0x5678, memory.read16(0x02000010))
        assertEquals(0xF2345678L, memory.read32(0x02000010))
    }

    @Test
    fun `main RAM is mirrored every 4 MB`() {
        assertEquals(0xF2345678L, memory.read32(0x02400010))
    }

    @Test
    fun `out of range reads return zero`() {
        assertEquals(0, memory.read8(0x01FFFFFF))
        assertEquals(0L, memory.read32(0x03000000))
        assertEquals(0, memory.read16(0x023FFFFF)) // would cross the end of the snapshot
    }
}
