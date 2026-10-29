package me.bers.spiazzetta.data

import me.bers.spiazzetta.domain.Stove
import me.bers.spiazzetta.domain.StoveSetting
import me.bers.spiazzetta.domain.StoveSnapshot
import me.bers.spiazzetta.domain.StoveTransport

/**
 * Everything the app needs from the Agua IoT cloud.
 *
 * Implementations must never swallow a failure: a stove that did not apply a setting is reported,
 * not ignored. See docs/protocol/PROTOCOL.md for the wire contract.
 */
interface AguaRepository : StoveTransport {
    /** Authenticates and returns the stoves linked to the account. */
    suspend fun login(email: String, password: String): List<Stove>

    /** Re-reads the stove list using the current session. */
    suspend fun listStoves(): List<Stove>

    /** True when a session is held and believed valid. */
    fun hasSession(): Boolean

    /** Sends a setting to the stove. Transport-level only: see [StoveTransport.send]. */
    suspend fun sendSetting(stoveId: String, setting: StoveSetting)

    /** Reads the full stove state. */
    suspend fun readSnapshot(stoveId: String): StoveSnapshot
}
