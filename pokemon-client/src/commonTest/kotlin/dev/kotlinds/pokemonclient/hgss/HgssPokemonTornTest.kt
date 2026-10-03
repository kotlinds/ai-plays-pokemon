package dev.kotlinds.pokemonclient.hgss

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** A Pokémon read while the game encrypts or decrypts it word by word (see [HgssPokemon.decode]). */
class HgssPokemonTornTest {

    private val blocks = ByteArray(0x80) { (it * 37 + 11).toByte() }
    private val checksum = (0 until 0x40).sumOf { HgssPokemon.u16(blocks, 2 * it) } and 0xFFFF
    private val encryptedBlocks = blocks.copyOf().also { HgssPokemon.crypt(it, 0, it.size, checksum.toLong()) }

    /** A box Pokémon (0x88 bytes): PID, flags 0 (encrypted), checksum, then [body] as the blocks. */
    private fun mon(body: ByteArray) = ByteArray(0x88).also {
        it[0] = 0x12; it[1] = 0x34
        it[6] = (checksum and 0xFF).toByte(); it[7] = (checksum shr 8).toByte()
        body.copyInto(it, 8)
    }

    /** The decoded blocks, in storage order (block order A..D is shuffled by the PID). */
    private fun decoded(raw: ByteArray): Pair<Boolean, ByteArray> {
        val d = HgssPokemon.decode(raw)!!
        val order = HgssAddresses.POKEMON_BLOCK_OFFSETS[((0x3412L shr 13) and 31).toInt()]
        val out = ByteArray(0x80)
        listOf(d.blockA, d.blockB, d.blockC, d.blockD).forEachIndexed { i, b -> b.copyInto(out, order[i]) }
        return d.checksumOk to out
    }

    @Test
    fun aCleanPokemonDecodes() {
        val (ok, out) = decoded(mon(encryptedBlocks))
        assertTrue(ok)
        assertContentEquals(blocks, out)
    }

    @Test
    fun beingEncryptedIsRecovered() {
        for (split in listOf(1, 20, 38, 63)) {
            val torn = blocks.copyOf().also { encryptedBlocks.copyInto(it, 0, 0, split * 2) }
            val (ok, out) = decoded(mon(torn))
            assertTrue(ok, "split $split")
            assertContentEquals(blocks, out, "split $split")
        }
    }

    @Test
    fun beingDecryptedIsRecovered() {
        for (split in listOf(1, 32, 63)) {
            val torn = encryptedBlocks.copyOf().also { blocks.copyInto(it, 0, 0, split * 2) }
            val (ok, out) = decoded(mon(torn))
            assertTrue(ok, "split $split")
            assertContentEquals(blocks, out, "split $split")
        }
    }
}
