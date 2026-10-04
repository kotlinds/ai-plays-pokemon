package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

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

    private fun blockSum(data: ByteArray, off: Int): Int {
        var sum = 0
        for (i in 0 until A.BOX_BLOCKS_SIZE / 2) sum = (sum + u16(data, off + 2 * i)) and 0xFFFF
        return sum
    }

    /**
     * The plain box blocks (0x80 bytes) that [raw]'s blocks can be, most likely first, keeping only those whose sum
     * gives the checksum.
     *
     * The game decrypts a Pokémon to read or write it and encrypts it again right after, one word at a time, and a
     * frame can end anywhere in that loop (a field task reads the lead Pokémon every frame):
     * - `GetMonData` / `SetMonData` (src/pokemon.c:410, 886) decrypt the party data then the blocks, work, and encrypt
     *   both again **without touching the flags**: with flags "encrypted" the blocks can be encrypted, plain, or torn
     *   either way;
     * - `AcquireMonLock` sets the flags first then decrypts; `ReleaseMonLock` clears them first then encrypts.
     * A torn loop leaves the words before some split in one state and the rest in the other: every split is tried,
     * both ways (a 1 in 65536 chance per split of matching by accident, which the plausibility checks back up).
     * Read only: RAM is never written.
     */
    private fun boxCandidates(raw: ByteArray, off: Int, checksum: Int, flaggedPlain: Boolean): List<ByteArray> {
        val words = A.BOX_BLOCKS_SIZE / 2
        val asIs = raw.copyOfRange(off, off + A.BOX_BLOCKS_SIZE)
        val decrypted = asIs.copyOf().also { crypt(it, 0, it.size, checksum.toLong()) }
        val whole = (if (flaggedPlain) listOf(asIs, decrypted) else listOf(decrypted, asIs)).filter { blockSum(it, 0) == checksum }
        if (whole.isNotEmpty()) return whole
        return (1 until words).asSequence().flatMap { split ->
            sequenceOf(
                // Being encrypted: words before the split are already encrypted, the rest still plain.
                decrypted.copyOf().also { asIs.copyInto(it, split * 2, split * 2, asIs.size) },
                // Being decrypted: words before the split are already plain, the rest still encrypted.
                decrypted.copyOf().also { asIs.copyInto(it, 0, 0, split * 2) },
            )
        }.filter { blockSum(it, 0) == checksum }.take(MAX_BOX_CANDIDATES).toList()
    }

    /**
     * The plain party data (0x64 bytes) that [raw]'s can be, most likely first. It has no checksum: the caller's
     * plausibility check (level against experience, stats against the base stats...) picks the right one. Same
     * torn loops as [boxCandidates]; only the first [PARTY_CHECKED_WORDS] words (status, level, HP, stats) are shown,
     * so later splits give the same values as the whole states.
     */
    private fun partyCandidates(raw: ByteArray, off: Int, pid: Long, flaggedPlain: Boolean): Sequence<ByteArray> {
        val asIs = raw.copyOfRange(off, off + A.PARTY_DATA_SIZE)
        val decrypted = asIs.copyOf().also { crypt(it, 0, it.size, pid) }
        val whole = if (flaggedPlain) sequenceOf(asIs, decrypted) else sequenceOf(decrypted, asIs)
        val torn = (1 until PARTY_CHECKED_WORDS).asSequence().flatMap { split ->
            sequenceOf(
                decrypted.copyOf().also { asIs.copyInto(it, split * 2, split * 2, asIs.size) },
                decrypted.copyOf().also { asIs.copyInto(it, 0, 0, split * 2) },
            )
        }
        return whole + torn
    }

    /**
     * Decodes a 0xEC-byte party Pokémon (or a 0x88-byte box Pokémon when `raw.size < 0xEC`). Returns null for empty
     * slots.
     *
     * The structure may be caught while the game encrypts or decrypts it (see [boxCandidates]): every way it can be
     * is tried, and the first one [accept] takes is returned (the plausibility check of the caller, see
     * [HgssMonCheck]). When none is accepted, the most likely reading is returned as is (the caller then rejects it);
     * its [Decoded.checksumOk] is false when no box reading matched the checksum.
     */
    fun decode(raw: ByteArray, accept: (Decoded) -> Boolean = { it.checksumOk }): Decoded? {
        if (raw.size < 0x88) return null
        val pid = u32(raw, 0)
        val flags = u16(raw, A.BOX_FLAGS.toInt())
        val checksum = u16(raw, A.BOX_CHECKSUM.toInt())
        val blocksOff = A.BOX_BLOCKS.toInt()
        val boxes = boxCandidates(raw, blocksOff, checksum, flaggedPlain = flags and 2 != 0)
        if (pid == 0L && checksum == 0 && boxes.isNotEmpty()) return null // empty slot (zeroed then encrypted)
        val order = A.POKEMON_BLOCK_OFFSETS[((pid shr 13) and 31).toInt()]
        fun decoded(box: ByteArray, party: ByteArray?, checksumOk: Boolean): Decoded {
            fun block(which: Int) = box.copyOfRange(order[which], order[which] + 0x20)
            return Decoded(pid, block(0), block(1), block(2), block(3), party, checksumOk)
        }
        val parties = if (raw.size >= 0xEC) partyCandidates(raw, A.PARTY_DATA.toInt(), pid, flaggedPlain = flags and 1 != 0) else sequenceOf(null)
        for (box in boxes) for (party in parties) {
            val mon = decoded(box, party, checksumOk = true)
            if (accept(mon)) return mon
        }
        // Nothing plausible: the most likely reading, for the caller's diagnostics.
        val box = boxes.firstOrNull()
            ?: raw.copyOfRange(blocksOff, blocksOff + A.BOX_BLOCKS_SIZE).also { if (flags and 2 == 0) crypt(it, 0, it.size, checksum.toLong()) }
        return decoded(box, parties.first(), checksumOk = boxes.isNotEmpty())
    }

    /** Box readings matching the checksum tried at most (normally one). */
    private const val MAX_BOX_CANDIDATES = 4

    /** Words of the party data that are shown and checked: status (2), level and capsule, HP, max HP, 5 stats. */
    private const val PARTY_CHECKED_WORDS = 10

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
