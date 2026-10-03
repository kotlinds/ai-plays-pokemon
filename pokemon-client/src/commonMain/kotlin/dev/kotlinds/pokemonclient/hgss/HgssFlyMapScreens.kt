package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssFlyMapAddresses as F

/**
 * The Fly map (party menu → a Pokémon → FLY): the Pokégear map application launched in fly mode
 * (`FlyMap_Init`, src/application/pokegear/map/fly_map.c, overlay `pokegear_app` = 101), drawn on the bottom screen.
 * Decoded into [Screen.FlyMap] while it waits for a destination, and into a [Screen.YesNo] while its
 * "FLY / CANCEL" confirmation is up. Checked live on HeartGold (US) with the dev bench.
 *
 * ## Entries
 * One entry per fly point of `gMapFlypointParams` (overlay_101_021F79B4.c) the cursor can reach with this map
 * unlock level, in the game's table order, then `option:cancel` (the "Close" button):
 * - id `fly:<map id>`: the game's own fly destination, `MapFlypointParam.mapIDforWarp` (the zone id the player
 *   lands in: the town's id for towns, `MAP_ROUTE_26` for Victory Road, `MAP_POKEATHLON_DOME` for the National Park
 *   point...). It is exactly what the game stores in `PokegearArgs.selectedFlyDest` when one is chosen;
 * - selectable when the game would accept A on it: its fly point flag (`FLAG_SYS_FLYPOINT_PALLET + flypoint`) is
 *   set (the town was visited) and it is in the player's region, or the player is at Indigo Plateau
 *   (`PokegearMap_GetFlyDestinationAtCoord`, overlay_101_021E9270.c:739). Unvisited points are listed, not selectable;
 * - label: the location name (display only, never matched).
 *
 * ## Cursor and why the D-pad is not the way to pick a destination
 * The map cursor is free: it sits on a map tile (`playerX`, `playerY - 2`) and each D-pad press moves it ONE tile
 * (scrolling the map near the edges, overlay_101_021EB568.c:76), whether or not a town is there. A press of A flies
 * only when that tile is inside a visited fly point's rectangle. So:
 * - [Cursor.At] the destination whose rectangle holds the cursor tile;
 * - [Cursor.Hidden] when the cursor is between destinations. It does NOT mean "the first press reveals the cursor"
 *   here: the cursor is drawn, it is simply on no destination. The generic navigator must not be used on this screen.
 *
 * The [Topology] is therefore exact but sparse: from an entry (the cursor tile if it is the current one, else the
 * tile a touch would put the cursor on), a direction gives the entry reached by that ONE press, and null when the
 * press stays in the same destination or leaves for a tile between destinations. Most destinations are several
 * presses apart, with no entry in between.
 *
 * ## Touch: how to choose a destination
 * Every destination visible on screen has [Entry.touch], the centre of one of its tiles. Touching a visited
 * destination moves the cursor there AND opens the confirmation at once (`FlyMap_HandleTouchInput_NotDragging`,
 * overlay_101_021EB568.c:276); touching an unvisited one only moves the cursor. A destination scrolled off screen
 * has no touch point: move the cursor towards it with the D-pad (the map scrolls with it) until it has one.
 * `option:cancel` is the "Close" button (touch only; B does the same): back to the party menu.
 *
 * ## After A / the touch
 * [Screen.YesNo] (`option:yes` = FLY, `option:no` = CANCEL; UP / DOWN wrap; B = CANCEL at once), a
 * `TouchscreenListMenu` (`FlyMap_HandleContextMenu`, overlay_101_021EDCE0.c:311). FLY fades out and the field plays
 * the Fly animation then warps (`Task_UseFlyInField`, src/start_menu.c:1366); CANCEL goes back to the map.
 */
