package me.bers.spiazzetta.domain

/** A pellet stove reachable through the Agua IoT cloud. */
data class Stove(
    val id: String,
    val name: String,
    val serial: String?,
    val model: String?,
    val macAddress: String?,
    val online: Boolean,
    val productId: String,
    val deviceId: String,
    val registrationNumber: String,
)

/** Coarse power state, derived from the cloud's own status/enable lists. */
enum class StoveOperatingState { OFF, ON, ALARM, UNKNOWN }

/** What this particular stove model exposes, taken from its register map. */
data class StoveCapabilities(
    val canPower: Boolean = false,
    val fanSpeedRange: IntRange? = null,
    val setpointRange: IntRange? = null,
    val powerLevelRange: IntRange? = null,
    val canEco: Boolean = false,
    val canNaturalMode: Boolean = false,
    val ductRightRange: IntRange? = null,
    val ductLeftRange: IntRange? = null,
    val multifireRange: IntRange? = null,
    val qualityRange: IntRange? = null,
) {
    // Name clash with data-class members is avoided by explicit getters.
    val supportsFanSpeed: Boolean get() = fanSpeedRange != null
    val supportsSetpoint: Boolean get() = setpointRange != null
    val supportsPowerLevel: Boolean get() = powerLevelRange != null
    val supportsDucts: Boolean get() = ductRightRange != null || ductLeftRange != null
}

/** What the stove reports beyond the basics, taken from its own register map. */
data class StoveDetails(
    val flueTemperature: Double? = null,
    val ductRightSetpoint: Int? = null,
    val ductLeftSetpoint: Int? = null,
    val ductRightRange: IntRange? = null,
    val ductLeftRange: IntRange? = null,
    val pelletLevel: PelletLevel = PelletLevel.UNKNOWN,
    val serviceHours: Int? = null,
    val serviceThreshold: Int? = null,
    val firmware: String? = null,
    val naturalMode: Boolean? = null,
    val multifireLevel: Int? = null,
    val multifireRange: IntRange? = null,
    val multifireModality: String? = null,
    val externalThermostat: String? = null,
    val stoveClock: String? = null,
    val warnings: List<StoveWarning> = emptyList(),
    val chronoPrograms: List<ChronoProgram> = emptyList(),
    val combustion: CombustionCorrection = CombustionCorrection(),
) {
    val serviceDue: Boolean
        get() = serviceHours != null && serviceThreshold != null && serviceHours >= serviceThreshold
}

enum class PelletLevel { OK, LOW, EMPTY, UNKNOWN }

/** One register as the stove reported it, for the register browser and the export. */
data class RegisterEntry(
    val group: String,
    val type: String,
    val offset: Int,
    val mask: Int,
    val raw: Int?,
    val decoded: Double?,
    val label: String?,
    val writable: Boolean,
)

/** Pellet-quality correction: the stove stores it as a value centred on 5, shown as -5..+5. */
data class CombustionCorrection(
    val powerLow: Int? = null,
    val powerHigh: Int? = null,
    val range: IntRange? = null,
) {
    val supported: Boolean get() = powerLow != null || powerHigh != null
}

data class StoveWarning(val kind: Kind) {
    enum class Kind { POWER_FAILURE, POWER_FAILURE_RESTART, AUGER_LOAD, SERVICE_DUE }
}

data class ChronoProgram(
    val index: Int,
    val enabled: Boolean,
    val start: String?,
    val stop: String?,
    val temperature: Int?,
    val power: Int?,
    val daysMask: Int = 0,
)

/** Minutes from midnight as the stove stores them; 144 is the "unset" sentinel. */
fun minutesToClock(minutes: Int?): String? {
    if (minutes == null || minutes < 0 || minutes > 1439) return null
    val hours = minutes / 60
    val mins = minutes % 60
    return hours.toString().padStart(2, '0') + ":" + mins.toString().padStart(2, '0')
}

