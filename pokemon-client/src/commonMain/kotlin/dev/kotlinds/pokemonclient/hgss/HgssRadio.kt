package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.PokegearRadio
import dev.kotlinds.pokemonclient.state.RadioBand
import dev.kotlinds.pokemonclient.state.RadioChannel
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

/**
 * The Pokégear radio (overlay 101, `PokegearRadio_Init`, src/application/pokegear/radio/): the dial and what airs.
 *
 * - The dial: `PokegearRadioAppData` (include/application/pokegear/radio/radio_internal.h) holds the band received
 *   here (`stationSelection`, chosen from the map and the story by `Radio_GetAvailableChannels`), the cursor
 *   (`cursorX/Y`, touch-screen pixels), the channel tuned (`station`, 255 between channels) and the signal (2 clear,
 *   1 static). Channels are circles of `sTuningHitboxes_*` (overlay_101_021F4F34.c): touching the inner circle gives a
 *   clear signal, the outer one static.
 * - Tuning: touching one of the four preset buttons (`sTuningButtonHitboxes`) snaps the cursor to channels 0-3; any
 *   other channel (Kanto's Poké Flute) needs a drag that starts within 8 pixels of the cursor
 *   (`Radio_HandleTouchInput_Internal`), or the D-pad (2 pixels a frame).
 * - The programme: `RadioShow` (title, host, the last line printed, `curStation` = `RADIO_STATION_*`). A channel's
 *   programme comes from `RadioShow_TranslateStationID` (channels 2 and 3 change with the hour).
 * - The music: `sRadioSeqNo` (sound_radio_sys.c), the radio's sequence; it plays on after the Pokégear is closed,
 *   and scripts test it (`RadioMusicIsPlaying`: the Route 11 Snorlax wakes to the Poké Flute).
 */
internal object HgssRadio {

    /** `PokegearRadio_Init` (pokegear_radio.o). */
    const val FN_RADIO_INIT = 0x021F4480L

    /** `POKEGEAR_APP_MAIN_STATE_RUN_RADIO` (pokegear_main.c). */
    const val GEAR_STATE_RUN_RADIO = 5

    /** `RADIO_MAIN_STATE_INPUT_LOOP`. */
    const val RADIO_STATE_INPUT_LOOP = 1

    // PokegearRadioAppData (radio_internal.h).
    private const val RA_FLAGS = 0x24L        // unk:1, isDraggingCursor:4, selectedButton:3
    private const val RA_TUNING = 0x26L       // stationSelection:4, signalStrength:2, stationActive:2
    private const val RA_STATION = 0x27L
    private const val RA_CURSOR_X = 0x28L
    private const val RA_CURSOR_Y = 0x2AL
    private const val RA_SHOW = 0x60L

    // RadioShow.
    private const val RS_LINE = 0x48L
    private const val RS_TITLE = 0x4CL
    private const val RS_HOST = 0x50L
    private const val RS_CUR_STATION = 0x59L

    /** `sRadioSeqNo` (sound_radio_sys.o .bss). */
    const val RADIO_SEQ_NO = 0x021D05E0L

    /** Radio sequences (include/constants/sndseq.h) and the programme they belong to (`GetRadioMusicPlayingSeq`). */
    private val SEQ_STATIONS = mapOf(
        1102 to RadioStation.POKE_FLUTE, 1314 to RadioStation.POKE_FLUTE, // SEQ_GS_HUE, SEQ_GS_P_HUE
        1100 to RadioStation.POKEMON_MUSIC, 1312 to RadioStation.POKEMON_MUSIC, // SEQ_GS_RADIO_MARCH (+ P)
        1099 to RadioStation.POKEMON_MUSIC, 1311 to RadioStation.POKEMON_MUSIC, // SEQ_GS_RADIO_KOMORIUTA (lullaby)
        1169 to RadioStation.POKEMON_MUSIC, 1170 to RadioStation.POKEMON_MUSIC, // Hoenn / Sinnoh sounds
        1101 to RadioStation.UNOWN, // SEQ_GS_RADIO_UNKNOWN
    )

    /** One circle of `sTuningHitboxes_*`: channel [channel] at ([x], [y]), clear within [clear] px, static up to [radius]. */
    private data class Circle(val channel: Int, val x: Int, val y: Int, val clear: Int, val radius: Int)

    private val JOHTO = listOf(Circle(0, 112, 76, 4, 16), Circle(1, 152, 76, 4, 20), Circle(2, 96, 108, 4, 16), Circle(3, 136, 116, 4, 20))
    /**
     * The four preset buttons (`sTuningButtonHitboxes`, centres of their rects): each snaps the cursor to the centre of
     * Johto channel 0-3 (`Radio_SnapCursorToChannelHitbox`), which are also Kanto's channels 0-3.
     */
    private val PRESETS = listOf(TouchPoint(32, 62), TouchPoint(224, 62), TouchPoint(32, 126), TouchPoint(224, 126))

