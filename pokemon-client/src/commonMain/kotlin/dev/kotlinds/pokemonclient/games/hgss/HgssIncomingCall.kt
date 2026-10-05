package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.IncomingCall
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * A phone call ringing on the field (the caller's icon on the bottom screen, the player still walking): the
 * `GearPhoneRingManager` of the field system (`FieldSystem.phoneRingManager`, include/field_system.h) has `active`
 * set (`GearPhoneRingManager_StartRinging`, src/unk_02092BE8.c) with the caller in `callerId`. Calls that open the
 * Pokégear by themselves (the professor's scripted calls) are the Pokégear's own screens.
 */
internal object HgssIncomingCall : HgssScreenDecoder {

    /** `FieldSystem.phoneRingManager` (struct size 0x128, after `menuInputState` and `unk_110..113`). */
    const val FS_PHONE_RING_MANAGER = 0x114L

    /** `GearPhoneRingManager`: bit 0 of byte 0 = `active`, then `callerId` at +2 (`PHONE_CONTACT_*`). */
    private const val RING_FLAGS = 0x00L
    private const val RING_CALLER_ID = 0x02L
    private const val ACTIVE_BIT = 0x01

    /**
     * The POKéGEAR button of the field's bottom-screen menu: touching it opens the Pokégear, which answers a ringing
     * call (`FieldSystem_InitPokegearArgs`, src/unk_02092BE8.c). Found live: A or touching the caller's name doesn't.
     */
    val ANSWER = TouchPoint(38, 150)

    /** `NUM_PHONE_CONTACTS` (include/constants/phone_contacts.h). */
    private const val CONTACT_COUNT = 75

    /** The ringing call, or null when the phone doesn't ring. */
    fun ringing(mem: HgssMemory): IncomingCall? {
        val fieldSystem = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val manager = mem.ptr(fieldSystem + FS_PHONE_RING_MANAGER) ?: return null
        if (mem.u8(manager + RING_FLAGS) and ACTIVE_BIT == 0) return null
        val caller = mem.u8(manager + RING_CALLER_ID).takeIf { it < CONTACT_COUNT } ?: return null
        return IncomingCall("contact:$caller", HgssTextAddresses.contactName(caller), ANSWER)
    }

    /** On the walkable overworld only: the call is shown on the [Screen.Overworld] screen. */
    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.OVERWORLD) return null
        val call = ringing(mem) ?: return null
        return Screen.Overworld(awaiting = if (state.awaitingInput) Awaiting.INPUT else Awaiting.ANIMATION, incomingCall = call)
    }
}
