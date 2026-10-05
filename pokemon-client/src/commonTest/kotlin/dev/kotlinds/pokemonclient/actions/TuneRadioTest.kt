package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioBand
import dev.kotlinds.pokemonclient.state.RadioChannel
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import dev.kotlinds.pokemonclient.state.sameAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `tune_radio` on a scripted Pokégear radio: presets, drags of the dial's cursor, verification, wrong band. */
class TuneRadioTest {

    private val channels = listOf(
        RadioChannel(0, listOf(RadioStation.POKEMON_MUSIC), TouchPoint(112, 76), 4, 16, TouchPoint(32, 62)),
        RadioChannel(1, listOf(RadioStation.POKEMON_TALK), TouchPoint(152, 76), 4, 20, TouchPoint(224, 62)),
        RadioChannel(4, listOf(RadioStation.POKE_FLUTE), TouchPoint(128, 48), 4, 8),
    )

    private fun radio(cursor: TouchPoint, band: RadioBand = RadioBand.KANTO_EXPANSION, list: List<RadioChannel> = channels): PokegearRadio {
        val tuned = list.firstOrNull { abs(it.touch.x - cursor.x) <= it.clearRadius && abs(it.touch.y - cursor.y) <= it.clearRadius }
        return PokegearRadio(band, list, cursor, tuned?.index, tuned != null, tuned?.stations?.first())
    }

    private fun viewer(radio: PokegearRadio) =
        Screen.Viewer(ViewerApp.POKEGEAR_RADIO, ViewerExit(touch = TouchPoint(230, 176)), Awaiting.INPUT, radio = radio)

    /** A radio whose presets snap the cursor and whose cursor follows a drag that starts on it. */
    private fun scriptedRadio(start: TouchPoint, presetsWork: Boolean = true): FakeGame {
        val game = FakeGame(viewer(radio(start)))
        var dragging = false
        var seen = 0
        game.onTouch = { point, screen ->
            val current = (screen as Screen.Viewer).radio!!
            val preset = channels.firstOrNull { it.preset == point }
            when {
                preset != null -> if (presetsWork) viewer(radio(preset.touch)) else screen
                abs(point.x - current.cursor.x) <= 8 && abs(point.y - current.cursor.y) <= 8 -> { dragging = true; screen }
                else -> screen
            }
        }
        game.onFrame = { _, screen ->
            val moved = game.touchFrames.size > seen
            seen = game.touchFrames.size
            when {
                dragging && moved -> viewer(radio(game.touchFrames.last()))
                else -> { if (!moved) dragging = false; screen }
            }
        }
        return game
    }

    @Test
    fun parsesStationIds() {
        val registry = ActionRegistry.of()
        fun parse(json: String) = registry.parse(Json.parseToJsonElement(json).jsonObject, ActionMode.ASSISTED)
        assertEquals(TuneRadio(RadioStation.POKE_FLUTE, close = true), parse("""{"type":"tune_radio","station":"poke_flute","close":true}""").getOrThrow())
        assertEquals(TuneRadio(RadioStation.POKEMON_TALK), parse("""{"type":"tune_radio","station":"station:pokemon_talk"}""").getOrThrow())
        assertTrue(parse("""{"type":"tune_radio","station":"Poké Flûte"}""").isFailure, "ids only, never display names")
    }

    @Test
    fun aPresetTunesChannelsZeroToThree() {
        val game = scriptedRadio(TouchPoint(129, 49))
        val outcome = assertIs<ActionOutcome.Done>(PokegearActions.tuneRadio.plan.run(TuneRadio(RadioStation.POKEMON_TALK), game.context()))
        assertEquals(listOf(TouchPoint(224, 62)), game.touches)
        assertTrue("channel 1" in outcome.detail!!)
        assertEquals(1, (game.screen as Screen.Viewer).radio!!.tuned)
    }

    @Test
    fun thePokeFluteIsReachedByDraggingTheCursorFromWhereItIs() {
        val game = scriptedRadio(TouchPoint(112, 76))
        assertIs<ActionOutcome.Done>(PokegearActions.tuneRadio.plan.run(TuneRadio(RadioStation.POKE_FLUTE), game.context()))
        assertEquals(TouchPoint(112, 76), game.touchFrames.first(), "the stylus goes down on the cursor")
        assertEquals(TouchPoint(128, 48), game.touchFrames.last())
        val radio = (game.screen as Screen.Viewer).radio!!
        assertEquals(4 to true, radio.tuned to radio.clear)
    }

    @Test
    fun aPresetThatDidNotTakeIsFollowedByADrag() {
        val game = scriptedRadio(TouchPoint(128, 48), presetsWork = false)
        assertIs<ActionOutcome.Done>(PokegearActions.tuneRadio.plan.run(TuneRadio(RadioStation.POKEMON_MUSIC), game.context()))
        assertEquals(TouchPoint(32, 62), game.touches.first())
        assertEquals(0, (game.screen as Screen.Viewer).radio!!.tuned)
    }

    @Test
    fun aDialThatNeverMovesEndsInAVerificationError() {
        val game = FakeGame(viewer(radio(TouchPoint(112, 76))))
        val outcome = assertIs<ActionOutcome.Failed>(PokegearActions.tuneRadio.plan.run(TuneRadio(RadioStation.POKE_FLUTE), game.context()))
        assertIs<ActionError.VerificationFailed>(outcome.error)
    }

    @Test
    fun aStationNotOnThisDialIsRefusedWithWhereToHearIt() {
        val johto = channels.take(2)
        val game = FakeGame(viewer(radio(TouchPoint(112, 76), RadioBand.JOHTO, johto)))
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(PokegearActions.tuneRadio.plan.run(TuneRadio(RadioStation.POKE_FLUTE), game.context())).error)
        assertTrue("Expansion Card" in error.hint!!)
        assertTrue(game.touches.isEmpty())
    }

    @Test
    fun theDialAndTheProgrammeAreSeenAsChanges() {
        val music = viewer(radio(TouchPoint(112, 76)))
        val talk = viewer(radio(TouchPoint(152, 76)))
        assertFalse(music.sameAs(talk), "a drag that moves the station is a change")
        assertFalse(music.sameAs(music.copy(radio = music.radio!!.copy(line = "next line"))), "a new line of the programme too")
        assertTrue(music.sameAs(viewer(radio(TouchPoint(112, 76)))))
    }
}
