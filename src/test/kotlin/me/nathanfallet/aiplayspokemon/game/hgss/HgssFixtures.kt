package me.nathanfallet.aiplayspokemon.game.hgss

import me.nathanfallet.aiplayspokemon.game.Memory
import me.nathanfallet.aiplayspokemon.game.RamMemory
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Real HeartGold (USA) RAM snapshots, stored sparsely: only the bytes the reader touches
 * (format: "SPRM" then records of [u32 address][u32 length][bytes], gzipped).
 */
object HgssFixtures {
    fun load(name: String): Memory {
        val ram = ByteArray(4 shl 20)
        val stream = javaClass.getResourceAsStream("/hgss/$name.ram.sparse.gz") ?: error("missing fixture $name")
        DataInputStream(GZIPInputStream(stream)).use { input ->
            val magic = ByteArray(4).also { input.readFully(it) }
            check(String(magic) == "SPRM")
            while (input.available() > 0) {
                val addr = try {
                    input.readInt()
                } catch (_: java.io.EOFException) {
                    break
                }
                val len = input.readInt()
                input.readFully(ram, addr - 0x02000000, len)
            }
        }
        return RamMemory(ram)
    }
}
