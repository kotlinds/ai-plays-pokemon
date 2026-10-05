package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4Bdhc
import dev.kotlinds.pokemonclient.games.gen4.Gen4Charmap
import dev.kotlinds.pokemonclient.games.gen4.Gen4LandData
import dev.kotlinds.pokemonclient.games.gen4.Gen4MapMatrix
import dev.kotlinds.pokemonclient.games.gen4.Gen4MessageFile
import dev.kotlinds.pokemonclient.games.gen4.Gen4Text
import dev.kotlinds.pokemonclient.games.gen4.Gen4ZoneEvents

/*
 * HeartGold / SoulSilver names of the Generation 4 pieces they share with Diamond / Pearl / Platinum (package
 * `gen4`): the HGSS code keeps using its own names, the implementation lives once in the Gen 4 layer.
 */

/** Gen 4 text decoding ([Gen4Text]). */
typealias HgssText = Gen4Text

/** Gen 4 character table ([Gen4Charmap]). */
internal typealias HgssCharmap = Gen4Charmap

/** Gen 4 message file ([Gen4MessageFile]). */
typealias HgssMessageFile = Gen4MessageFile

/** Gen 4 map matrix ([Gen4MapMatrix]). */
typealias HgssMapMatrix = Gen4MapMatrix

/** Gen 4 land data ([Gen4LandData]). */
typealias HgssLandData = Gen4LandData

/** Gen 4 BDHC heights ([Gen4Bdhc]). */
typealias HgssBdhc = Gen4Bdhc

/** Gen 4 zone events ([Gen4ZoneEvents]). */
typealias HgssZoneEvents = Gen4ZoneEvents
typealias NameScreenType = dev.kotlinds.pokemonclient.games.gen4.NameScreenType