fun clockToMinutes(clock: String?): Int? {
    val parts = clock?.split(":") ?: return null
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val mins = parts[1].toIntOrNull() ?: return null
    return hours * 60 + mins
}

/** A complete, timestamped picture of the stove. */
data class StoveSnapshot(
    val stoveId: String,
    val state: StoveOperatingState,
    val rawStateCode: Int?,
    /** Text the cloud itself provides for the current state, already localized. */
    val stateLabel: String?,
    val alarmCode: Int?,
    val alarmLabel: String?,
    val roomTemperature: Double?,
    val setpoint: Int?,
    val fanSpeed: Int?,
    val powerLevel: Int?,
    val ecoEnabled: Boolean?,
    val capabilities: StoveCapabilities,
    val details: StoveDetails = StoveDetails(),
    /** Every register the stove exposed in the last read, for the browser and the export. */
    val registers: List<RegisterEntry> = emptyList(),
    val receivedAtMillis: Long,
    /** Raw register values keyed by register group, for the diagnostics screen. */
    val rawRegisters: Map<String, Int> = emptyMap(),
) {
    val isControllable: Boolean
        get() = state != StoveOperatingState.ALARM && state != StoveOperatingState.UNKNOWN
}

/** Every user-visible command the app can send, expressed in domain units. */
sealed interface StoveSetting {
    val displayKey: String

    data class FanSpeed(val level: Int) : StoveSetting {
        override val displayKey: String get() = "fan_speed"
    }

    data class Setpoint(val celsius: Int) : StoveSetting {
        override val displayKey: String get() = "setpoint"
    }

    data class PowerLevel(val level: Int) : StoveSetting {
        override val displayKey: String get() = "power_level"
    }

    data class Powered(val on: Boolean) : StoveSetting {
        override val displayKey: String get() = "power"
    }

    data class Eco(val enabled: Boolean) : StoveSetting {
        override val displayKey: String get() = "eco"
    }

    /** Natural convection: the stove stops pushing air with the fan. */
    data class NaturalMode(val enabled: Boolean) : StoveSetting {
        override val displayKey: String get() = "natural_mode"
    }

    data class DuctRight(val celsius: Int) : StoveSetting {
        override val displayKey: String get() = "duct_right"
    }

    data class DuctLeft(val celsius: Int) : StoveSetting {
        override val displayKey: String get() = "duct_left"
    }

    data class Multifire(val level: Int) : StoveSetting {
        override val displayKey: String get() = "multifire"
    }

    /** Weekly-programme edits. Each field is its own register on the stove. */
    data class ChronoProgramEdit(
        val program: Int,
        val chronoField: ChronoField,
        val value: Int,
    ) : StoveSetting {
        override val displayKey: String get() = "chrono" + program + "_" + chronoField.key
    }

    /** Weekday selection: the seven day flags share one byte, one bit each. */
    data class ChronoDays(val program: Int, val mask: Int) : StoveSetting {
        override val displayKey: String get() = "chrono" + program + "_days"
    }

    /** Pellet-quality correction, -5..+5 around the stove's default (stored as raw 0..10). */
    data class QualityCorrection(val lowBand: Boolean, val value: Int) : StoveSetting {
        override val displayKey: String get() = if (lowBand) "quality_low" else "quality_high"
    }
}

enum class ChronoField(val key: String) {
    ENABLE("enable"),
    START("start"),
    STOP("stop"),
    TEMPERATURE("temperature"),
    POWER("power"),
}

object ChronoWeekday {
    const val MONDAY = 1
    const val TUESDAY = 2
    const val WEDNESDAY = 4
    const val THURSDAY = 8
    const val FRIDAY = 16
    const val SATURDAY = 32
    const val SUNDAY = 64

