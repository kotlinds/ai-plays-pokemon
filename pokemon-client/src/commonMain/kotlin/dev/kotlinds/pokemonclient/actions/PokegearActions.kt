package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioChannel
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.ViewerApp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tunes the Pokégear radio to [station] (opening the Pokégear and its radio when needed), checks the dial in RAM,
 * and closes the Pokégear when [close] (the radio keeps playing: the Poké Flute still wakes the Route 11 Snorlax).
 */
data class TuneRadio(val station: RadioStation, val close: Boolean = false) : GameAction {
    override val key get() = "tune_radio(${station.wire})"
}

/** The Pokégear radio's action: `tune_radio`. Listed with the common actions ([CommonActions.definitions]). */
object PokegearActions {

    private val assisted = setOf(ActionMode.ASSISTED)

    val tuneRadio = ActionDefinition(TuneRadio::class, object : ActionSpec<TuneRadio> {
        override val name = "tune_radio"
        override val description = "Tune the Pokégear radio to a station (opens the Pokégear and its radio from the field " +
            "or another Pokégear app, touches the preset button or drags the dial's cursor onto the channel, checks the " +
            "station in the game). close: true closes the Pokégear after (the music keeps playing). In Kanto with the " +
            "Expansion Card, station poke_flute plays the Poké Flute: then talk to a sleeping Pokémon to wake it."
        override val parameters = listOf(
            Parameter("station", ParameterType.STRING, "The station id.", values = RadioStation.entries.filter { it != RadioStation.COMMERCIALS }.map { it.wire }),
            Parameter("close", ParameterType.BOOLEAN, "Close the Pokégear once tuned (default false).", required = false),
        )
        override val modes = assisted
        override fun availability(state: GameState): Availability = availabilityOf(state)
        override fun parse(json: JsonObject): TuneRadio {
            val raw = json["station"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw ActionException(ActionError.InvalidParameter("station", "missing"))
            val station = RadioStation.parse(raw)?.takeIf { it != RadioStation.COMMERCIALS }
                ?: throw ActionException(ActionError.InvalidParameter("station", raw, RadioStation.entries.filter { it != RadioStation.COMMERCIALS }.map { it.wire }))
            return TuneRadio(station, json["close"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        override fun enumerate(state: GameState) = emptyList<TuneRadio>()
    }, tunePlan())

    /** The definitions, in the order they are listed to agents. */
    val definitions: List<ActionDefinition<*>> get() = listOf(tuneRadio)

    // region Availability

    private fun availabilityOf(state: GameState): Availability {
        val screen = state.screen
        val radio = (screen as? Screen.Viewer)?.radio
        val onGear = screen is Screen.Viewer && screen.app in GEAR_VIEWERS ||
            (screen as? Screen.ListMenu)?.kind == MenuKind.PHONE_CONTACTS
        val walking = state.battle == null && (screen as? Screen.Overworld)?.awaiting == Awaiting.INPUT
        if (!onGear && !walking) return Availability.Hidden
        if (state.player?.pokegearCards?.contains(PokegearCard.RADIO) == false) {
            return Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The Pokégear has no Radio Card yet", "the Goldenrod Radio Tower's quiz gives it")
        }
        val stations = radio?.channels?.flatMap { it.stations } ?: RadioStation.entries.filter { it != RadioStation.COMMERCIALS }
        return Availability.Available(mapOf("station" to stations.map { Choice(it.wire, it.wire) }), listed = radio != null)
    }

    // endregion

    // region Plan

    private fun tunePlan() = ActionPlan<TuneRadio> { action, context ->
        val opened = openRadio(context)
        if (opened is Step.Failed) return@ActionPlan ActionOutcome.Failed(opened.error)
        var radio = (opened as Step.Done).value
        val channel = radio.channels.firstOrNull { action.station in it.stations }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(
                UnavailableReason.CANNOT_USE_HERE,
                "${action.station.wire} isn't on the dial here (band ${radio.band.name.lowercase()}: " +
                    radio.channels.flatMap { it.stations }.joinToString { it.wire }.ifEmpty { "no signal" } + ")",
                hintFor(action.station),
            ))
        var attempts = 0
        while (!(radio.tuned == channel.index && radio.clear)) {
            if (attempts > MAX_CORRECTIONS) {
                return@ActionPlan ActionOutcome.Failed(ActionError.VerificationFailed(
                    "the radio's channel ${channel.index} (${action.station.wire})",
                    expected = "channel ${channel.index}, clear signal",
                    actual = radio.tuned?.let { "channel $it" + if (radio.clear) "" else " (static)" } ?: "between channels",
                    attempts = attempts,
                ))
            }
            // A preset first; a drag of the cursor when there is none or the preset didn't take.
            val preset = channel.preset
            if (preset != null && attempts == 0) context.scope.touch(preset) else drag(context, radio.cursor, channel.touch)
            attempts++
            radio = settledRadio(context) ?: return@ActionPlan ActionOutcome.Failed(
                ActionError.UnexpectedScreen("the Pokégear radio", context.state().screen.kind),
            )
        }
        // The programme starts a few frames after the channel is tuned.
        var waited = 0
        while (radio.station == null && waited < PROGRAMME_FRAMES) {
            context.scope.step(4)
            waited += 4
            radio = (context.state().screen as? Screen.Viewer)?.radio ?: break
        }
        val airing = radio.station
        val detail = buildString {
            append("tuned to channel ${channel.index}")
            airing?.let { append(": ${it.wire}") }
            radio.title?.let { append(" (\"$it\")") }
            if (airing != null && airing != action.station && airing != RadioStation.COMMERCIALS) {
                append("; this channel airs ${airing.wire} at this hour (${action.station.wire} at other hours)")
            }
        }
        if (!action.close) return@ActionPlan ActionOutcome.Done(detail)
        PartyBagPlans.closeToOverworld(context)
        val after = context.navigator.settle()
        if (after.screen !is Screen.Overworld) {
            return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the field after closing the Pokégear", after.screen.kind))
        }
        val playing = after.field?.radioMusic
        ActionOutcome.Done("$detail; Pokégear closed" + (playing?.let { ", ${it.wire} still playing" } ?: ""))
    }

    /** Opens the Pokégear's radio from the field, the phone or the map; returns its dial once it reads input. */
    private fun openRadio(context: PlanContext): Step<PokegearRadio> {
        repeat(MAX_OPEN_STEPS) {
            val state = context.navigator.settle()
            when (val screen = state.screen) {
                is Screen.Viewer -> when {
                    screen.app == ViewerApp.POKEGEAR_RADIO -> {
                        if (screen.awaiting == Awaiting.INPUT && screen.radio != null) return Step.Done(screen.radio)
                        context.scope.step(10)
                    }
                    screen.app == ViewerApp.POKEGEAR_MAP -> {
                        val button = screen.apps.firstOrNull { it.id == RADIO_APP }
                            ?: return Step.Failed(ActionError.NotOnScreen(RADIO_APP, screen.kind, screen.apps.map { it.label }))
                        if (!button.selectable) return Step.Failed(noRadioCard())
                        context.scope.touch(button.touch ?: return Step.Failed(ActionError.Unreachable("the radio", button.label)))
                        context.navigator.awaitChange(screen)
                    }
                    else -> return Step.Failed(ActionError.UnexpectedScreen("the Pokégear", screen.kind))
                }
                is Screen.ListMenu -> {
                    if (screen.kind != MenuKind.PHONE_CONTACTS) return Step.Failed(ActionError.UnexpectedScreen("the Pokégear", (screen as Screen).kind))
                    if (screen.entries.firstOrNull { it.id == RADIO_APP }?.selectable == false) return Step.Failed(noRadioCard())
                    val touched = context.navigator.touchEntry(Screen.ListMenu::class, RADIO_APP, { it.id == RADIO_APP }, ActionError.Unreachable("the radio", RADIO_APP))
                    if (touched is Step.Failed) return touched
                }
                is Screen.Overworld -> {
                    if (state.battle != null) return Step.Failed(ActionError.Unavailable(UnavailableReason.IN_BATTLE, "Not during a battle"))
                    // Opening the Pokégear while the phone rings answers the call: the agent decides (advance_dialogue).
                    if (screen.incomingCall != null) return Step.Failed(ActionError.Interrupted(InterruptionCause.PHONE_CALL, "the phone rings (${screen.incomingCall.caller}): answer it first"))
                    val opened = PartyBagPlans.openStartMenuEntry(context, GEAR_ENTRY)
                    if (opened is Step.Failed) return opened
                }
                is Screen.Animation -> context.scope.step(10)
                is Screen.Dialogue -> return Step.Failed(
                    if (screen.source == TextSource.PHONE) ActionError.Interrupted(InterruptionCause.PHONE_CALL, "a call started: advance_dialogue reads it")
                    else ActionError.UnexpectedScreen("the field or the Pokégear", screen.kind),
                )
                else -> return Step.Failed(ActionError.UnexpectedScreen("the field or the Pokégear", screen.kind))
            }
        }
        return Step.Failed(ActionError.Timeout("the Pokégear radio didn't open"))
    }

    /** The radio's dial once it reads input again, or null when the screen is no longer the radio. */
    private fun settledRadio(context: PlanContext): PokegearRadio? {
        val screen = context.navigator.settle(maxFrames = SETTLE_FRAMES).screen as? Screen.Viewer ?: return null
        return screen.radio.takeIf { screen.app == ViewerApp.POKEGEAR_RADIO }
    }

    /**
     * Drags the dial's cursor from [cursor] to [to]: the stylus goes down on the cursor (the radio only picks it up
     * within 8 pixels of it), moves a little every frame, rests on [to], then lifts.
     */
    private fun drag(context: PlanContext, cursor: TouchPoint, to: TouchPoint) {
        for (i in 0..DRAG_FRAMES) {
            val point = TouchPoint(cursor.x + (to.x - cursor.x) * i / DRAG_FRAMES, cursor.y + (to.y - cursor.y) * i / DRAG_FRAMES)
            context.scope.step(1, InputFrame(touch = point))
        }
        context.scope.step(DRAG_HOLD_FRAMES, InputFrame(touch = to))
        context.scope.step(DRAG_RELEASE_FRAMES)
    }

    private fun noRadioCard() = ActionError.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The Pokégear has no Radio Card yet", "the Goldenrod Radio Tower's quiz gives it")

    /** Where a station that isn't on this dial can be heard. */
    private fun hintFor(station: RadioStation): String = when (station) {
        RadioStation.POKE_FLUTE -> "the Poké Flute airs in Kanto once the Pokégear has the Expansion Card (Lavender Radio Tower's director, after the Power Plant is restored)"
        RadioStation.UNOWN -> "only in the Ruins of Alph"
        RadioStation.TEAM_ROCKET -> "only during Team Rocket's Radio Tower takeover"
        RadioStation.MAHOGANY_SIGNAL -> "only around Mahogany Town until the Rocket hideout is cleared"
        else -> "outside caves, in Johto or in Kanto once its Power Plant is restored"
    }

    // endregion

    private val GEAR_VIEWERS = setOf(ViewerApp.POKEGEAR_RADIO, ViewerApp.POKEGEAR_MAP)
    private const val RADIO_APP = "app:radio"
    private const val GEAR_ENTRY = "option:pokegear"
    private const val MAX_OPEN_STEPS = 8
    private const val MAX_CORRECTIONS = 3
    private const val SETTLE_FRAMES = 120
    private const val PROGRAMME_FRAMES = 60
    private const val DRAG_FRAMES = 20
    private const val DRAG_HOLD_FRAMES = 6
    private const val DRAG_RELEASE_FRAMES = 4
}