internal object HgssFlyMapScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (mem.version != HgssVersion.HEARTGOLD_US) return null
        if (state.mode != GameMode.APP || state.modeDetail != APP_TOWN_MAP) return null
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val app = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return null
        if (mem.fn(app + A.OM_INIT) != F.FN_FLY_MAP_INIT) return null
        val map = mem.ptr(app + A.OM_DATA) ?: return null
        // The same app shows the Pokémon Center wall map (type TOWN_MAP): only the fly mode is a choice.
        if (mem.u8(map + F.MAP_TYPE) != F.TYPE_FLY) return null
        if (mem.s32(app + A.OM_EXEC_STATE) != OM_EXEC_MAIN) return Screen.Animation(AnimationKind.TRANSITION)
        return when (mem.s32(app + A.OM_PROC_STATE)) {
            F.STATE_HANDLE_INPUT -> flyMap(mem, map)
            F.STATE_FLY_CONTEXT_MENU -> confirmation(mem, map) ?: Screen.Animation(AnimationKind.TRANSITION)
            else -> Screen.Animation(AnimationKind.TRANSITION)
        }
    }

    /** One fly point of `gMapFlypointParams` (`MapFlypointParam`): map rectangle in tiles and its flag. */
    data class Flypoint(val nameMap: Int, val warpMap: Int, val flag: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
        val id get() = "fly:$warpMap"
        fun contains(tileX: Int, tileY: Int) = tileX in x until x + width && tileY in y until y + height
    }

    /** What the map shows: scroll, cursor, region and the fly flags, read from `PokegearMapAppData`. */
    private class MapView(mem: HgssMemory, map: Long) {
        val zoom = (mem.u8(map + F.MAP_FLAGS_138) and 1) + 1
        val top = mem.u16(map + F.MAP_CURSOR_TOP)
        val left = mem.u16(map + F.MAP_CURSOR_LEFT)
        val centerX = mem.u8(map + F.MAP_CENTER_X)
        val centerY = mem.u8(map + F.MAP_CENTER_Y)
        val maxX = mem.u16(map + F.MAP_MAX_X_SCROLL)
        val maxY = mem.u16(map + F.MAP_MAX_Y_SCROLL)
        val minX = mem.u16(map + F.MAP_MIN_X_SCROLL)
        val minY = mem.u16(map + F.MAP_MIN_Y_SCROLL)
        val region = mem.u8(map + F.MAP_CUR_REGION)

        /** The cursor tile, in fly point coordinates (`playerX`, `playerY - 2`). */
        val cursorX = mem.s16(map + F.MAP_PLAYER_X)
        val cursorY = mem.s16(map + F.MAP_PLAYER_Y) - 2
        private val flags = mem.ptr(map + F.MAP_POKEGEAR)?.let { mem.ptr(it + F.GEAR_SAVE_VARS_FLAGS) }
        private val flagBytes = flags?.let { f -> IntArray(F.FLYPOINT_FLAG_BYTES) { mem.u8(f + A.FLAGS_OFFSET + F.FLAG_FLYPOINT_FIRST / 8 + it) } }

        fun visited(point: Flypoint): Boolean {
            val bit = F.FLAG_FLYPOINT_FIRST % 8 + point.flag
            return flagBytes != null && flagBytes[bit / 8] shr (bit % 8) and 1 == 1
        }

        /** Tiles the cursor can stand on (`ov101_021EB654`), in fly point coordinates. */
        fun reachable(x: Int, y: Int) = x in minX + 1 until maxX && y + 2 in minY + 1..maxY

        /** The game's region test before flying (`ov101_021EA804`). */
        fun inRegion(point: Flypoint) =
            point.warpMap in F.ALWAYS_FLYABLE || region == F.REGION_INDIGO || regionOf(point.x, point.y) == region

        /**
         * Where to touch to put the cursor on tile ([x], [y]) (the inverse of `ov101_021EC980`), or null when the tile
         * is outside the touchable map area (`ov101_021F7EA4[1]` and the clamp of `ov101_021EC980`).
         */
        fun touchOf(x: Int, y: Int): TouchPoint? {
            val column = x - left
            val row = y + 2 - top
            if (zoom != 1 || column !in 1..F.TOUCH_MAX_COLUMN || row !in 1..F.TOUCH_MAX_ROW) return null
            return TouchPoint(centerX + 8 * column + 4, centerY + 8 * row + 4)
        }
    }

    private fun flyMap(mem: HgssMemory, map: Long): Screen {
        // Moving cursor / scrolling map / zoom animation: input is ignored meanwhile (FlyMap_HandleKeyInput).
        if (mem.u8(map + F.MAP_FLAGS_139) and F.MOVING_MASK != 0) return Screen.Animation(AnimationKind.TRANSITION)
        val view = MapView(mem, map)
        val points = F.FLYPOINTS.filter { p ->
            (p.x until p.x + p.width).any { x -> (p.y until p.y + p.height).any { y -> view.reachable(x, y) } }
        }
        // The tile a touch would put the cursor on: the rectangle's tile closest to its centre that is on screen.
        val touchTiles = points.map { p ->
            val tiles = (p.y until p.y + p.height).flatMap { y -> (p.x until p.x + p.width).map { x -> x to y } }
            tiles.sortedBy { (x, y) -> (2 * x - (2 * p.x + p.width - 1)).let { it * it } + (2 * y - (2 * p.y + p.height - 1)).let { it * it } }
                .firstOrNull { (x, y) -> view.touchOf(x, y) != null }
        }
        val entries = points.mapIndexed { i, p ->
            Entry(
                p.id, HgssData.mapLocation(p.nameMap) ?: HgssData.mapName(p.nameMap),
                selectable = view.visited(p) && view.inRegion(p),
                touch = touchTiles[i]?.let { (x, y) -> view.touchOf(x, y) },
            )
        } + Entry(ID_CANCEL, "Close", touch = F.CLOSE_BUTTON)
        val current = points.indexOfFirst { it.contains(view.cursorX, view.cursorY) }
        val cursor = if (current >= 0) Cursor.At(current) else Cursor.Hidden
        fun tileOf(index: Int): Pair<Int, Int>? = if (index == current) view.cursorX to view.cursorY else touchTiles.getOrNull(index)
        val topology = Topology { from, button ->
            val (x, y) = tileOf(from) ?: return@Topology null
            val (nx, ny) = when (button) {
                Button.UP -> x to y - 1
                Button.DOWN -> x to y + 1
                Button.LEFT -> x - 1 to y
                Button.RIGHT -> x + 1 to y
                else -> return@Topology null
            }
            if (!view.reachable(nx, ny)) return@Topology null
            points.indexOfFirst { it.contains(nx, ny) }.takeIf { it >= 0 && it != from }
        }
        return Screen.FlyMap(entries, cursor, topology, CancelBehavior.CLOSES)
    }

    /**
     * The FLY / CANCEL `TouchscreenListMenu` (`PokegearMap_SpawnFlyContextMenu`, overlay_101_021E9270.c:1083): two
     * items of value 0 (fly) and 1 (cancel), wrapping, cursor `TouchscreenListMenu.cursorPos`. The question is the
     * "Fly to …?" line printed under the map (`flavorTextString`).
     */
    private fun confirmation(mem: HgssMemory, map: Long): Screen? {
        val menu = mem.ptr(map + F.MAP_LIST_MENU) ?: return null
        if (mem.u8(menu + HgssTextAddresses.TSM_COUNT) != 2) return null
        if (mem.u8(menu + HgssTextAddresses.TSM_ANIM_ACTIVE) != 0) return Screen.Animation(AnimationKind.TRANSITION)
        val items = mem.ptr(menu + HgssTextAddresses.TSM_ITEMS) ?: return null
        val hitboxes = mem.ptr(menu + HgssTextAddresses.TSM_HITBOXES)
        val entries = listOf("option:yes", "option:no").mapIndexed { i, id ->
            val label = mem.gameString(mem.ptr(items + A.LIST_MENU_ITEM_SIZE * i))?.replace('\n', ' ') ?: "?"
            val touch = hitboxes?.let { h ->
                val rect = h + 4L * i
                TouchPoint((mem.u8(rect + 2) + mem.u8(rect + 3)) / 2, (mem.u8(rect) + mem.u8(rect + 1)) / 2)
            }
            Entry(id, label, touch = touch)
        }
        val question = mem.gameString(mem.ptr(map + F.MAP_FLAVOR_TEXT))?.replace('\n', ' ')
        val cursor = Cursor.At(mem.u8(menu + HgssTextAddresses.TSM_CURSOR).coerceIn(0, 1))
        return Screen.YesNo(question, entries, cursor, Topology.vertical(2, wrap = true), CancelBehavior.CONFIRMS_LAST)
    }

    /** `Pokegear_RegionFromCoords` (overlay_100_021E5900.c:167): 0 Kanto, 1 Indigo, 2 Johto. */
    fun regionOf(x: Int, y: Int): Int = when {
        x <= 21 -> F.REGION_JOHTO
        x == 25 && y == 8 -> F.REGION_JOHTO
        x == 28 && (y == 6 || y in 9..12) -> F.REGION_INDIGO
        else -> F.REGION_KANTO
    }

    private const val APP_TOWN_MAP = "town_map"
    private const val ID_CANCEL = "option:cancel"
    private const val OM_EXEC_MAIN = 2
}

