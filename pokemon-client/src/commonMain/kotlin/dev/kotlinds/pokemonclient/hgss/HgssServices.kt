package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldTrainer
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.ShopItem

/**
 * Adds to a mapped [GameState] what services need and the main reader doesn't give: the PC boxes and the OPTIONS
 * (save data), whether Fly works on this map (map header), who the trainers on the map are (scripts + trainer
 * data + trainer flags) and what the clerks sell (clerk scripts + mart tables).
 *
 * Stateful (the box reader's cache): one per game.
 */
internal class HgssServices {

    private val boxes = HgssBoxReader()

    fun enrich(mapped: GameState, raw: HgssState, mem: HgssMemory): GameState {
        val save = HgssSave(mem)
        val withSave = mapped.copy(
            storage = runCatching { boxes.read(mem, save) }.getOrNull(),
            options = runCatching { save.options() }.getOrNull(),
            startMenu = runCatching { save.startMenu() }.getOrNull(),
        )
        val field = withSave.field ?: return withSave
        val objects = raw.surroundings?.objects.orEmpty().associateBy { "person:${it.id}" }
        val badges = withSave.player?.badges?.size ?: 0
        val badgeIds = raw.story?.badges
        return withSave.copy(
            field = field.copy(
                flyAllowed = HgssData.world?.header(field.mapId)?.flyAllowed,
                hasPc = hasPc(field.mapId),
                bikeAllowed = HgssData.world?.header(field.mapId)?.bikeAllowed,
                objects = field.objects.map { o -> objects[o.id]?.let { info -> enrichObject(o, info, field.mapId, badges, save, badgeIds) } ?: o },
            ),
        )
    }

    private val pcMaps = HashMap<Int, Boolean>()

    /** Whether zone [mapId] has a PC tile (only rooms do: other map types are never scanned), cached per zone. */
    private fun hasPc(mapId: Int): Boolean? {
        val world = HgssData.world ?: return null
        pcMaps[mapId]?.let { return it }
        val interior = world.header(mapId)?.mapType == MAP_TYPE_INTERIOR
        val area = if (interior) world.areaOf(mapId) ?: return null else null
        val found = area != null && (0 until area.height).any { dy ->
            (0 until area.width).any { dx ->
                val x = area.originX + dx
                val y = area.originY + dy
                area.tile(x, y)?.kind == dev.kotlinds.pokemonclient.world.TileKind.Pc && (area.zoneAt(x, y) ?: mapId) == mapId
            }
        }
        pcMaps[mapId] = found
        return found
    }

    /**
     * Beaten: its trainer flag, or what its scripted battle records once won: a flag (the Elite Four, Elder Li) or the
     * badge it gives (gym leaders), see [HgssTrainers.WonCondition].
     */
    private fun defeated(save: HgssSave, zone: Int, trainerId: Int, badgeIds: Set<Int>?): Boolean? {
        val byTrainerFlag = save.trainerDefeated(trainerId)
        val byScript = when (val won = HgssTrainers.wonCondition(zone, trainerId)) {
            is HgssTrainers.WonCondition.Flag -> save.flag(won.flag)
            is HgssTrainers.WonCondition.Badge -> badgeIds?.let { won.badge in it }
            is HgssTrainers.WonCondition.VarAtLeast -> save.variable(won.variable)?.let { it >= won.value }
            null -> null
        }
        return if (byTrainerFlag == true || byScript == true) true else byTrainerFlag ?: byScript
    }

    private fun enrichObject(o: FieldObject, info: MapObjectInfo, mapId: Int, badges: Int, save: HgssSave, badgeIds: Set<Int>? = null): FieldObject {
        if (o.kind != FieldObjectKind.PERSON) return o
        val zone = info.mapId.takeIf { it >= 0 } ?: mapId
        // The nurse's sprite is also worn by attendants on a common script (std_wifi_club_closed: the one blocking
        // the stairs of Cherrygrove's Center): only a map's own script (CallStd std_nurse_joy) heals.
        if (o.role == PersonRole.NURSE && info.scriptId >= FIRST_STD_SCRIPT) return o.copy(role = null)
        if (o.role == PersonRole.CLERK) {
            val catalog = HgssMarts.catalog(zone, info.scriptId, badges) ?: return o
            return o.copy(catalog = catalog.map { ShopItem(Named(ItemId(it), HgssData.itemName(it)), HgssItemPrices.price(it)) })
        }
        val trainerId = HgssTrainers.trainerOf(zone, info.id, info.scriptId) ?: return o
        val (trainerClass, name) = HgssTrainers.names(trainerId) ?: return o
        val trainer = FieldTrainer(
            trainerId = trainerId,
            trainerClass = trainerClass,
            name = name,
            defeated = defeated(save, zone, trainerId, badgeIds),
            sightRange = if (info.type == TRAINER_TYPE_SIGHT) info.param0.coerceIn(0, MAX_SIGHT) else 0,
        )
        return o.copy(label = label(trainer), trainer = trainer)
    }

    /** "Psychic Eli (trainer, beaten)" / "(trainer, not beaten, sees 2 tiles ahead)". */
    private fun label(t: FieldTrainer): String {
        val status = when (t.defeated) {
            true -> "beaten"
            false -> "not beaten"
            null -> null
        }
        val sight = if (t.sightRange > 0 && t.defeated != true) "sees ${t.sightRange} tiles ahead" else null
        return "${t.fullName} (" + listOfNotNull("trainer", status, sight).joinToString(", ") + ")"
    }

    private companion object {
        /** Map object type 1: a trainer who sees ahead (`param[0]` tiles); 0: battles only when talked to. */
        const val TRAINER_TYPE_SIGHT = 1
        const val MAX_SIGHT = 15

        /** `std_signpost` (include/constants/std_script.h): common scripts start here, a map's own are below. */
        const val FIRST_STD_SCRIPT = 2000

        /** `MAP_TYPE_INTERIOR` (include/map_header.h). */
        const val MAP_TYPE_INTERIOR = 4
    }
}
