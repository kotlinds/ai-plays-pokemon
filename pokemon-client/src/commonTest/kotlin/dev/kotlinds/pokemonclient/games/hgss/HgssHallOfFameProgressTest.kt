package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.sameAs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Hall of Fame shows which Pokémon it presents (`RegisterHallOfFameData.currentScene` / `curMonIndex`), so
 * waiting "until something changes" sees the animation go on. Synthetic RAM laid out like the decomp's struct (no
 * League win was captured in game: the offsets come from src/register_hall_of_fame.c).
 */
class HgssHallOfFameProgressTest {

    private class Ram : Memory {
        val words = HashMap<Long, Long>()
        fun put32(addr: Long, value: Long) { words[addr] = value }
        fun put16(addr: Long, value: Int) {
            val base = addr and 3L.inv()
            val shift = ((addr - base) * 8).toInt()
            val old = words[base] ?: 0L
            words[base] = (old and (0xFFFFL shl shift).inv()) or (value.toLong() shl shift)
        }
        override fun read32(addr: Long) = words[addr] ?: 0L
        override fun read16(addr: Long) = ((read32(addr and 3L.inv()) shr (((addr and 3L) * 8).toInt())) and 0xFFFF).toInt()
        override fun read8(addr: Long) = ((read32(addr and 3L.inv()) shr (((addr and 3L) * 8).toInt())) and 0xFF).toInt()
        override fun readBytes(addr: Long, size: Int) = ByteArray(size) { read8(addr + it).toByte() }
    }

    private fun screen(scene: Int, mon: Int, count: Int = 6): Screen {
        val ram = Ram()
        val version = HgssVersion.HEARTGOLD_US
        val fs = 0x02100000L
        val sub0 = 0x02100400L
        val app = 0x02100800L
        val data = 0x02200000L
        ram.put32(version.fieldSystemPtr, fs)
        ram.put32(fs + HgssAddresses.FS_SUB0, sub0)
        ram.put32(sub0 + HgssAddresses.FSS0_SUB_APP, app)
        ram.put32(app + HgssAddresses.OM_DATA, data)
        ram.put32(data + 0x13048L, count.toLong())
        ram.put32(data + 0x1304CL, scene.toLong())
        ram.put16(data + 0x13056L, mon)
        val state = HgssState(frame = 0, mode = GameMode.APP, modeDetail = "hall_of_fame_register")
        return HgssViewerScreens.decode(HgssMemory(ram, version), state)!!
    }

    @Test
    fun eachPokemonPresentedIsAChange() {
        val third = screen(scene = 3, mon = 2) as Screen.Viewer
        assertEquals(listOf("presenting 3/6: ?"), third.details)
        assertFalse(third.sameAs(screen(scene = 3, mon = 3)))
        assertTrue(third.sameAs(screen(scene = 3, mon = 2)))
    }

    @Test
    fun theWholeTeamWaitsForA() {
        val whole = screen(scene = 6, mon = 6) as Screen.Viewer
        assertTrue(whole.details.single().startsWith("the whole team"))
    }
}
