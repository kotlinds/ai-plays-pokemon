package dev.kotlinds.pokemonclient.games.gen4

/**
 * Generation 4 string decoding (charmap.txt + control codes, src/string_control_code.c): shared by Diamond / Pearl /
 * Platinum and HeartGold / SoulSilver (same character table, same control codes, same packed trainer names).
 */
object Gen4Text {
    const val EOS = 0xFFFF
    const val CTRL = 0xFFFE

    const val NEWLINE = 0xE000
    const val SCROLL = 0x25BC   // \r: wait for A then scroll one line
    const val NEW_PAGE = 0x25BD // \f: wait for A then clear the box

    /**
     * What the 2-line message box shows after printing [printed] chars (all of them when null): the last two lines
     * of the current page. Lines are split by newlines and scrolls; a new page clears them.
     */
    fun visibleLines(chars: IntArray, printed: Int?): String {
        val end = printed?.coerceAtMost(chars.size) ?: chars.size
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        // A new page only clears the box once its first character prints (at the page break the old page is still shown).
        var newPage = false
        var i = 0
        while (i < end) {
            when (val c = chars[i]) {
                EOS -> break
                CTRL -> {
                    if (i + 2 >= chars.size) break
                    i += 3 + chars[i + 2]
                    continue
                }
                NEWLINE, SCROLL -> { lines += line.toString(); line.clear() }
                NEW_PAGE -> { lines += line.toString(); line.clear(); newPage = true }
                else -> {
                    if (newPage) { lines.clear(); newPage = false }
                    line.append(Gen4Charmap.table[c] ?: if (c == 0) "" else "?")
                }
            }
            i++
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.filter { it.isNotEmpty() }.takeLast(2).joinToString("\n").trim()
    }

    /** First character of a packed trainer name (`TRNAMECODE`, include/constants/charcode.h). */
    const val TRAINER_NAME_CODE = 0xF100

    private const val TRAINER_NAME_END = 0x1FF

    /**
     * Unpacks a trainer name stored packed ([TRAINER_NAME_CODE] then 9-bit codes, 15 bits used per u16, least
     * significant first, [TRAINER_NAME_END] ends it: src/pm_string.c String_Cat_HandleTrainerName). Other strings are
     * returned as they are. NPC trainer names are stored this way in the ROM and in the battle's trainer structures.
     */
    fun unpack(chars: IntArray): IntArray {
        if (chars.firstOrNull() != TRAINER_NAME_CODE) return chars
        val out = ArrayList<Int>()
        var buffer = 0L
        var bits = 0
        for (i in 1 until chars.size) {
            buffer = buffer or ((chars[i].toLong() and 0x7FFF) shl bits)
            bits += 15
            while (bits >= 9) {
                val code = (buffer and 0x1FF).toInt()
                buffer = buffer shr 9
                bits -= 9
                if (code == TRAINER_NAME_END) return (out + EOS).toIntArray()
                out += code
            }
        }
        return (out + EOS).toIntArray()
    }

    /**
     * Decodes a u16 character array (stops at EOS). Control sequences FFFE,cmd,argc,args... are skipped, except the
     * first one, replaced by [firstPlaceholder] when given (a message whose only buffer is the player's name...).
     */
    fun decode(raw: IntArray, firstPlaceholder: String? = null): String {
        val chars = unpack(raw)
        var placeholder = firstPlaceholder
        val sb = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c == EOS -> break
                c == CTRL -> {
                    if (i + 2 >= chars.size) break
                    val argc = chars[i + 2]
                    placeholder?.let { sb.append(it) }
                    placeholder = null
                    i += 3 + argc
                    continue
                }
                else -> sb.append(Gen4Charmap.table[c] ?: if (c == 0) "" else "?")
            }
            i++
        }
        return sb.toString()
    }
}
