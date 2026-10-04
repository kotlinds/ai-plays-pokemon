package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Recipes of the Pokémon storage system: walk to the nearest PC, boot it, open the storage, run one or several
 * operations (deposit, withdraw, move to another box, swap a party Pokémon with a stored one) without switching the
 * PC off in between, then switch it off. Pokémon are found by their ids, never by name.
 *
 * The PC's two menus are multichoices whose entries are always in the same order, whatever the language: storage
 * first in the top menu (SOMEONE'S / BILL'S PC), then DEPOSIT, WITHDRAW, MOVE, MOVE ITEMS, SEE YA! in the storage
 * menu. Leaving needs only B: B picks the last entry (SEE YA!, SWITCH OFF) of these menus and confirms it.
 *
 * Every operation is checked on the party and the boxes read from the save data after it, and reported.
 */
internal object PcPlans {

    val deposit = ActionPlan<GameAction.Deposit> { action, context -> session(context, listOf(PcOperation.Deposit(action.mon))) }

    val withdraw = ActionPlan<GameAction.Withdraw> { action, context -> session(context, listOf(PcOperation.Withdraw(action.mon))) }

    val pc = ActionPlan<GameAction.Pc> { action, context -> session(context, action.operations) }

    /** Boots the PC once, runs [operations] in order (stopping at the first failure), switches it off. */
    private fun session(context: PlanContext, operations: List<PcOperation>): ActionOutcome {
        if (operations.isEmpty()) return ActionOutcome.Failed(ActionError.InvalidParameter("operations", "empty", listOf("deposit", "withdraw", "move", "swap")))
        val plan = validate(context.state(), operations.map { normalize(context.state(), it) })
        if (plan is Step.Failed) return ActionOutcome.Failed(plan.error)
        val ops = (plan as Step.Done).value
        val opened = openStorage(context)
        if (opened is Step.Failed) {
            PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
            return ActionOutcome.Failed(opened.error)
        }
        val report = mutableListOf<String>()
        var failure: ActionError? = null
        for (op in ops) {
            val before = context.state()
            val step = when (op) {
                is PcOperation.Deposit -> runDeposit(context, op)
                is PcOperation.Withdraw -> runWithdraw(context, op)
                is PcOperation.Move -> runMove(context, op)
                is PcOperation.Swap -> runSwap(context, op)
            }.andThen { backToStorageMenu(context) }
            if (step is Step.Failed) {
                failure = step.error
                break
            }
            when (val checked = check(before, context.state(), op)) {
                is Step.Done -> report += checked.value
                is Step.Failed -> {
                    failure = checked.error
                    break
                }
            }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        return when {
            failure == null -> ActionOutcome.Done(report.joinToString("; "))
            report.isEmpty() -> ActionOutcome.Failed(failure)
            else -> ActionOutcome.Done(report.joinToString("; ") + "; stopped: ${failure.code} ${failure.message}")
        }
    }

    // region Checks

    /** A move of a party Pokémon is a deposit into that box. */
    private fun normalize(state: GameState, op: PcOperation): PcOperation =
        if (op is PcOperation.Move && state.party.any { it.id == op.mon }) PcOperation.Deposit(op.mon, op.box) else op

    /** Refuses impossible sessions before touching the PC, following the party size and boxes through the operations. */
    private fun validate(state: GameState, operations: List<PcOperation>): Step<List<PcOperation>> {
        val party = state.party.map { it.id }.toMutableList()
        val able = state.party.filter { !it.isEgg && !it.fainted }.map { it.id }.toMutableSet()
        val storage = state.storage
        val stored = storage?.mons?.associate { it.id to it.box }?.toMutableMap()
        val counts = storage?.boxes?.associate { it.index to it.mons.size }?.toMutableMap()
        fun boxFull(box: Int) = counts != null && (counts[box] ?: 0) >= BOX_SLOTS
        for (op in operations) {
            when (op) {
                is PcOperation.Deposit -> {
                    if (op.mon !in party) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in the party")
                    if (party.size <= 1 || (op.mon in able && able.size <= 1)) return unavailable(UnavailableReason.LAST_POKEMON, "${op.mon} is your last Pokémon able to battle")
                    if (op.box != null && op.box !in 0 until BOXES) return Step.Failed(ActionError.InvalidParameter("box", op.box.toString(), listOf("0..${BOXES - 1}")))
                    if (op.box != null && boxFull(op.box)) return unavailable(UnavailableReason.PARTY_FULL, "Box ${op.box + 1} is full")
                    party -= op.mon
                    able -= op.mon
                    val box = op.box ?: counts?.entries?.sortedBy { it.key }?.firstOrNull { it.value < BOX_SLOTS }?.key
                    if (box != null) {
                        counts?.set(box, (counts[box] ?: 0) + 1)
                        stored?.set(op.mon, box)
                    }
                }
                is PcOperation.Withdraw -> {
                    if (stored != null && op.mon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in any box", "the boxes hold: ${storage.mons.joinToString { "${it.id} ${it.displayName}" }}")
                    if (party.size >= PARTY_SIZE) return unavailable(UnavailableReason.PARTY_FULL, "The party is full", "deposit one first (in the same pc session)")
                    stored?.remove(op.mon)?.let { counts?.set(it, (counts[it] ?: 1) - 1) }
                    party += op.mon
                    able += op.mon
                }
                is PcOperation.Move -> {
                    if (op.box !in 0 until BOXES) return Step.Failed(ActionError.InvalidParameter("box", op.box.toString(), listOf("0..${BOXES - 1}")))
                    if (stored != null && op.mon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in any box")
                    if (boxFull(op.box)) return unavailable(UnavailableReason.PARTY_FULL, "Box ${op.box + 1} is full")
                    stored?.put(op.mon, op.box)?.let { from -> counts?.set(from, (counts[from] ?: 1) - 1) }
                    counts?.set(op.box, (counts[op.box] ?: 0) + 1)
                }
                is PcOperation.Swap -> {
                    if (op.partyMon !in party) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.partyMon} isn't in the party")
                    if (stored != null && op.boxMon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.boxMon} isn't in any box")
                    if (op.partyMon in able && able.size <= 1) return unavailable(UnavailableReason.LAST_POKEMON, "${op.partyMon} is your last Pokémon able to battle")
                    party[party.indexOf(op.partyMon)] = op.boxMon
                    able -= op.partyMon
                    able += op.boxMon
                    stored?.remove(op.boxMon)?.let { box -> stored[op.partyMon] = box }
                }
            }
        }
        return Step.Done(operations)
    }

    private fun unavailable(reason: UnavailableReason, detail: String, hint: String? = null) = Step.Failed(ActionError.Unavailable(reason, detail, hint))

    /** What the operation changed, read from the party and the boxes; a typed error when it didn't happen. */
    private fun check(before: GameState, after: GameState, op: PcOperation): Step<String> {
        fun name(id: MonId) = before.party.firstOrNull { it.id == id }?.displayName ?: before.storage?.find(id)?.displayName ?: id.toString()
        fun where(id: MonId) = after.storage?.find(id)?.let { mon -> after.storage.boxes.getOrNull(mon.box)?.let { "${it.name} (${it.mons.size}/${it.capacity})" } }
        return when (op) {
            is PcOperation.Deposit ->
                if (after.party.none { it.id == op.mon }) Step.Done("deposited ${name(op.mon)}" + (where(op.mon)?.let { " in $it" } ?: ""))
                else Step.Failed(ActionError.Timeout("${name(op.mon)} is still in the party"))
            is PcOperation.Withdraw ->
                if (after.party.any { it.id == op.mon }) Step.Done("withdrew ${name(op.mon)} (party: ${after.party.size}/$PARTY_SIZE)")
                else Step.Failed(ActionError.Timeout("${name(op.mon)} didn't join the party"))
            is PcOperation.Move -> {
                val box = after.storage?.find(op.mon)?.box
                if (box == op.box) Step.Done("moved ${name(op.mon)} to ${where(op.mon)}")
                else Step.Failed(ActionError.Timeout("${name(op.mon)} is in box ${box?.plus(1)} instead of ${op.box + 1}"))
            }
            is PcOperation.Swap ->
                if (after.party.any { it.id == op.boxMon } && after.party.none { it.id == op.partyMon }) {
                    Step.Done("swapped ${name(op.partyMon)} (now in ${where(op.partyMon) ?: "the PC"}) with ${name(op.boxMon)} (now in the party)")
                } else Step.Failed(ActionError.Timeout("the swap of ${name(op.partyMon)} and ${name(op.boxMon)} didn't happen"))
        }
    }

    // endregion

    // region Operations (each starts and ends on the storage menu)

    private fun runDeposit(context: PlanContext, op: PcOperation.Deposit): Step<GameState> =
        openMode(context, PcMode.DEPOSIT).andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to deposit") { it.id == op.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "DEPOSIT") { it.id == "option:deposit" }
        }.andThen { picker ->
            // "Deposit in which box?": the box asked for, or the first box with room (full boxes aren't selectable).
            val boxes = picker.screen as? Screen.ListMenu ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the box picker", picker.screen.toString()))
            val box = boxes.entries.firstOrNull { if (op.box != null) it.id == "box:${op.box}" else it.id.startsWith("box:") && it.selectable }
                ?: return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.PARTY_FULL, "Every box is full"))
            context.navigator.select(Screen.ListMenu::class, box.label) { it.id == box.id }.andThen {
                context.navigator.choose(Screen.ListMenu::class, "DEPOSIT POKéMON") { it.id == "option:deposit" }
            }
        }

    private fun runWithdraw(context: PlanContext, op: PcOperation.Withdraw): Step<GameState> =
        openMode(context, PcMode.WITHDRAW).andThen { showBoxWith(context, op.mon) }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to withdraw") { it.id == op.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "WITHDRAW") { it.id == "option:withdraw" }
        }

    /** MOVE POKéMON: pick the Pokémon up, bring the cursor to the box tabs, highlight the box, put it down there. */
    private fun runMove(context: PlanContext, op: PcOperation.Move): Step<GameState> =
        pickUp(context, op.mon).andThen {
            context.navigator.select(Screen.PcBox::class, "the box tabs") { it.id.startsWith("box:") || it.id.startsWith("tab:") }
        }.andThen { highlightBox(context, op.box) }.andThen {
            context.navigator.confirm("box ${op.box + 1}", target = { it.id == "box:${op.box}" })
        }

    /**
     * MOVE POKéMON: pick the stored Pokémon up and put it on the party Pokémon: the game swaps them at once (the party
     * Pokémon goes to the freed box slot, verified live). Should the cursor still carry the party Pokémon, it is put
     * down in the slot the stored one came from.
     */
    private fun runSwap(context: PlanContext, op: PcOperation.Swap): Step<GameState> {
        val slot = context.state().storage?.find(op.boxMon)?.slot
        return pickUp(context, op.boxMon).andThen {
            context.navigator.choose(Screen.PcBox::class, "the party Pokémon") { it.id == op.partyMon.toString() }
        }.andThen { swapped ->
            val box = swapped.screen as? Screen.PcBox ?: return@andThen Step.Done(swapped)
            if (box.holding == null) return@andThen Step.Done(swapped)
            if (box.holding != op.partyMon || slot == null) return@andThen Step.Failed(ActionError.UnexpectedScreen("the swap done", swapped.screen.toString()))
            context.navigator.choose(Screen.PcBox::class, "the box slot") { it.id == "slot:$slot" || it.id == op.boxMon.toString() }
        }
    }

    /** In MOVE POKéMON: shows the box holding [mon] and picks it up (through MOVE in the menu when one opens). */
    private fun pickUp(context: PlanContext, mon: MonId): Step<GameState> =
        openMode(context, PcMode.MOVE).andThen { showBoxWith(context, mon) }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to move") { it.id == mon.toString() }
        }.andThen { state ->
            if (state.screen is Screen.ContextMenu) context.navigator.choose(Screen.ContextMenu::class, "MOVE") { it.id == "option:move" } else Step.Done(state)
        }.andThen { state ->
            if ((state.screen as? Screen.PcBox)?.holding == mon) Step.Done(state)
            else Step.Failed(ActionError.UnexpectedScreen("the cursor carrying $mon", state.screen.toString()))
        }

    /**
     * With the cursor on the box tabs: LEFT / RIGHT (the shorter way round) until box [box] is highlighted, re-reading
     * after every press; an unexpected highlight counts as a correction (at most [RetryPolicy.maxCorrections]).
     */
    private fun highlightBox(context: PlanContext, box: Int): Step<GameState> {
        var corrections = 0
        var expected: Int? = null
        repeat(BOXES * 2) {
            val state = context.navigator.settle()
            val screen = state.screen as? Screen.PcBox ?: return Step.Failed(ActionError.UnexpectedScreen("the box tabs", state.screen.toString()))
            val current = (screen.cursor as? Cursor.At)?.let { screen.entries.getOrNull(it.index) }?.id?.takeIf { it.startsWith("box:") }?.removePrefix("box:")?.toIntOrNull()
                ?: return Step.Failed(ActionError.UnexpectedScreen("the cursor on a box tab", screen.toString()))
            if (current == box) return Step.Done(state)
            if (expected != null && current != expected) corrections++
            if (corrections > MAX_CORRECTIONS) return Step.Failed(ActionError.VerificationFailed("box ${box + 1}", "box ${box + 1}", "box ${current + 1}", corrections))
            val right = (box - current).mod(BOXES) <= BOXES / 2
            expected = (current + if (right) 1 else -1).mod(BOXES)
            context.scope.tap(if (right) Button.RIGHT else Button.LEFT)
            context.navigator.awaitChange(screen, maxFrames = TAB_FRAMES)
        }
        return Step.Failed(ActionError.Timeout("box ${box + 1} was never highlighted"))
    }

    // endregion

    // region Menus

    /** Walks to the PC, boots it and opens the storage menu. */
    private fun openStorage(context: PlanContext): Step<GameState> {
        when (val booted = MovePlans.interact.run(GameAction.Interact(MovePlans.PC), context)) {
            is ActionOutcome.Failed -> return Step.Failed(booted.error)
            is ActionOutcome.Done -> Unit
        }
        return pcMenu(context).andThen {
            context.navigator.choose(Screen.ListMenu::class, "the storage system") { it.id == "option:$TOP_STORAGE" }
        }.andThen { pcMenu(context) }
    }

    /** From the storage menu, opens the box in [mode]. */
    private fun openMode(context: PlanContext, mode: PcMode): Step<GameState> {
        val entry = when (mode) {
            PcMode.DEPOSIT -> STORAGE_DEPOSIT
            PcMode.WITHDRAW -> STORAGE_WITHDRAW
            PcMode.MOVE -> STORAGE_MOVE
            PcMode.MOVE_ITEMS -> STORAGE_MOVE_ITEMS
        }
        return context.navigator.choose(Screen.ListMenu::class, mode.name) { it.id == "option:$entry" }.andThen {
            context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.PcBox)?.mode == mode }
        }
    }

    /** Reads messages until the next PC multichoice. */
    private fun pcMenu(context: PlanContext) =
        context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }

    /**
     * Leaves the box screen for the storage menu: B on the box (twice from the MOVE party panel) asks "Continue Box
     * operations?", where NO leaves; the RETURN button would ask "Exit the Box?", where YES leaves. Questions are told
     * apart by what the answer does, never by their text: answer NO first, and YES the next time if NO kept the box
     * open. Messages are read; never leaves while the cursor carries a Pokémon.
     */
    private fun backToStorageMenu(context: PlanContext): Step<GameState> {
        var answerYes = false
        var answeredNo = false
        repeat(EXIT_PRESSES) {
            val state = context.navigator.settle()
            when (val screen = state.screen) {
                is Screen.ListMenu -> if (screen.kind == MenuKind.MULTICHOICE) return Step.Done(state) else context.scope.tap(Button.B)
                is Screen.YesNo -> {
                    if (screen.entries.any { it.dangerous }) return Step.Failed(ActionError.UnexpectedScreen("the box", screen.toString()))
                    val answer = if (answerYes) "option:yes" else "option:no"
                    val answered = context.navigator.choose(Screen.YesNo::class, answer) { it.id == answer }
                    if (answered is Step.Failed) return answered
                    answeredNo = !answerYes
                    return@repeat
                }
                is Screen.PcBox -> {
                    if (screen.holding != null) return Step.Failed(ActionError.UnexpectedScreen("empty hands", "the cursor still carries ${screen.holding}"))
                    // Back on the box right after a NO: that question was "Exit the Box?", answer YES next time.
                    if (answeredNo) answerYes = true
                    context.scope.tap(Button.B)
                }
                is Screen.Dialogue, is Screen.PressToContinue -> context.scope.tap(Button.A)
                is Screen.ContextMenu -> context.scope.tap(Button.B)
                else -> context.scope.step(10)
            }
            context.navigator.awaitChange(state.screen, maxFrames = 60)
        }
        return Step.Failed(ActionError.Timeout("couldn't get back to the storage menu"))
    }

    /** Shows each box in turn (touching the next-box arrow) until one holds [mon]. */
    private fun showBoxWith(context: PlanContext, mon: MonId): Step<GameState> {
        val monId = mon.toString()
        repeat(BOXES) {
            val state = context.navigator.settle()
            val box = state.screen as? Screen.PcBox ?: return Step.Failed(ActionError.UnexpectedScreen("a PC box", state.screen.toString()))
            if (box.entries.any { it.id == monId }) return Step.Done(state)
            // The stored box is known from the save data: go the shorter way round.
            val target = state.storage?.find(mon)?.box
            val backwards = target != null && (box.box - target).mod(BOXES) < (target - box.box).mod(BOXES)
            val arrow = box.entries.firstOrNull { it.id == if (backwards) "option:prev_box" else "option:next_box" }?.touch
                ?: return Step.Failed(ActionError.NotOnScreen("the box arrow", "the PC box", box.entries.map { it.id }))
            context.scope.touch(arrow)
            context.navigator.awaitChange(box)
        }
        return Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "$monId isn't in any box"))
    }

    // endregion

    private const val TOP_STORAGE = 0
    private const val STORAGE_DEPOSIT = 0
    private const val STORAGE_WITHDRAW = 1
    private const val STORAGE_MOVE = 2
    private const val STORAGE_MOVE_ITEMS = 3
    private const val PC_WAITS = 40
    private const val BOXES = 18
    private const val BOX_SLOTS = 30
    private const val PARTY_SIZE = 6
    private const val CLOSE_PRESSES = 16
    private const val EXIT_PRESSES = 12
    private const val MAX_CORRECTIONS = 3
    private const val TAB_FRAMES = 30
}