/** Addresses and constants of the Fly map (HeartGold US, include/application/pokegear/map/pokegear_map_internal.h). */
internal object HgssFlyMapAddresses {
    /** `FlyMap_Init` (fly_map.o, overlay 101). */
    const val FN_FLY_MAP_INIT = 0x021ED7F8L

    /** `PokegearMapMainState` (the app's OverlayManager proc state). */
    const val STATE_HANDLE_INPUT = 1
    const val STATE_FLY_CONTEXT_MENU = 12

    // --- PokegearMapAppData (size 0x9F4) ---
    const val MAP_TYPE = 0x00DL           // u8 PokegearMapType: 0 gear, 1 fly, 2 town map
    const val TYPE_FLY = 1
    const val MAP_CUR_REGION = 0x00EL     // u8 PokegearRegion of the player
    const val MAP_POKEGEAR = 0x010L       // PokegearAppData *
    const val MAP_FLAVOR_TEXT = 0x090L    // String *flavorTextString ("Fly to …?" while the menu is up)
    const val MAP_LIST_MENU = 0x0C4L      // TouchscreenListMenu *listMenu
    const val MAP_CURSOR_TOP = 0x0F0L     // cursorSpriteState.top (u16): first map row shown
    const val MAP_CURSOR_LEFT = 0x0F4L    // cursorSpriteState.left (u16): first map column shown
    const val MAP_MIN_X_SCROLL = 0x100L
    const val MAP_MAX_X_SCROLL = 0x102L
    const val MAP_MIN_Y_SCROLL = 0x104L
    const val MAP_MAX_Y_SCROLL = 0x106L
    const val MAP_PLAYER_X = 0x110L       // s16 cursor tile x
    const val MAP_PLAYER_Y = 0x112L       // s16 cursor tile y (+2 against the fly point table)
    const val MAP_CENTER_Y = 0x131L
    const val MAP_CENTER_X = 0x132L
    const val MAP_FLAGS_138 = 0x138L      // bit0 zoomed
    const val MAP_FLAGS_139 = 0x139L      // bit0 cursor moving, bit1 zooming, bit2 key move pending, bit3 dragging the map
    const val MOVING_MASK = 0x07

