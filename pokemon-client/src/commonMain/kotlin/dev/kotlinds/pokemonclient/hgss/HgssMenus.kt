package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.MenuState

/**
 * The menu waiting for a choice, whatever its kind (script yes/no or multichoice, start menu, battle menus), with
 * the labels shown on screen, the highlighted option and how the D-pad moves the cursor.
 */
object HgssMenus {

    data class View(
        val kind: String,
        val options: List<String>,
        val cursor: Int?,
        val layout: MenuState.Layout,
        /** How to move and choose, for the AI. */
        val controls: String,
        /** Extra note, e.g. "the cursor is hidden: the first D-pad press shows it". */
        val note: String? = null,
        /** Exact on-screen (row, column) of each option for irregular menus; null when [layout] says it all. */
        val positions: List<MenuState.Position>? = null,
    ) {
        fun toMenuState() = MenuState(kind, options, cursor, layout, positions)
        val highlighted: String? get() = cursor?.let { options.getOrNull(it) }
    }

    fun current(state: HgssState): View? {
        state.menu?.let { m ->
            if (m.options.isEmpty()) return null
            val grid = m.columns > 1
            return View(
                kind = if (m.kind == "yes_no") "yes/no" else "multichoice",
                options = m.options,
                cursor = m.cursor,
                layout = if (grid) MenuState.Layout.TWO_COLUMNS else MenuState.Layout.VERTICAL,
                controls = if (grid) {
                    "two columns, options listed row by row (left, right, then next row): D-pad moves, A chooses" +
                        if (m.kind == "multichoice") ", B picks the last option" else ""
                } else {
                    "UP/DOWN move the cursor, A chooses, B " + if (m.kind == "yes_no") "answers NO" else "picks the last option"
                },
            )
        }
        if (state.mode == GameMode.START_MENU) state.startMenu?.let { sm ->
            return View(
                kind = "start menu",
                options = sm.items,
                cursor = sm.cursor,
                layout = MenuState.Layout.TWO_COLUMNS,
                controls = "icons in 2 columns (options listed row by row, \"-\" = empty slot): " +
                    "left column ${sm.leftColumn.joinToString(", ")}; right column ${sm.rightColumn.joinToString(", ")} (top to bottom). " +
                    "D-pad moves (empty slots are skipped), A opens, B or X closes the menu",
            )
        }
        state.battle?.takeIf { it.awaitingInput }?.let { return battleMenu(it) }
        state.app?.takeIf { it.entries.isNotEmpty() }?.let { app ->
            return View(
                kind = app.title,
                options = app.entries,
                cursor = app.cursor,
                layout = when (app.layout) {
                    "horizontal" -> MenuState.Layout.HORIZONTAL
                    "grid2" -> MenuState.Layout.TWO_COLUMNS
                    else -> MenuState.Layout.VERTICAL
                },
                controls = app.prompt ?: "D-pad moves, A chooses, B goes back",
            )
        }
        return null
    }

    private fun battleMenu(b: BattleInfo): View? {
        val cursor = b.menuCursor // [y, x] or null while hidden
        val hidden = "no option is highlighted yet: the first D-pad press only shows the cursor (A does nothing until then)"
        return when (b.menu) {
            "MAIN_FIGHT_ONLY" -> View(
                "battle: main", listOf("FIGHT"), if (cursor == null) null else 0, MenuState.Layout.VERTICAL,
                "only FIGHT is available: A chooses it", if (cursor == null) hidden else null,
            )
            "MAIN", "PAL_PARK", "PAL_PARK_INITIAL" -> {
                // battle_input.c: y=0 FIGHT (big button on top); y=1: x=0 BAG, x=1 RUN, x=2 POKéMON
                val index = cursor?.let { (y, x) -> if (y == 0) 0 else when (x) { 0 -> 1; 1 -> 2; else -> 3 } }
                View(
                    "battle: main", listOf("FIGHT", "BAG", "RUN", "POKéMON"), index, MenuState.Layout.TWO_COLUMNS,
                    "FIGHT is the big button on top; below it, left to right: BAG, RUN, POKéMON. " +
                        "From FIGHT, LEFT goes to BAG, RIGHT to POKéMON, DOWN to the button below; UP returns to FIGHT; A chooses",
                    if (cursor == null) hidden else null,
                    // FIGHT is the big button on top; BAG, RUN and POKéMON are the row below it (left to right).
                    positions = listOf(MenuState.Position(0, 1), MenuState.Position(1, 0), MenuState.Position(1, 1), MenuState.Position(1, 2)),
                )
            }
            "FIGHT" -> {
                val player = b.player.firstOrNull()
                val moves = (0 until 4).map { i ->
                    player?.moves?.getOrNull(i)?.let { "${it.name} (${it.type ?: "?"}, ${it.pp}/${it.maxPp} PP)" } ?: "-"
                }
                val index = cursor?.let { (y, x) -> if (y >= 2) 4 else y * 2 + x }
                View(
                    "battle: fight", moves + "CANCEL", index, MenuState.Layout.TWO_COLUMNS,
                    "moves in a 2x2 grid (1 2 / 3 4), CANCEL below: D-pad moves, A uses the move, B goes back",
                    if (cursor == null) hidden else null,
                )
            }
            "YES_NO", "KEEP_FORGET_MOVE", "GIVE_UP_ON_MOVE", "SWITCH_OR_FLEE", "SWITCH_OR_KEEP" -> {
                val options = when (b.menu) {
                    "KEEP_FORGET_MOVE" -> listOf("FORGET A MOVE", "KEEP OLD MOVES")
                    "SWITCH_OR_FLEE" -> listOf("USE NEXT POKéMON", "FLEE")
                    "SWITCH_OR_KEEP" -> listOf("SWITCH", "KEEP BATTLING")
                    else -> listOf("YES", "NO")
                }
                View(
                    "battle: ${b.menu.lowercase().replace('_', ' ')}", options, cursor?.let { (y, _) -> y.coerceIn(0, 1) },
                    MenuState.Layout.VERTICAL, "UP/DOWN move, A chooses, B picks the bottom option",
                    if (cursor == null) hidden else null,
                )
            }
            else -> null
        }
    }
}
