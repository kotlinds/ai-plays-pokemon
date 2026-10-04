package dev.kotlinds.pokemonclient.libretro

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * How a core stores the cartridge save (the in-game save) on disk.
 *
 * The app always keeps `<rom>.sav` (the raw save memory, readable by every emulator) as the canonical file:
 * before loading, it is converted to the core's format when that one is older or missing; after closing, the
 * core's file is converted back. So switching cores keeps the same game.
 */
enum class SaveFormat {
    /** `<rom>.sav`, raw save memory (melonDS). */
    RAW {
        override fun coreFile(saveDirectory: Path, romBase: String): Path = saveDirectory.resolve("$romBase.sav")
        override fun toCore(raw: ByteArray) = raw
        override fun fromCore(data: ByteArray) = data
    },

    /** `<rom>.dsv`: the raw save memory followed by a 122-byte DeSmuME footer. */
    DESMUME {
        override fun coreFile(saveDirectory: Path, romBase: String): Path = saveDirectory.resolve("$romBase.dsv")
        override fun toCore(raw: ByteArray) = raw + desmumeFooter(raw.size)
        override fun fromCore(data: ByteArray): ByteArray {
            val marker = FOOTER_TEXT.encodeToByteArray()
            val start = indexOf(data, marker)
            return if (start >= 0) data.copyOf(start) else data
        }
    };

    abstract fun coreFile(saveDirectory: Path, romBase: String): Path
    abstract fun toCore(raw: ByteArray): ByteArray
    abstract fun fromCore(data: ByteArray): ByteArray

    /** Before loading the game: writes the core's file from `<rom>.sav` when that one is newer. */
    fun prepare(saveDirectory: Path, romBase: String) {
        val raw = saveDirectory.resolve("$romBase.sav")
        val core = coreFile(saveDirectory, romBase)
        if (core == raw || !Files.exists(raw)) return
        if (!Files.exists(core) || Files.getLastModifiedTime(raw) > Files.getLastModifiedTime(core)) {
            Files.write(core, toCore(Files.readAllBytes(raw)))
            Files.setLastModifiedTime(core, Files.getLastModifiedTime(raw))
        }
    }

    /** After closing the game: copies the core's file back to `<rom>.sav` when it is newer. */
    fun sync(saveDirectory: Path, romBase: String) {
        val raw = saveDirectory.resolve("$romBase.sav")
        val core = coreFile(saveDirectory, romBase)
        if (core == raw || !Files.exists(core)) return
        if (!Files.exists(raw) || Files.getLastModifiedTime(core) > Files.getLastModifiedTime(raw)) {
            Files.write(raw, fromCore(Files.readAllBytes(core)))
            Files.setLastModifiedTime(raw, FileTime.from(Files.getLastModifiedTime(core).toInstant()))
        }
    }

    private companion object {
        const val FOOTER_TEXT = "|<--Snip above here to create a raw sav by excluding this DeSmuME savedata footer:"
        const val FOOTER_END = "|-DESMUME SAVE-|"

        /**
         * DeSmuME's save footer (BackupDevice::persistMemory): the text above, then little-endian u32 fields
         * (actual size, padded size, type, address size, memory size, version), then the end marker. Values are the
         * ones DeSmuME writes for HeartGold's 512 KB flash.
         */
        fun desmumeFooter(size: Int): ByteArray {
            val fields = intArrayOf(0x40001, size, 6, 3, size, 0)
            val numbers = ByteArray(fields.size * 4)
            fields.forEachIndexed { i, v -> for (b in 0 until 4) numbers[i * 4 + b] = (v ushr (8 * b)).toByte() }
            return FOOTER_TEXT.encodeToByteArray() + numbers + FOOTER_END.encodeToByteArray()
        }

        fun indexOf(data: ByteArray, needle: ByteArray): Int {
            outer@ for (i in data.size - needle.size downTo 0) {
                for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
