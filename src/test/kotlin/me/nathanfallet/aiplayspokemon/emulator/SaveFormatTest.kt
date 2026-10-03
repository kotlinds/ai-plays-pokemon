package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.emulator.libretro.SaveFormat
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveFormatTest {

    private val raw = ByteArray(512 * 1024) { (it * 31).toByte() }

    @Test
    fun desmumeFooterRoundTrips() {
        val dsv = SaveFormat.DESMUME.toCore(raw)
        assertEquals(raw.size + 122, dsv.size)
        assertTrue(dsv.copyOfRange(dsv.size - 16, dsv.size).decodeToString() == "|-DESMUME SAVE-|")
        assertContentEquals(raw, SaveFormat.DESMUME.fromCore(dsv))
    }

    @Test
    fun footerFieldsMatchWhatDesmumeWritesForA512KbFlash() {
        val dsv = SaveFormat.DESMUME.toCore(raw)
        val numbers = dsv.copyOfRange(raw.size + 82, raw.size + 106)
        val expected = "010004000000080006000000030000000000080000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, numbers)
    }

    @Test
    fun theNewestFileWinsInBothDirections() {
        val dir = Files.createTempDirectory("saves")
        val sav = dir.resolve("game.sav").also { it.writeBytes(raw) }
        SaveFormat.DESMUME.prepare(dir, "game")
        val dsv = dir.resolve("game.dsv")
        assertContentEquals(raw, SaveFormat.DESMUME.fromCore(dsv.readBytes()))

        // The core wrote a newer save: it goes back to game.sav when the game closes.
        val played = raw.copyOf().also { it[0] = 42 }
        dsv.writeBytes(SaveFormat.DESMUME.toCore(played))
        Files.setLastModifiedTime(dsv, FileTime.fromMillis(Files.getLastModifiedTime(sav).toMillis() + 5_000))
        SaveFormat.DESMUME.sync(dir, "game")
        assertContentEquals(played, sav.readBytes())
    }

    @Test
    fun rawFormatIsTheSavFileItself() {
        val dir = Files.createTempDirectory("saves")
        dir.resolve("game.sav").writeBytes(raw)
        SaveFormat.RAW.prepare(dir, "game")
        SaveFormat.RAW.sync(dir, "game")
        assertContentEquals(raw, dir.resolve("game.sav").readBytes())
    }
}
