package dev.kotlinds.pokemonclient.games.gen4

/**
 * Decoder of one Generation 4 message file (`MAT` table, src/msgdata.c): `u16 count, u16 key`, then `count`
 * entries `{u32 offset, u32 length}` encrypted with a key derived from [key] and the line index (Decrypt1), then the
 * u16 characters of each line, encrypted with a stream derived from the line index (Decrypt2).
 *
 * Lines are returned as raw u16 character codes; [line] decodes them to text with the charmap ([Gen4Text.decode]).
 */
class Gen4MessageFile(private val bytes: ByteArray) {

    /** Number of lines in the file. */
    val count: Int = if (bytes.size >= 4) Gen4RomBytes.u16(bytes, 0) else 0

    private val key: Int = if (bytes.size >= 4) Gen4RomBytes.u16(bytes, 2) else 0

    /** The u16 character codes of line [index] (decrypted, unpacked), null when out of range. */
    fun rawLine(index: Int): IntArray? {
        if (index !in 0 until count) return null
        val entryKey = ((key.toLong() * 765L * (index + 1)) and 0xFFFF).let { it or (it shl 16) }
        val offset = (Gen4RomBytes.u32(bytes, 4 + index * 8) xor entryKey).toInt()
        val length = (Gen4RomBytes.u32(bytes, 8 + index * 8) xor entryKey).toInt()
        if (offset < 0 || length < 0 || offset + length * 2 > bytes.size) return null
        var stream = ((index + 1) * 596947) and 0xFFFF
        val chars = IntArray(length) { i ->
            val c = Gen4RomBytes.u16(bytes, offset + i * 2) xor stream
            stream = (stream + 18749) and 0xFFFF
            c
        }
        return Gen4Text.unpack(chars)
    }

    /** Line [index] as text (control codes removed), null when out of range. */
    fun line(index: Int): String? = rawLine(index)?.let { Gen4Text.decode(it) }

    /** Every line as text, index = line number. */
    fun lines(): List<String> = (0 until count).map { line(it) ?: "" }

    companion object {
        /** First character of a compressed line (packed 9-bit codes, used by NPC trainer names): see [Gen4Text.unpack]. */
        const val COMPRESSED = Gen4Text.TRAINER_NAME_CODE
    }
}