    val ALL = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)

    fun keyFor(flag: Int): String = when (flag) {
        MONDAY -> "monday"
        TUESDAY -> "tuesday"
        WEDNESDAY -> "wednesday"
        THURSDAY -> "thursday"
        FRIDAY -> "friday"
        SATURDAY -> "saturday"
        else -> "sunday"
    }
}

/** Why a command did not reach the stove. Every failure is surfaced to the user. */
sealed class AguaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable? = null) : AguaException("network", cause)
    class Unauthorized : AguaException("unauthorized")
    class SessionExpired : AguaException("session_expired")
    class Server(val statusCode: Int, val body: String?) : AguaException("server_" + statusCode)
    class InvalidResponse(val detail: String) : AguaException("invalid_response: " + detail)
    class StoveOffline(val stoveId: String) : AguaException("stove_offline: " + stoveId)
    class StoveRejected(val detail: String) : AguaException("stove_rejected: " + detail)
    class UnsupportedSetting(val displayKey: String) : AguaException("unsupported_setting: " + displayKey)

    /** The bridge answered with a business error code (not an auth failure). */
    class Bridge(val code: String?, val text: String?, val action: String) :
        AguaException("bridge " + code + " on " + action + (": " + text).orEmpty())

    /** The app is running in read-only mode: no command may reach the stove. */
    class ReadOnly : AguaException("read_only")
}

/**
 * Outcome of a write. A command is only ever reported as [Confirmed] when a fresh read from the
 * stove proves the value changed; anything else is told to the user.
 */
sealed interface WriteOutcome {
    data class Confirmed(val setting: StoveSetting, val snapshot: StoveSnapshot, val attempts: Int) : WriteOutcome

    data class NotConfirmed(
        val setting: StoveSetting,
        val attempts: Int,
        val lastSnapshot: StoveSnapshot?,
        val cause: Cause,
    ) : WriteOutcome {
        enum class Cause { READ_BACK_MISMATCH, STOVE_OFFLINE, TRANSPORT_ERROR }
    }
}

/** Result of reading the state of one stove. */
sealed interface ReadOutcome {
    data class Success(val snapshot: StoveSnapshot) : ReadOutcome
    data class Failure(val cause: AguaException) : ReadOutcome
}

/** Does the snapshot prove the setting was applied? */
fun StoveSnapshot.matches(setting: StoveSetting): Boolean = when (setting) {
    is StoveSetting.FanSpeed -> fanSpeed == setting.level
    is StoveSetting.Setpoint -> setpoint == setting.celsius
    is StoveSetting.PowerLevel -> powerLevel == setting.level
    is StoveSetting.Eco -> ecoEnabled == setting.enabled
    is StoveSetting.NaturalMode -> details.naturalMode == setting.enabled
    is StoveSetting.DuctRight -> details.ductRightSetpoint == setting.celsius
    is StoveSetting.DuctLeft -> details.ductLeftSetpoint == setting.celsius
    is StoveSetting.Multifire -> details.multifireLevel == setting.level
    is StoveSetting.ChronoProgramEdit -> {
        val program = details.chronoPrograms.firstOrNull { it.index == setting.program }
        when (setting.chronoField) {
            ChronoField.ENABLE -> program != null && program.enabled == (setting.value == 1)
            ChronoField.START -> program?.start == minutesToClock(setting.value * 10)
            ChronoField.STOP -> program?.stop == minutesToClock(setting.value * 10)
            ChronoField.TEMPERATURE -> program?.temperature == setting.value
            ChronoField.POWER -> program?.power == setting.value
        }
    }
    is StoveSetting.ChronoDays -> {
        val program = details.chronoPrograms.firstOrNull { it.index == setting.program }
        program != null && program.daysMask == setting.mask
    }
    is StoveSetting.QualityCorrection ->
        if (setting.lowBand) details.combustion.powerLow == setting.value else details.combustion.powerHigh == setting.value
    is StoveSetting.Powered ->
        if (setting.on) state != StoveOperatingState.OFF else state == StoveOperatingState.OFF
}
