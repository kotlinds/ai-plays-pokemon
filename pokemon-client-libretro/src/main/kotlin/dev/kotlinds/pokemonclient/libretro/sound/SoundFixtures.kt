package dev.kotlinds.pokemonclient.libretro.sound

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Small test fixtures of real save states for music during pauses: the state with every byte zeroed except what the
 * splicer and the guards read (format headers and section / field headers, the whole ARM7 WRAM, the sound chip, the
 * melonDS DMA sections, and the main RAM around the ARM9's sound command statics), gzipped (a 12 MB DeSmuME state
 * becomes a few tens of KB). Not loadable by a core: for unit tests only.
 */
object SoundFixtures {

    /** Writes the reduced, gzipped [state] to [file]. */
    fun write(file: Path, splicer: SavestateSoundSplicer, layout: SoundDriverLayout, state: ByteArray) {
        Files.newOutputStream(file).use { GZIPOutputStream(it).use { gz -> gz.write(reduce(splicer, layout, state)) } }
    }

    /** Reads a fixture written by [write]. */
    fun read(file: Path): ByteArray = Files.newInputStream(file).use { GZIPInputStream(it).readAllBytes() }

    /** [state] with everything zeroed but what music during pauses reads. */
    fun reduce(splicer: SavestateSoundSplicer, layout: SoundDriverLayout, state: ByteArray): ByteArray {
        val located = splicer.locate(state)
        val keep = BooleanArray(state.size)
        fun keep(offset: Int, size: Int) = keep.fill(true, offset.coerceIn(0, state.size), (offset + size).coerceIn(0, state.size))
        when (splicer) {
            SavestateSoundSplicer.DESMUME -> {
                keep(0, 32)
                var offset = 32
                while (offset + 4 <= state.size) {
                    val id = SoundDriverLayout.le32(state, offset)
                    keep(offset, if (id == -1) 4 else 8)
                    if (id == -1) break
                    val size = SoundDriverLayout.le32(state, offset + 4)
                    if (id == 4 || id == 60) {
                        var field = offset + 8
                        while (field + 12 <= offset + 8 + size) {
                            keep(field, 12)
                            field += 12 + SoundDriverLayout.le32(state, field + 4) * SoundDriverLayout.le32(state, field + 8)
                        }
                    }
                    offset += 8 + size
                }
            }
            SavestateSoundSplicer.MELONDS -> {
                keep(0, 16)
                var offset = 16
                val total = SoundDriverLayout.le32(state, 8)
                while (offset + 16 <= total) {
                    val length = SoundDriverLayout.le32(state, offset + 4)
                    val name = state.copyOfRange(offset, offset + 4).decodeToString()
                    keep(offset, if (name.startsWith("DMA")) length else 16)
                    offset += length
                }
            }
        }
        keep(located.arm7Wram.offset, located.arm7Wram.size)
        located.soundChip.forEach { keep(it.offset, it.size) }
        val shared = located.arm7(layout.sharedWorkPointer)
        if (located.inMainRam(shared)) keep(located.mainRam.offset + (shared - SoundDriverLayout.MAIN_RAM) - 0x100, 0x1C00)
        return ByteArray(state.size) { if (keep[it]) state[it] else 0 }
    }
}
