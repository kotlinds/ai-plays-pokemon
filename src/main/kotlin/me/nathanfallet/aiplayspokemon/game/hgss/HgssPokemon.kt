package me.nathanfallet.aiplayspokemon.game.hgss

import me.nathanfallet.aiplayspokemon.game.hgss.HgssAddresses as A

/**
 * Gen 4 Pokémon structure decryption (src/pokemon.c, src/math_util.c).
 *
 * Layout of a party `Pokemon` (0xEC bytes):
 *  0x00 u32 personality, 0x04 u16 flags (bit0 partyDecrypted, bit1 boxDecrypted, bit2 checksumFailed),
 *  0x06 u16 checksum, 0x08..0x87 four 0x20-byte blocks A/B/C/D (shuffled by (pid >> 13) & 31, encrypted
 *  with the LCRNG seeded by the checksum), 0x88..0xEB PartyPokemon (encrypted with the LCRNG seeded by pid).
 * LCRNG: seed = seed * 1103515245 + 24691; key = seed >> 16 (MonEncryptionLCRNG).
 */
object HgssPokemon {

    class Decoded(
        val personality: Long,
        /** Decrypted blocks in canonical order A, B, C, D (each 0x20 bytes). */
        val blockA: ByteArray,
        val blockB: ByteArray,
        val blockC: ByteArray,
        val blockD: ByteArray,
        /** Decrypted PartyPokemon (0x64 bytes) or null for a box mon. */
        val party: ByteArray?,
        val checksumOk: Boolean,
    ) {
        val species get() = u16(blockA, 0x00)
        val heldItem get() = u16(blockA, 0x02)
        val otId get() = u32(blockA, 0x04)
        val exp get() = u32(blockA, 0x08)
        val friendship get() = u8(blockA, 0x0C)
        val ability get() = u8(blockA, 0x0D)
        fun move(i: Int) = u16(blockB, 0x00 + 2 * i)
        fun movePp(i: Int) = u8(blockB, 0x08 + i)
        fun movePpUps(i: Int) = u8(blockB, 0x0C + i)
        val ivWord get() = u32(blockB, 0x10)
        val isEgg get() = (ivWord shr 30) and 1L == 1L
        val hasNickname get() = (ivWord shr 31) and 1L == 1L
        val form get() = (u8(blockB, 0x18) shr 3) and 0x1F
        val nicknameChars: IntArray get() = IntArray(11) { u16(blockC, 2 * it) }

        // PartyPokemon (include/pokemon_types_def.h)
        val status get() = party?.let { u32(it, 0x00) } ?: 0L
        val level get() = party?.let { u8(it, 0x04) } ?: 0
        val hp get() = party?.let { u16(it, 0x06) } ?: 0
        val maxHp get() = party?.let { u16(it, 0x08) } ?: 0
        val atk get() = party?.let { u16(it, 0x0A) } ?: 0
        val def get() = party?.let { u16(it, 0x0C) } ?: 0
        val speed get() = party?.let { u16(it, 0x0E) } ?: 0
        val spAtk get() = party?.let { u16(it, 0x10) } ?: 0
        val spDef get() = party?.let { u16(it, 0x12) } ?: 0
    }

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)) and 0xFFFFFFFFL

    /** XORs `data[off until off+len]` in place with the Gen 4 LCRNG stream. */
    fun crypt(data: ByteArray, off: Int, len: Int, seed: Long) {
        var s = seed and 0xFFFFFFFFL
        var i = 0
        while (i + 1 < len) {
            s = (s * 1103515245L + 24691L) and 0xFFFFFFFFL
            val k = (s ushr 16).toInt() and 0xFFFF
            val v = u16(data, off + i) xor k
            data[off + i] = (v and 0xFF).toByte()
            data[off + i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** Decodes a 0xEC-byte party Pokémon (or a 0x88-byte box Pokémon when `raw.size < 0xEC`). Returns null for empty slots. */
    fun decode(raw: ByteArray): Decoded? {
        if (raw.size < 0x88) return null
        val data = raw.copyOf()
        val pid = u32(data, 0)
        val flags = u16(data, A.BOX_FLAGS.toInt())
        val checksum = u16(data, A.BOX_CHECKSUM.toInt())
        val boxDecrypted = flags and 2 != 0
        val partyDecrypted = flags and 1 != 0
        val blocksOff = A.BOX_BLOCKS.toInt()
        if (!boxDecrypted) crypt(data, blocksOff, A.BOX_BLOCKS_SIZE, checksum.toLong())
        var sum = 0
        for (i in 0 until A.BOX_BLOCKS_SIZE / 2) sum = (sum + u16(data, blocksOff + 2 * i)) and 0xFFFF
        val checksumOk = boxDecrypted || sum == checksum
        if (pid == 0L && checksum == 0 && sum == 0) return null // empty slot
        val order = A.POKEMON_BLOCK_OFFSETS[((pid shr 13) and 31).toInt()]
        fun block(which: Int) = data.copyOfRange(blocksOff + order[which], blocksOff + order[which] + 0x20)
        val party = if (raw.size >= 0xEC) {
            val pOff = A.PARTY_DATA.toInt()
            if (!partyDecrypted) crypt(data, pOff, A.PARTY_DATA_SIZE, pid)
            data.copyOfRange(pOff, pOff + A.PARTY_DATA_SIZE)
        } else null
        return Decoded(pid, block(0), block(1), block(2), block(3), party, checksumOk)
    }

    /** Status condition word (STATUS_* include/constants/battle.h:296). */
    fun statusName(status: Long, hp: Int? = null): String {
        val s = status.toInt()
        return when {
            hp == 0 -> "FAINTED"
            s and 0x7 != 0 -> "SLEEP(${s and 0x7})"
            s and 0x80 != 0 -> "TOXIC"
            s and 0x8 != 0 -> "POISON"
            s and 0x10 != 0 -> "BURN"
            s and 0x20 != 0 -> "FREEZE"
            s and 0x40 != 0 -> "PARALYSIS"
            else -> "OK"
        }
    }

    /** Max PP of a move with PP Ups (pp + pp * 20 * ppUps / 100, ppUps clamped to 3: GetMoveMaxPP in src/move.c:19). */
    fun maxPp(moveId: Int, ppUps: Int): Int {
        val base = HgssData.moveData[moveId]?.pp ?: return 0
        val ups = ppUps.coerceIn(0, 3)
        return base + base * 20 * ups / 100
    }
}
