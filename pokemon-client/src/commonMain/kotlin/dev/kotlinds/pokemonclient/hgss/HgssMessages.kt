package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.TextBankId

/**
 * The text banks of HeartGold / SoulSilver used by [HgssGameData]: members of the message archive
 * (`msgdata/msg.narc`, ROM path [HgssGameData.MESSAGE_NARC]), by index (`NARC_msg_msg_XXXX_bin`, files/msgdata/msg.naix).
 *
 * The indices are the same in every language release: a translated ROM has the same banks with translated lines.
 */
object HgssTextBanks {
    /** Item names, index = item id (src/item.c GetItemNameIntoString). */
    val ITEM_NAMES = TextBankId(222)

    /** Species names, index = species id (src/message_format.c BufferSpeciesName). */
    val SPECIES_NAMES = TextBankId(237)

    /** Map section (location) names, index = mapsec id. */
    val MAP_SECTION_NAMES = TextBankId(279)

    /** Ability names, index = ability id (BufferAbilityName). */
    val ABILITY_NAMES = TextBankId(720)

    /** Trainer class names, index = trainer class id (BufferTrainerClassName). */
    val TRAINER_CLASS_NAMES = TextBankId(730)

    /** Type names, index = type id (BufferTypeName). */
    val TYPE_NAMES = TextBankId(735)

    /** Move names, index = move id (BufferMoveName). */
    val MOVE_NAMES = TextBankId(750)
}

/**
 * Decoder of one Generation 4 message file (`MAT` table, src/msgdata.c): `u16 count, u16 key`, then `count`
 * entries `{u32 offset, u32 length}` encrypted with a key derived from [key] and the line index (Decrypt1), then the
 * u16 characters of each line, encrypted with a stream derived from the line index (Decrypt2).
 *
 * Lines are returned as raw u16 character codes; [line] decodes them to text with the charmap ([HgssText.decode]).
 */
class HgssMessageFile(private val bytes: ByteArray) {

    /** Number of lines in the file. */
    val count: Int = if (bytes.size >= 4) HgssRomBytes.u16(bytes, 0) else 0

    private val key: Int = if (bytes.size >= 4) HgssRomBytes.u16(bytes, 2) else 0

    /** The u16 character codes of line [index] (decrypted, possibly compressed), null when out of range. */
    fun rawLine(index: Int): IntArray? {
        if (index !in 0 until count) return null
        val entryKey = ((key.toLong() * 765L * (index + 1)) and 0xFFFF).let { it or (it shl 16) }
        val offset = (HgssRomBytes.u32(bytes, 4 + index * 8) xor entryKey).toInt()
        val length = (HgssRomBytes.u32(bytes, 8 + index * 8) xor entryKey).toInt()
        if (offset < 0 || length < 0 || offset + length * 2 > bytes.size) return null
        var stream = ((index + 1) * 596947) and 0xFFFF
        val chars = IntArray(length) { i ->
            val c = HgssRomBytes.u16(bytes, offset + i * 2) xor stream
            stream = (stream + 18749) and 0xFFFF
            c
        }
        return if (chars.firstOrNull() == COMPRESSED) decompress(chars) else chars
    }

    /** Line [index] as text (control codes removed), null when out of range. */
    fun line(index: Int): String? = rawLine(index)?.let { HgssText.decode(it) }

    /** Every line as text, index = line number. */
    fun lines(): List<String> = (0 until count).map { line(it) ?: "" }

    companion object {
        /** First character of a compressed line: the rest packs 9-bit codes (used by the Japanese releases). */
        const val COMPRESSED = 0xF100

        private const val COMPRESSED_END = 0x1FF

        /** Unpacks a compressed line: 9-bit codes, least significant bits first, [COMPRESSED_END] ends the line. */
        private fun decompress(chars: IntArray): IntArray {
            val out = ArrayList<Int>()
            var buffer = 0L
            var bits = 0
            for (i in 1 until chars.size) {
                buffer = buffer or (chars[i].toLong() shl bits)
                bits += 16
                while (bits >= 9) {
                    val code = (buffer and 0x1FF).toInt()
                    buffer = buffer shr 9
                    bits -= 9
                    if (code == COMPRESSED_END) return (out + HgssText.EOS).toIntArray()
                    out += code
                }
            }
            return (out + HgssText.EOS).toIntArray()
        }
    }
}