    /** `PokegearAppData.saveVarsFlags`. */
    const val GEAR_SAVE_VARS_FLAGS = 0x02CL

    /** `FLAG_SYS_FLYPOINT_PALLET`: fly point n's flag is this + n (`Save_VarsFlags_FlypointFlagAction`). */
    const val FLAG_FLYPOINT_FIRST = 0x9B0
    const val FLYPOINT_FLAG_BYTES = 6

    const val REGION_KANTO = 0
    const val REGION_INDIGO = 1
    const val REGION_JOHTO = 2

    /** `MAP_INDIGO_PLATEAU`, `MAP_ROUTE_26`: flyable from any region (`ov101_021EA804`). */
    val ALWAYS_FLYABLE = setOf(58, 30)

    /** Touchable map tiles on screen (clamp of `ov101_021EC980`, unzoomed). */
    const val TOUCH_MAX_COLUMN = 22
    const val TOUCH_MAX_ROW = 16

    /** Centre of the "Close" button (`sTouchscreenHitbox_CloseButton`, y 152-184, x 194-254). */
    val CLOSE_BUTTON = TouchPoint(224, 168)

    /**
     * `gMapFlypointParams` (overlay_101_021F79B4.c, 27 entries) with the map ids of include/constants/maps.h:
     * (mapIDforName, mapIDforWarp, flypoint flag, x, y, width, height).
     */
    val FLYPOINTS = listOf(
        HgssFlyMapScreens.Flypoint(49, 49, 0, 32, 11, 1, 1),    // Pallet Town
        HgssFlyMapScreens.Flypoint(50, 50, 1, 31, 7, 2, 2),     // Viridian City
        HgssFlyMapScreens.Flypoint(51, 51, 2, 32, 2, 2, 2),     // Pewter City
        HgssFlyMapScreens.Flypoint(52, 52, 3, 40, 3, 2, 2),     // Cerulean City
        HgssFlyMapScreens.Flypoint(53, 53, 4, 44, 7, 1, 1),     // Lavender Town
        HgssFlyMapScreens.Flypoint(54, 54, 5, 40, 9, 2, 2),     // Vermilion City
        HgssFlyMapScreens.Flypoint(55, 55, 6, 37, 7, 2, 2),     // Celadon City
        HgssFlyMapScreens.Flypoint(56, 56, 7, 37, 12, 2, 2),    // Fuchsia City
        HgssFlyMapScreens.Flypoint(57, 57, 8, 32, 15, 1, 1),    // Cinnabar Island
        HgssFlyMapScreens.Flypoint(58, 58, 9, 28, 6, 1, 1),     // Indigo Plateau
        HgssFlyMapScreens.Flypoint(59, 59, 10, 40, 6, 2, 2),    // Saffron City
        HgssFlyMapScreens.Flypoint(60, 60, 11, 21, 12, 1, 1),   // New Bark Town
        HgssFlyMapScreens.Flypoint(67, 67, 12, 16, 12, 2, 1),   // Cherrygrove City
        HgssFlyMapScreens.Flypoint(73, 73, 13, 14, 7, 2, 2),    // Violet City
        HgssFlyMapScreens.Flypoint(74, 74, 14, 12, 14, 2, 1),   // Azalea Town
        HgssFlyMapScreens.Flypoint(75, 75, 15, 5, 10, 1, 2),    // Cianwood City
        HgssFlyMapScreens.Flypoint(76, 76, 16, 9, 10, 3, 2),    // Goldenrod City
        HgssFlyMapScreens.Flypoint(77, 77, 17, 8, 7, 2, 2),     // Olivine City
        HgssFlyMapScreens.Flypoint(78, 78, 18, 11, 4, 2, 2),    // Ecruteak City
        HgssFlyMapScreens.Flypoint(87, 87, 19, 16, 5, 1, 1),    // Mahogany Town
        HgssFlyMapScreens.Flypoint(89, 89, 21, 20, 4, 2, 2),    // Blackthorn City
        HgssFlyMapScreens.Flypoint(88, 88, 20, 15, 1, 3, 2),    // Lake of Rage
        HgssFlyMapScreens.Flypoint(90, 90, 22, 25, 8, 1, 1),    // Mt. Silver
        HgssFlyMapScreens.Flypoint(174, 174, 30, 2, 8, 2, 2),   // Safari Zone Gate
        HgssFlyMapScreens.Flypoint(272, 411, 27, 6, 6, 2, 2),   // Battle Frontier (lands in Frontier Access)
        HgssFlyMapScreens.Flypoint(96, 280, 35, 10, 6, 2, 2),   // National Park (lands at the Pokéathlon Dome)
        HgssFlyMapScreens.Flypoint(124, 30, 33, 28, 7, 1, 2),   // Victory Road (lands on Route 26)
    )
}
