package dev.kotlinds.pokemonclient.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The message file decoder and the Kotlin charmap, without a ROM. */
class HgssMessagesTest {

    @Test
    fun `charmap table equals the decomp charmap`() {
        // The bundled charmap.txt, parsed the way HgssData used to (commands excluded, breaks and pocket icons mapped).
        val pocketIcons = setOf("♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏")
        val expected = HgssData.lines("charmap.txt").mapNotNull { l ->
            if (l.indexOf('=') != 4) return@mapNotNull null
            val code = l.substring(0, 4).toIntOrNull(16) ?: return@mapNotNull null
            code to when (val s = l.substring(5)) {
                "\\n", "\\r" -> "\n"
                "\\f" -> "\n\n"
                in pocketIcons -> ""
                else -> s
            }
        }.toMap()
        if (expected.isEmpty()) return // resource removed: nothing left to compare
        assertEquals(expected, HgssCharmap.table)
    }

    @Test
    fun `decrypts lines`() {
        val reverse = HgssCharmap.table.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
        val lines = listOf("Bulbasaur", "Poké Ball", "")
        val file = HgssMessageFile(encrypt(0x1234, lines.map { l -> l.map { reverse.getValue(it) } + HgssText.EOS }))
        assertEquals(3, file.count)
        assertEquals(lines, file.lines())
        assertNull(file.line(3))
    }

    @Test
    fun `decompresses 9-bit lines`() {
        // Packed like NPC trainer names: 9-bit codes, 15 bits per u16 (src/pm_string.c String_Cat_HandleTrainerName).
        val codes = listOf(0x12B, 0x150, 0x15F, 0x14C) // printable codes below 0x1FF
        var bits = 0L
        var n = 0
        for (c in codes + 0x1FF) { bits = bits or (c.toLong() shl n); n += 9 }
        // 15 bits per u16 (String_Cat_HandleTrainerName, src/pm_string.c), the top bit unused.
        val packed = (0 until (n + 14) / 15).map { ((bits shr (it * 15)) and 0x7FFF).toInt() }
        val file = HgssMessageFile(encrypt(7, listOf(listOf(HgssMessageFile.COMPRESSED) + packed)))
        assertEquals(codes.joinToString("") { HgssCharmap.table.getValue(it) }, file.line(0))
    }

    @Test
    fun `decodes a packed trainer name read from RAM`() {
        val codes = listOf(0x12D, 0x145, 0x156) // "C", "a", "r"
        var bits = 0L
        var n = 0
        for (c in codes + 0x1FF) { bits = bits or (c.toLong() shl n); n += 9 }
        val packed = listOf(HgssText.TRAINER_NAME_CODE) + (0 until (n + 14) / 15).map { ((bits shr (it * 15)) and 0x7FFF).toInt() }
        val name = (packed + List(8 - packed.size) { 0 }).toIntArray()
        assertEquals(codes.joinToString("") { HgssCharmap.table.getValue(it) }, HgssText.decode(name))
    }

    /** Builds a message file with the game's encryption (inverse of src/msgdata.c Decrypt1 / Decrypt2). */
    private fun encrypt(key: Int, lines: List<List<Int>>): ByteArray {
        val header = 4 + lines.size * 8
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        u16(lines.size); u16(key)
        var offset = header
        lines.forEachIndexed { i, l ->
            val k = ((key.toLong() * 765 * (i + 1)) and 0xFFFF).let { it or (it shl 16) }
            u32(offset.toLong() xor k); u32(l.size.toLong() xor k)
            offset += l.size * 2
        }
        lines.forEachIndexed { i, l ->
            var s = ((i + 1) * 596947) and 0xFFFF
            for (c in l) { u16(c xor s); s = (s + 18749) and 0xFFFF }
        }
        return out.toByteArray()
    }
}
