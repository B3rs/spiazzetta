package me.bers.spiazzetta.domain

import kotlinx.coroutines.delay

/** Contract the transport layer implements: talk to the Agua cloud. */
interface StoveTransport {
    /** Sends the setting to the stove. May succeed at transport level while the stove ignores it. */
    suspend fun send(stoveId: String, setting: StoveSetting)

    /** Reads the current state. Throws [AguaException] when it cannot. */
    suspend fun read(stoveId: String): StoveSnapshot
}

/**
 * Turns a fire-and-forget write into a verified one.
 *
 * The stock MyPiazzetta app posts a register write and never checks whether the stove applied it,
 * which is why a fan speed can silently not change. Here every write is followed by a read of the
 * real state, retried, and finally reported as [WriteOutcome.NotConfirmed] so the user always knows.
 */
class ConfirmedWriter(
    private val transport: StoveTransport,
    private val attempts: Int = 3,
    private val initialBackoffMillis: Long = 700,
    private val backoffFactor: Double = 1.8,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(attempts >= 1) { "attempts must be >= 1" }
    }

    suspend fun write(stoveId: String, setting: StoveSetting): WriteOutcome {
        var lastSnapshot: StoveSnapshot? = null
        var lastCause = WriteOutcome.NotConfirmed.Cause.TRANSPORT_ERROR
        var backoff = initialBackoffMillis.toDouble()

        for (attempt in 1..attempts) {
            val sendFailure = runCatching { transport.send(stoveId, setting) }.exceptionOrNull()
            when (sendFailure) {
                null -> Unit
                is AguaException.StoveOffline -> lastCause = WriteOutcome.NotConfirmed.Cause.STOVE_OFFLINE
                else -> lastCause = WriteOutcome.NotConfirmed.Cause.TRANSPORT_ERROR
            }

            val readBack = runCatching { transport.read(stoveId) }
            readBack.getOrNull()?.let { snapshot ->
                lastSnapshot = snapshot
                if (snapshot.matches(setting)) {
                    return WriteOutcome.Confirmed(setting, snapshot, attempt)
                }
                lastCause = WriteOutcome.NotConfirmed.Cause.READ_BACK_MISMATCH
            } ?: run {
                val failure = readBack.exceptionOrNull()
                if (failure is AguaException.StoveOffline) {
                    lastCause = WriteOutcome.NotConfirmed.Cause.STOVE_OFFLINE
                }
            }

            if (attempt < attempts) {
                pause(backoff.toLong())
                backoff *= backoffFactor
            }
        }
        return WriteOutcome.NotConfirmed(setting, attempts, lastSnapshot, lastCause)
    }
}
