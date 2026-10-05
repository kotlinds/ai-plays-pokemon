package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.runtime.SEED_POLLS
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleStat
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MajorStatus
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens met while checking the untested recipes live on the bench (8-badge save; scenarios in the report of the
 * "tests" workstream): a TM over an HM, battle statuses and stat stages, the forced replacement and "Will you
 * switch?" of an Elite Four battle, the nickname keyboard, a Rare Candy evolution, the Mail menus and Mom's call.
 */
class HgssLiveChecksFixtureTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    private fun state(name: String): GameState = game.state(HgssFixtures.load(name))

    private fun foe(name: String) = state(name).battle!!.battlers.single { it.ref == BattlerRef.FOE_LEFT }

    // region TM / HM

    @Test
    fun anHmOnTheForgetListOfATmIsShownButNotSelectable() {
        // TM01 (Focus Punch) taught to Typhlosion, which knows Cut (HM01): Cut can't be chosen.
        val list = assertIs<Screen.MoveSelect>(state("ts_teach_hm_forget").screen)
        assertEquals(MoveContext.FORGET_SUMMARY, list.context)
        assertEquals("mon:52d32ba1.76f3a6fb", list.mon.toString())
        assertEquals(264, list.newMove?.id?.value)
        assertEquals(listOf("move:436", "move:15", "move:53", "move:129", "move:264"), list.entries.map { it.id })
        assertEquals(listOf(true, false, true, true, true), list.entries.map { it.selectable })
    }

    // endregion

    // region Battle statuses

    @Test
    fun aParalyzedFoeIsReadAsParalyzed() {
        // Wild Poliwag after Ampharos' Thunder Wave.
        val poliwag = foe("ts_bt_foe_paralyzed")
        assertEquals(60, poliwag.species.id.value)
        assertEquals(MajorStatus.Paralyzed, poliwag.status)
        assertTrue(poliwag.volatile.isEmpty())
        assertTrue(poliwag.statStages.isEmpty())
    }

    @Test
    fun icyWindLowersTheFoesSpeedOneStage() {
        val poliwag = foe("ts_bt_foe_speed_down")
        assertEquals(mapOf(BattleStat.SPEED to -1), poliwag.statStages)
        assertEquals(MajorStatus.Paralyzed, poliwag.status)
    }

    @Test
    fun whirlpoolBindsAndTrapsTheFoeAndIntimidateLowersItsAttack() {
        val poliwag = foe("ts_bt_foe_bound")
        assertEquals(setOf(VolatileStatus.Bound(4), VolatileStatus.Trapped), poliwag.volatile)
        assertEquals(mapOf(BattleStat.ATTACK to -1, BattleStat.SPEED to -1), poliwag.statStages)
    }

    @Test
    fun ourConfusionAndTheFoesCalmMindStagesAreRead() {
        // Elite Four Will's Xatu: a move that raised all its stats (AncientPower-like) is at +1 everywhere, our Ho-Oh is confused.
        val battle = state("ts_bt_confused_stages").battle!!
        val hoOh = battle.battlers.single { it.ref == BattlerRef.PLAYER_LEFT }
        assertEquals(setOf(VolatileStatus.Confused(3)), hoOh.volatile)
        assertEquals(mapOf(BattleStat.SP_DEFENSE to -2), hoOh.statStages)
        val xatu = battle.battlers.single { it.ref == BattlerRef.FOE_LEFT }
        assertEquals(178, xatu.species.id.value)
        assertEquals(listOf(BattleStat.ATTACK, BattleStat.DEFENSE, BattleStat.SPEED, BattleStat.SP_ATTACK, BattleStat.SP_DEFENSE).associateWith { 1 }, xatu.statStages)
    }

    // endregion

    // region Elite Four battle

    @Test
    fun afterAKnockOutTheReplacementGridHasNoCancelAndTheFaintedOneIsRefused() {
        val grid = assertIs<Screen.PartyGrid>(state("ts_bt_replace_fainted").screen)
        assertEquals(PartyPurpose.BATTLE_REPLACE_FAINTED, grid.purpose)
        assertEquals(CancelBehavior.NONE, grid.cancel)
        val hoothoot = grid.entries.first()
        assertEquals("mon:c50a0956.76f3a6fb", hoothoot.id)
        assertFalse(hoothoot.selectable)
        assertEquals(5, grid.entries.count { it.id.startsWith("mon:") && it.selectable })
        assertFalse(grid.entries.single { it.id == "option:cancel" }.selectable)
    }

    @Test
    fun willYouSwitchIsTheSwitchOrKeepMenu() {
        val menu = assertIs<Screen.ListMenu>(state("ts_bt_switch_or_keep").screen)
        assertEquals(MenuKind.BATTLE_SWITCH_OR_KEEP, menu.kind)
        assertEquals(listOf("option:switch", "option:keep"), menu.entries.map { it.id })
        assertEquals(CancelBehavior.CONFIRMS_LAST, menu.cancel)
    }

    // endregion

    // region Nickname keyboard

    @Test
    fun theNicknameKeyboardOfACapture() {
        val keyboard = assertIs<Screen.Keyboard>(state("ts_kb_nickname").screen)
        assertEquals("pokemon", keyboard.purpose)
        assertEquals("upper", keyboard.page)
        assertEquals("", keyboard.buffer)
        assertEquals(10, keyboard.maxLength)
        val ids = keyboard.entries.map { it.id }.toSet()
        assertTrue(listOf("key:A", "key:-", "key:♀", "key:0", "page:lower", "page:others", "option:ok").all { it in ids })
    }

    // endregion

    // region Evolution (Rare Candy on a Magikarp Lv20)

    @Test
    fun theEvolutionSceneIsAnAnimationThatCanBeStoppedOnlyDuringTheMorph() {
        val start = assertIs<Screen.Evolution>(state("ts_evo_start").screen)
        assertEquals(129, start.from.id.value)
        assertEquals(130, start.to?.id?.value)
        assertEquals(Awaiting.ANIMATION, start.awaiting)
        assertFalse(start.canCancel)
        assertNull(start.text)

        val message = assertIs<Screen.Evolution>(state("ts_evo_msg_printing").screen)
        assertTrue(message.text!!.contains("MAGIKARP"))
        assertFalse(message.canCancel)

        val morph = assertIs<Screen.Evolution>(state("ts_evo_cancelable").screen)
        assertTrue(morph.canCancel)
        assertEquals(Awaiting.ANIMATION, morph.awaiting)
    }

    @Test
    fun theEvolutionEndsOnAFieldMessageWithTheNewSpecies() {
        val s = state("ts_evo_congrats")
        val congrats = assertIs<Screen.Dialogue>(s.screen)
        assertEquals(TextSource.FIELD, congrats.source)
        assertEquals(130, s.party.single { it.id.toString() == "mon:120ae20c.76f3a6fb" }.species.id.value)
    }

    /**
     * After an Elite Four battle won with an evolution, the field comes back with the save's older party for two
     * frames (the Magikarp at its pre-battle level and every HP back to the start): one evolution only, never
     * "GYARADOS evolved into MAGIKARP" (P8 of the speedrun notes).
     */
    @Test
    fun theOlderPartyShownForTwoFramesAfterABattleIsNotAReverseEvolution() {
        val magikarp = state("ts_evo_stale_f125")
        assertEquals(129, magikarp.party.first().species.id.value)
        val evolved = state("ts_evo_stale_f124")
        val stale = state("ts_evo_stale_f125")
        val after = state("ts_evo_stale_f127")
        assertEquals(130, after.party.first().species.id.value)
        val events = record(List(SEED_POLLS + 2) { magikarp } + evolved + stale + stale + after + after)
            .filterIsInstance<GameEvent.Evolved>()
        assertEquals(listOf("MAGIKARP" to "GYARADOS"), events.map { it.from to it.to })
    }

    private fun record(states: List<GameState>): List<GameEvent> {
        var current = states.first()
        val scripted = object : PokemonGame {
            override val name = "Scripted"
            override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
            override fun state(memory: Memory) = current
            override val inputProbe = InputProbe { emptySet() }
        }
        val recorder = Recorder(scripted, every = 1)
        val memory = HgssFixtures.load("ts_evo_stale_f124")
        states.forEachIndexed { frame, state ->
            current = state
            recorder.onFrame(frame.toLong()) { memory }
        }
        return recorder.log.since(0)
    }

    // endregion

    // region Mail

    @Test
    fun aPokemonHoldingMailHasMailInsteadOfItemThenReadAndTake() {
        val menu = assertIs<Screen.ContextMenu>(state("ts_pb_mail_holder_menu").screen)
        assertEquals("mon:00006b5e.000003e9", menu.owner.toString())
        assertTrue(menu.entries.any { it.id == "option:mail" })
        assertTrue(menu.entries.none { it.id == "option:item" })
        val submenu = assertIs<Screen.ContextMenu>(state("ts_pb_mail_submenu").screen)
        assertEquals(listOf("option:read", "option:take", "option:quit"), submenu.entries.map { it.id })
        // TAKE asks whether to send the Mail to the PC (yes keeps the message).
        val question = assertIs<Screen.YesNo>(state("ts_pb_mail_take_question").screen)
        assertEquals(listOf("option:yes", "option:no"), question.entries.map { it.id })
    }

    // endregion

    // region Mom's call (Route 30, after the egg is delivered)

    @Test
    fun momsCallIsAPhoneDialogueThenATwoChoiceQuestion() {
        val hello = assertIs<Screen.Dialogue>(state("ts_mom_call_start").screen)
        assertEquals(TextSource.PHONE, hello.source)
        assertEquals("Mother", hello.speaker)
        assertEquals(Awaiting.INPUT, hello.awaiting)
        val question = assertIs<Screen.ListMenu>(state("ts_mom_call_question").screen)
        assertEquals(MenuKind.MULTICHOICE, question.kind)
        assertEquals(listOf("option:0", "option:1"), question.entries.map { it.id })
        assertTrue(question.entries.all { it.touch != null })
    }

    // endregion
}
