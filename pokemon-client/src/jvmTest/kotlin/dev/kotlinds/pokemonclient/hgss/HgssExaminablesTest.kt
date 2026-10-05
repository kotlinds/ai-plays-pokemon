package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.ExaminableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invisible objects that answer A ([HgssExaminables]) and the labels of objects drawn from misleading sprites
 * (NOTES.md, Kanto part). ROM tests are skipped without `POKEMON_ROM`.
 */
class HgssExaminablesTest {

    /** A hidden `SPRITE_STOP` object as the reader sees it in RAM (the game spawns invisible objects hidden). */
    private fun stop(id: Int, x: Int, z: Int, script: Int, mapId: Int, flag: Int = 0) = MapObjectInfo(
        id = id, sprite = "STOP", x = x, z = z, dx = 0, dz = 0, height = 0, facing = "north", movement = 0, type = 0,
        scriptId = script, hidden = true, kind = "npc", label = "invisible object", mapId = mapId, eventFlag = flag,
    )

    /**
     * The Cerulean Gym (map 427) before the Machine Part is found: obj_T04GYM0101_stop / stop_2 (local ids 8 and 9) at
     * (3,10) and (4,10), script `_EV_scr_seq_T04GYM0101_005 + 1` = 6, flag FLAG_HIDE_CERULEAN_GYM_MACHINE_PART. The
     * current save has it already (fixture below): this "present" case comes from the ROM's own zone events.
     */
    @Test
    fun theMachinePartSpotsComeFromTheRomAndGoWithTheirFlag() {
        val world = HgssWorldRom.require()
        HgssData.useGameData(HgssWorldRom.requireData())
        val events = assertNotNull(world.events(CERULEAN_GYM)).objects.filter { HgssData.spriteName(it.sprite) == "STOP" }
        assertEquals(listOf(3 to 10, 4 to 10), events.map { it.x to it.z })
        val flag = events.map { it.eventFlag }.distinct().single()
        val objects = events.map { stop(it.id, it.x, it.z, it.script, CERULEAN_GYM, it.eventFlag) }
        val present = HgssExaminables.of(objects, CERULEAN_GYM, world) { false }
        assertEquals(listOf("examine:8", "examine:9"), present.map { it.id })
        assertTrue(present.all { it.kind == ExaminableKind.ITEM && it.item?.id?.value == ITEM_MACHINE_PART && !it.cue }, present.toString())
        assertEquals("Machine Part", present.first().label)
        // Once FLAG_HIDE_CERULEAN_GYM_MACHINE_PART is set they are gone (the game doesn't spawn them either).
        assertTrue(HgssExaminables.of(objects, CERULEAN_GYM, world) { it == flag }.isEmpty())
    }

    /** STOP objects with no script or an empty one (`End`) are walls only: the Mahogany souvenir shop's, Vermilion Gym's. */
    @Test
    fun invisibleWallsAreNotExaminable() {
        val world = HgssWorldRom.require()
        val shop = assertNotNull(world.events(MAHOGANY_SOUVENIR_SHOP)).objects.filter { HgssData.spriteName(it.sprite) == "STOP" }
        assertEquals(3, shop.size)
        assertTrue(HgssExaminables.of(shop.map { stop(it.id, it.x, it.z, it.script, MAHOGANY_SOUVENIR_SHOP) }, MAHOGANY_SOUVENIR_SHOP, world) { false }.isEmpty())
        val vermilion = assertNotNull(world.events(VERMILION_GYM)).objects.filter { HgssData.spriteName(it.sprite) == "STOP" }
        assertEquals(6, vermilion.size)
        assertTrue(HgssExaminables.of(vermilion.map { stop(it.id, it.x, it.z, it.script, VERMILION_GYM) }, VERMILION_GYM, world) { false }.isEmpty())
    }

    /**
     * Live saves: `ob_cerulean_gym_part_taken` (Kanto save, Cerulean Gym at 4,9 facing the pool's edge, Machine Part
     * already returned: its objects aren't spawned) and `ob_mahogany_shop_stops` (souvenir shop: three hidden STOP
     * walls in RAM, scripts 0 and an empty one).
     */
    @Test
    fun liveSavesListNoExaminableWhenThereIsNone() {
        HgssData.useWorld(HgssWorldRom.require())
        val gym = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("ob_cerulean_gym_part_taken")).field)
        assertEquals(CERULEAN_GYM to (4 to 9), gym.mapId to (gym.x to gym.y))
        assertTrue(gym.examinables.isEmpty(), gym.examinables.toString())
        val raw = assertNotNull(HgssReader(HgssFixtures.load("ob_mahogany_shop_stops")).read())
        assertEquals(3, raw.surroundings!!.objects.count { it.sprite == "STOP" && it.hidden })
        val shop = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("ob_mahogany_shop_stops")).field)
        assertEquals(MAHOGANY_SOUVENIR_SHOP, shop.mapId)
        assertTrue(shop.examinables.isEmpty())
        // Invisible objects never show among the people.
        assertTrue(shop.objects.none { it.label == "invisible object" })
    }

    @Test
    fun labelsComeFromWhoReallyWearsTheSprite() {
        // Each Kanto gym's leader object (Misty on Route 25 wears her gym sprite).
        assertEquals("Lt. Surge (gym leader)", HgssLabels.person("GSLEADER9"))
        assertEquals("Sabrina (gym leader)", HgssLabels.person("GSLEADER10"))
        assertEquals("Misty (gym leader)", HgssLabels.person("GSLEADER11"))
        assertEquals("Brock (gym leader)", HgssLabels.person("GSLEADER14"))
        // Lab coats: the Power Plant's scientists aren't Elm's aide.
        assertEquals("scientist", HgssLabels.person("GSASSISTANTM"))
        assertEquals("invisible object", HgssLabels.person("STOP"))
    }

    /** Route 11 (obj_R11_kabigon 1354,304 + three GSBABYBOY1 placeholders, movement 53, same script). */
    @Test
    fun theTilesOfSnorlaxsBigSpriteAreLabelledSnorlax() {
        fun obj(id: Int, sprite: String, x: Int, z: Int, movement: Int, script: Int = 1) = MapObjectInfo(
            id = id, sprite = sprite, x = x, z = z, dx = 0, dz = 0, height = 0, facing = "south", movement = movement, type = 0,
            scriptId = script, hidden = false, kind = "npc", label = HgssLabels.person(sprite), mapId = 19,
        )
        val objects = listOf(
            obj(4, "KABIGON", 1354, 304, 0), obj(7, "GSBABYBOY1", 1355, 303, 53), obj(8, "GSBABYBOY1", 1354, 303, 53),
            obj(9, "GSBABYBOY1", 1355, 304, 53), obj(10, "GSBABYBOY1", 1340, 300, 0, script = 5),
        )
        assertEquals(listOf("Snorlax", "Snorlax", "Snorlax", "Snorlax", "little boy"), HgssLabels.bigSpriteParts(objects).map { it.label })
    }

    private companion object {
        const val CERULEAN_GYM = 427
        const val MAHOGANY_SOUVENIR_SHOP = 116
        const val VERMILION_GYM = 365
        const val ITEM_MACHINE_PART = 481
    }
}
