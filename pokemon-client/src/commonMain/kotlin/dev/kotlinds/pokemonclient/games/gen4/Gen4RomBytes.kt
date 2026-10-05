package dev.kotlinds.pokemonclient.games.gen4

/** Little-endian reads of ROM files (NARC members, the ARM9 binary), shared by the Gen 4 ROM decoders. */
internal object Gen4RomBytes {
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun s32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    fun u32(b: ByteArray, o: Int): Long = s32(b, o).toLong() and 0xFFFFFFFFL
}