    /** The bands whose channels 0-3 sit where the presets snap. */
    private val PRESET_BANDS = setOf(RadioBand.JOHTO, RadioBand.KANTO, RadioBand.KANTO_EXPANSION)

    private val ROCKET_MAHOGANY_AREA = Triple(128, 92, 38 to 52)

    /** The channels of each band (`sTuningHitboxes`, by `RadioStationSelection`). */
    private fun circles(band: RadioBand): List<Circle> = when (band) {
        RadioBand.JOHTO -> JOHTO
        RadioBand.KANTO -> JOHTO.take(3)
        RadioBand.KANTO_EXPANSION -> JOHTO + Circle(4, 128, 48, 4, 8)
        RadioBand.NO_SIGNAL -> emptyList()
        RadioBand.RUINS_OF_ALPH -> listOf(Circle(5, 128, 92, 4, 16))
        RadioBand.TEAM_ROCKET -> listOf(ROCKET_MAHOGANY_AREA.let { (x, y, r) -> Circle(6, x, y, r.first, r.second) })
        RadioBand.MAHOGANY -> listOf(ROCKET_MAHOGANY_AREA.let { (x, y, r) -> Circle(7, x, y, r.first, r.second) })
    }

    /** The programmes a channel airs (`RadioShow_TranslateStationID`): channels 2 and 3 by the hour. */
    fun stationsOf(channel: Int): List<RadioStation> = when (channel) {
        0 -> listOf(RadioStation.POKEMON_MUSIC)
        1 -> listOf(RadioStation.POKEMON_TALK)
        2 -> listOf(RadioStation.TRAINER_PROFILES, RadioStation.THAT_TOWN_THESE_PEOPLE)
        3 -> listOf(RadioStation.POKEMON_SEARCH_PARTY, RadioStation.SERIAL_RADIO_DRAMA, RadioStation.BUENAS_PASSWORD)
        4 -> listOf(RadioStation.POKE_FLUTE)
        5 -> listOf(RadioStation.UNOWN)
        6 -> listOf(RadioStation.TEAM_ROCKET)
        7 -> listOf(RadioStation.MAHOGANY_SIGNAL)
        else -> emptyList()
    }

    /**
     * The radio app's data when the Pokégear runs the radio ([gear]: the Pokégear's overlay manager, [gearData] its
     * `PokegearAppData`), with whether it reads input now. Null when the radio isn't the running app.
     */
    fun radioApp(mem: HgssMemory, gear: Long, gearData: Long): Pair<Long, Boolean>? {
        val child = mem.ptr(gearData + HgssTextAddresses.GEAR_CHILD_APP) ?: return null
        if (mem.fn(child + A.OM_INIT) != FN_RADIO_INIT) return null
        val data = mem.ptr(child + A.OM_DATA) ?: return null
        val ready = mem.s32(gear + A.OM_PROC_STATE) == GEAR_STATE_RUN_RADIO && mem.s32(child + A.OM_EXEC_STATE) == 2 &&
            mem.s32(child + A.OM_PROC_STATE) == RADIO_STATE_INPUT_LOOP
        return data to ready
    }

    /** Decodes the dial and the programme of the radio app at [radio] ([inAppBar]: the cursor is on the app bar). */
    fun decode(mem: HgssMemory, radio: Long, inAppBar: Boolean): PokegearRadio? {
        val tuning = mem.u8(radio + RA_TUNING)
        val band = RadioBand.entries.getOrNull(tuning and 0xF) ?: return null
        val signal = (tuning shr 4) and 3
        val active = (tuning shr 6) and 3 != 0
        val channel = mem.u8(radio + RA_STATION).takeIf { it != 0xFF }
        val presets = band in PRESET_BANDS
        val channels = circles(band).map {
            RadioChannel(it.channel, stationsOf(it.channel), TouchPoint(it.x, it.y), it.clear, it.radius, PRESETS.getOrNull(it.channel)?.takeIf { presets })
        }
        val show = mem.ptr(radio + RA_SHOW)
        val station = if (active && show != null) RadioStation.entries.getOrNull(mem.u8(show + RS_CUR_STATION)) else null
        fun text(offset: Long) = show?.let { mem.gameString(mem.ptr(it + offset)) }?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        return PokegearRadio(
            band = band,
            channels = channels,
            cursor = TouchPoint(mem.s16(radio + RA_CURSOR_X), mem.s16(radio + RA_CURSOR_Y)),
            tuned = channel,
            clear = channel != null && signal == 2,
            station = station,
            title = if (active) text(RS_TITLE) else null,
            host = if (active) text(RS_HOST) else null,
            line = if (active) text(RS_LINE) else null,
            inAppBar = inAppBar,
            playing = playing(mem),
        )
    }

    /** The radio programme whose music plays now (`sRadioSeqNo`), on the Pokégear or after it was closed. */
    fun playing(mem: HgssMemory): RadioStation? = SEQ_STATIONS[mem.u32(RADIO_SEQ_NO).toInt()]
}
