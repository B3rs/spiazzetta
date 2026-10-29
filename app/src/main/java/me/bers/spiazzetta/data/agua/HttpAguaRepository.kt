package me.bers.spiazzetta.data.agua

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.bers.spiazzetta.data.AguaRepository
import me.bers.spiazzetta.domain.AguaException
import me.bers.spiazzetta.domain.ChronoProgram
import me.bers.spiazzetta.domain.ChronoWeekday
import me.bers.spiazzetta.domain.CombustionCorrection
import me.bers.spiazzetta.domain.RegisterEntry
import me.bers.spiazzetta.domain.PelletLevel
import me.bers.spiazzetta.domain.Stove
import me.bers.spiazzetta.domain.StoveCapabilities
import me.bers.spiazzetta.domain.StoveDetails
import me.bers.spiazzetta.domain.StoveOperatingState
import me.bers.spiazzetta.domain.StoveSetting
import me.bers.spiazzetta.domain.StoveSnapshot
import me.bers.spiazzetta.domain.StoveWarning
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Client for the Micronova "Agua IoT" bridge used by the Piazzetta stove app.
 *
 * Wire contract reverse-engineered from MyPiazzetta 1.9.91 - see docs/protocol/PROTOCOL.md:
 * every action is a request to the same bridge URL, with the action name in the "url" header and
 * the HTTP verb in the "method" header. Reads and writes are asynchronous jobs: create the job,
 * then poll deviceJobStatus/{idRequest} until it reports "completed".
 */
class HttpAguaRepository(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val languageTag: () -> String = { "it" },
    private val http: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val jobPollIntervalMillis: Long = 300,
    private val jobTimeoutMillis: Long = 45_000,
    /** When true, every write is refused before any request leaves the device. */
    private val readOnly: Boolean = false,
    private val phoneVersion: String = "1.0",
) : AguaRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var token: String? = null
    private var refreshToken: String? = null
    private var userId: String? = null
    private var aguaId: String? = null
    private var installationId: String = UUID.randomUUID().toString()

    private var cachedStoves: List<Stove> = emptyList()
    private val registerMaps = HashMap<String, DeviceRegisterMap>()

    override fun hasSession(): Boolean = token != null

    override suspend fun login(email: String, password: String): List<Stove> {
        // The stock app registers the installation (appSignup) before the first login; the bridge
        // answers 1144 for an installation it does not know.
        activateApplication()
        val response = try {
            call(
                action = "userLogin",
                payload = buildJsonObject {
                    put("email", email)
                    put("password", password)
                },
                authenticated = false,
            )
        } catch (rejected: AguaException.Bridge) {
            // 1134 = account not activated, 1144 = application not active: retry once after activating.
            if (rejected.code == ERROR_APP_NOT_ACTIVE || rejected.code == ERROR_NOT_ACTIVATED) {
                activateApplication()
                try {
                    call(
                        action = "userLogin",
                        payload = buildJsonObject {
                            put("email", email)
                            put("password", password)
                        },
                        authenticated = false,
                    )
                } catch (again: AguaException.Bridge) {
                    throw again
                }
            } else if (rejected.code == ERROR_USER_NOT_FOUND || rejected.code == ERROR_BAD_CREDENTIALS) {
                throw AguaException.Unauthorized()
            } else {
                throw rejected
            }
        }
        val newToken = response.string("token") ?: throw AguaException.Unauthorized()
        token = newToken
        refreshToken = response.string("refresh_token")
        loadUserDetail()
        return listStoves()
    }

    /** Registers this installation with the bridge, exactly as the stock app does before login. */
    private suspend fun activateApplication() {
        runCatching {
            val response = call(
                action = "appSignup",
                payload = buildJsonObject {
                    put("id_app", installationId)
                    put("push_notification_token", "")
                    put("push_notification_active", false)
                    put("phone_id", installationId)
                    put("phone_type", "android")
                    put("phone_version", phoneVersion)
                },
            )
            response.string("id_app")?.takeIf { it.isNotBlank() }?.let { installationId = it }
        }
    }

    private suspend fun loadUserDetail() {
        val response = runCatching { call("userDetail", JsonObject(emptyMap()), method = "GET") }.getOrNull() ?: return
        val user = response["User"].asObjectOrNull() ?: return
        userId = user.string("id")
        aguaId = user.string("agua_id")
    }

    override suspend fun listStoves(): List<Stove> {
        val response = call("deviceList", buildJsonObject { }, method = "POST")
        val devices = response["device"].asArrayOrNull() ?: JsonArray(emptyList())
        val stoves = devices.mapNotNull { element ->
            val device = element.asObjectOrNull() ?: return@mapNotNull null
            val id = device.string("id") ?: return@mapNotNull null
            val productInfo = device["product_info"].asObjectOrNull()
            Stove(
                id = id,
                name = device.string("name")?.takeIf { it.isNotBlank() } ?: device.string("mac") ?: id,
                serial = device.string("product_serial"),
                model = device.string("product_model") ?: device.string("model"),
                macAddress = device.string("mac"),
                online = device.boolean("is_online") ?: device.boolean("online") ?: true,
                productId = device.string("id_product").orEmpty(),
                deviceId = device.string("id_device").orEmpty(),
                registrationNumber = productInfo?.string("registration_number")
                    ?: device.string("product_serial").orEmpty(),
            )
        }
        cachedStoves = stoves
        return stoves
    }

    private fun stove(stoveId: String): Stove =
        cachedStoves.firstOrNull { it.id == stoveId }
            ?: throw AguaException.InvalidResponse("unknown stove " + stoveId)

    override suspend fun readSnapshot(stoveId: String): StoveSnapshot {
        val stove = stove(stoveId)
        val map = registerMap(stove)
        val response = call(
            action = "deviceGetBufferReading",
            payload = buildJsonObject {
                put("id_product", stove.productId)
                put("id_device", stove.deviceId)
                put("BufferId", DEFAULT_BUFFER_ID)
                put("registration_number", stove.registrationNumber)
            },
        )
        val answer = resolveJobAnswer(response)
        return buildSnapshot(stove, map, answer)
    }

    override suspend fun sendSetting(stoveId: String, setting: StoveSetting) {
        if (readOnly) throw AguaException.ReadOnly()
        val stove = stove(stoveId)
        val map = registerMap(stove)
        val writes = encodeWrites(map, setting)
        if (writes.isNullOrEmpty()) throw AguaException.UnsupportedSetting(setting.displayKey)
        val response = call(
            action = "deviceRequestWriting",
            payload = buildJsonObject {
                put("id_product", stove.productId)
                put("id_device", stove.deviceId)
                put("Protocol", "RWMSmaster")
                put("BitData", 8)
                put("Endianess", "L")
                put("registration_number", stove.registrationNumber)
                put("Items", buildJsonArray { writes.forEach { add(JsonPrimitive(it.definition.offset)) } })
                put("Masks", buildJsonArray { writes.forEach { add(JsonPrimitive(it.definition.mask)) } })
                put("Values", buildJsonArray { writes.forEach { add(JsonPrimitive(it.rawValue)) } })
                put("Endianess", buildJsonArray { writes.forEach { add(JsonPrimitive(it.definition.endianess)) } })
                put("BitData", buildJsonArray { writes.forEach { add(JsonPrimitive(it.definition.bitData)) } })
            },
        )
        // Unlike the stock app, the job answer is awaited: a write the stove never performed must
        // surface as an error, never as a silent success.
        resolveJobAnswer(response)
    }

    override suspend fun send(stoveId: String, setting: StoveSetting) = sendSetting(stoveId, setting)

    override suspend fun read(stoveId: String): StoveSnapshot = readSnapshot(stoveId)

    // ---------------------------------------------------------------- jobs

    private suspend fun resolveJobAnswer(response: JsonObject): JsonObject {
        val inline = response["jobAnswerData"].asObjectOrNull()
        if (inline != null && (inline["Values"] != null)) return inline
        val idRequest = response.string("idRequest")
            ?: return inline ?: throw AguaException.InvalidResponse(
                "the bridge accepted the action but returned no job ticket",
            )
        return pollJob(idRequest)
    }

    private suspend fun pollJob(idRequest: String): JsonObject {
        val deadline = clock() + jobTimeoutMillis
        var lastStatus: String? = null
        while (clock() < deadline) {
            val response = call(
                action = "deviceJobStatus",
                payload = JsonObject(emptyMap()),
                method = "GET",
                affix = "/" + idRequest,
            )
            val status = response.string("jobAnswerStatus")?.lowercase()
            lastStatus = status
            when (status) {
                "completed" -> {
                    val answer = response["jobAnswerData"].asObjectOrNull()
                        ?: throw AguaException.InvalidResponse("completed job without jobAnswerData")
                    return answer
                }
                "terminated" -> throw AguaException.StoveRejected("job terminated by the stove")
                "waiting", "missing job answer", null -> delay(jobPollIntervalMillis)
                else -> throw AguaException.StoveRejected("unexpected job status " + status)
            }
        }
        throw AguaException.StoveRejected("stove did not answer in time (last status " + lastStatus + ")")
    }

    // ---------------------------------------------------------------- register map

    private suspend fun registerMap(stove: Stove): DeviceRegisterMap {
        registerMaps[stove.id]?.let { return it }
        val response = call(
            action = "deviceGetRegistersMap",
            payload = buildJsonObject {
                put("id_product", stove.productId)
                put("id_device", stove.deviceId)
                put("last_update", null as String?)
                put("registration_number", stove.registrationNumber)
            },
        )
        val mapNode = response["device_registers_map"]
            .asObjectOrNull()
            ?.get("registers_map")
            .asArrayOrNull()
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?: throw AguaException.InvalidResponse("deviceGetRegistersMap returned no register map")
        val parsed = DeviceRegisterMap.parse(mapNode)
        registerMaps[stove.id] = parsed
        return parsed
    }

    // ---------------------------------------------------------------- snapshot

    private fun buildSnapshot(stove: Stove, map: DeviceRegisterMap, answer: JsonObject): StoveSnapshot {
        val values = parseValues(answer)
        if (values.isEmpty()) {
            throw AguaException.InvalidResponse("register answer without values")
        }
        if (values.values.all { it == INACTIVITY_CODE }) {
            throw AguaException.StoveOffline(stove.id)
        }
        val language = languageTag()

        val statusDef = map.main(KEY_STATUS)
        val rawStatus = statusDef?.let { values[it.offset] }
        val alarmCodes = map.group(KEY_ALARMS)?.main?.enabledValues.orEmpty()
        val offStates = map.group(KEY_STATUS_MANAGED_ON)?.main?.enabledValues.orEmpty()
        val onStates = map.group(KEY_STATUS_MANAGED_OFF)?.main?.enabledValues.orEmpty()

        val state = when {
            rawStatus != null && alarmCodes.contains(rawStatus) -> StoveOperatingState.ALARM
            rawStatus != null && offStates.contains(rawStatus) -> StoveOperatingState.OFF
            rawStatus != null && onStates.contains(rawStatus) -> StoveOperatingState.ON
            else -> StoveOperatingState.UNKNOWN
        }

        val alarmsGet = map.group(KEY_ALARMS)?.get
        val rawAlarm = alarmsGet?.let { values[it.offset] }

        val fanGroup = map.findByKeys(FAN_KEYS)
        val setpointGroup = map.findByKeys(SETPOINT_KEYS)
        val powerGroup = map.findByKeys(POWER_KEYS)
        val ecoGroup = ecoSettingGroup(map)
        val roomGroup = temperatureGroup(map)

        val roomTemperature = readDecoded(roomGroup, values)
        val details = readDetails(map, values, language)

        val capabilities = StoveCapabilities(
            canPower = map.group(KEY_STATUS_MANAGED) != null && statusDef != null,
            fanSpeedRange = rangeOf(fanGroup),
            setpointRange = rangeOf(setpointGroup),
            powerLevelRange = rangeOf(powerGroup),
            canEco = ecoGroup != null,
            // Natural convection exists only when the map carries the settable record.
            // The stock app writes this register through its main record even when it is not typed
            // SET, so requiring a SET record hid the control on real stoves.
            canNaturalMode = map.group(KEY_NATURAL_MODE)?.main != null,
            ductRightRange = details.ductRightRange,
            ductLeftRange = details.ductLeftRange,
            multifireRange = details.multifireRange,
            qualityRange = qualityRange(map),
        )

        val ecoEnabled = readRaw(ecoGroup, values)?.let { it != 0 }

        val rawRegisters = LinkedHashMap<String, Int>()
        for (group in map.groups.values) {
            val definition = group.main ?: continue
            values[definition.offset]?.let { rawRegisters[group.key] = it }
        }

        // Diagnostics: the exact register map this stove exposes, so values can be verified.
        android.util.Log.d("SpiazzettaRegisters", "stove " + stove.id + " groups=" + map.groups.keys.joinToString(","))
        for (group in map.groups.values) {
            val definition = group.main ?: continue
            val raw = values[definition.offset] ?: continue
            android.util.Log.d(
                "SpiazzettaRegisters",
                group.key + " | off=" + definition.offset + " | mask=" + definition.mask + " | raw=" + raw +
                    " | formula=" + definition.formula + "/" + definition.formulaInverse +
                    " | decoded=" + definition.unbake(raw) +
                    " | word=" + definition.isWord + " | bigEndian=" + definition.bigEndian +
                    " | temp=" + definition.isTemperature + " | min=" + definition.setMin + " | max=" + definition.setMax +
                    " | label=" + (definition.labelFor(raw, language) ?: "-"),
            )
        }
        android.util.Log.d(
            "SpiazzettaRegisters",
            "roomGroup=" + (roomGroup?.key ?: "none") + " fanGroup=" + (fanGroup?.key ?: "none") +
                " setpointGroup=" + (setpointGroup?.key ?: "none") + " powerGroup=" + (powerGroup?.key ?: "none") +
                " ecoGroup=" + (ecoGroup?.key ?: "none"),
        )

        return StoveSnapshot(
            stoveId = stove.id,
            state = state,
            rawStateCode = rawStatus,
            stateLabel = rawStatus?.let { statusDef?.labelFor(it, language) },
            alarmCode = rawAlarm,
            alarmLabel = rawAlarm?.let { alarmsGet?.labelFor(it, language) },
            roomTemperature = roomTemperature,
            setpoint = readInteger(setpointGroup, values),
            fanSpeed = readInteger(fanGroup, values),
            powerLevel = readInteger(powerGroup, values),
            ecoEnabled = ecoEnabled,
            capabilities = capabilities,
            details = details,
            receivedAtMillis = clock(),
            rawRegisters = rawRegisters,
            registers = registerInventory(map, values, language),
        )
    }

    /** Every register of the map with its current raw value, so any stove can be inspected and exported. */
    private fun registerInventory(
        map: DeviceRegisterMap,
        values: Map<Int, Int>,
        language: String,
    ): List<RegisterEntry> {
        val entries = ArrayList<RegisterEntry>(map.groups.size)
        for (group in map.groups.values) {
            for (definition in group.byType.values) {
                val raw = values[definition.offset]
                entries += RegisterEntry(
                    group = group.key,
                    type = definition.regType,
                    offset = definition.offset,
                    mask = definition.mask,
                    raw = raw,
                    decoded = raw?.let { definition.unbake(it) },
                    label = raw?.let { definition.labelFor(it, language) },
                    writable = group.byType.containsKey(RegisterGroup.TYPE_SET),
                )
            }
        }
        return entries.sortedWith(compareBy({ it.offset }, { it.type }))
    }

    /** Pellet-quality correction, stored by the stove as a value whose middle means "no correction". */
    private fun qualityRange(map: DeviceRegisterMap): IntRange? {
        val definition = writeDefinition(map.group(KEY_QUALITY_LOW))
            ?: writeDefinition(map.group(KEY_QUALITY_HIGH))
            ?: return null
        val min = definition.setMin ?: return null
        val max = definition.setMax ?: return null
        if (max < min) return null
        val middle = (min + max) / 2
        return (min - middle)..(max - middle)
    }

    private fun qualityValue(map: DeviceRegisterMap, values: Map<Int, Int>, key: String): Int? {
        val group = map.group(key) ?: return null
        val raw = readRaw(group, values) ?: return null
        val definition = writeDefinition(group) ?: group.main ?: return null
        val middle = ((definition.setMin ?: 0) + (definition.setMax ?: 10)) / 2
        return raw - middle
    }

    /**
     * Everything the P220M reports beyond state and setpoints, decoded from its own register map:
     * flue gas, the two duct zones, pellet stock, service hours, firmware, natural convection,
     * multifire, external thermostat, clock, warnings and the six weekly programs.
     */
    private fun readDetails(
        map: DeviceRegisterMap,
        values: Map<Int, Int>,
        language: String,
    ): StoveDetails {
        val base = StoveDetails(
            flueTemperature = readDecoded(map.group(KEY_FLUE_TEMPERATURE), values),
            ductRightSetpoint = readInteger(map.group(KEY_DUCT_RIGHT), values),
            ductLeftSetpoint = readInteger(map.group(KEY_DUCT_LEFT), values),
            ductRightRange = rangeOf(map.group(KEY_DUCT_RIGHT)),
            ductLeftRange = rangeOf(map.group(KEY_DUCT_LEFT)),
            pelletLevel = pelletLevel(map, values),
            serviceHours = readInteger(map.group(KEY_SERVICE_HOURS), values),
            serviceThreshold = readInteger(map.group(KEY_SERVICE_THRESHOLD), values),
            firmware = firmware(map, values),
            naturalMode = readDecoded(map.group(KEY_NATURAL_MODE), values)?.let { it != 0.0 },
            multifireLevel = readInteger(map.group(KEY_MULTIFIRE), values),
            multifireRange = rangeOf(map.group(KEY_MULTIFIRE)),
            multifireModality = readLabel(map.group(KEY_MULTIFIRE_MODALITY), values, language),
            externalThermostat = readLabel(map.group(KEY_THERM_EXT), values, language),
            stoveClock = stoveClock(map, values),
        )
        return base.copy(
            warnings = warnings(map, values, base.serviceDue),
            chronoPrograms = chronoPrograms(map, values),
            combustion = CombustionCorrection(
                powerLow = qualityValue(map, values, KEY_QUALITY_LOW),
                powerHigh = qualityValue(map, values, KEY_QUALITY_HIGH),
                range = qualityRange(map),
            ),
        )
    }

    /**
     * The records a read may use for one group: the settable record first, because it is the one
     * carrying the engineering formula and the range, then the plain read record. Both are tried in
     * order and every absent offset is skipped, so a group whose SET offset is not delivered by the
     * buffer still reads through its GET record.
     */
    private fun readCandidates(group: RegisterGroup): List<NativeDef> =
        listOfNotNull(group.byType[RegisterGroup.TYPE_SET], group.get, group.main).distinctBy { it.offset }

    private fun readRaw(group: RegisterGroup?, values: Map<Int, Int>): Int? {
        if (group == null) return null
        for (definition in readCandidates(group)) {
            val raw = values[definition.offset]
            if (raw != null) return raw
        }
        return null
    }

    private fun readDecoded(group: RegisterGroup?, values: Map<Int, Int>): Double? {
        if (group == null) return null
        for (definition in readCandidates(group)) {
            val raw = values[definition.offset]
            if (raw != null) return definition.unbake(raw)
        }
        return null
    }

    private fun readInteger(group: RegisterGroup?, values: Map<Int, Int>): Int? =
        readDecoded(group, values)?.let { Math.round(it).toInt() }

    private fun readLabel(group: RegisterGroup?, values: Map<Int, Int>, language: String): String? {
        if (group == null) return null
        for (definition in readCandidates(group)) {
            val raw = values[definition.offset]
            if (raw != null) return definition.labelFor(raw, language)
        }
        return null
    }

    /**
     * The room-temperature group of this stove. The P220M reports it on temp_air2; temp_room and
     * temp_ambiente are older layouts. sonda_ambiente is a probe channel rather than the room
     * temperature, and a group whose settable range tops out at 1 is a flag, so neither is used.
     */
    private fun temperatureGroup(map: DeviceRegisterMap): RegisterGroup? {
        for (key in ROOM_TEMP_KEYS) {
            val group = map.groups[key] ?: continue
            val definition = group.get ?: group.main ?: continue
            val limit = (group.byType[RegisterGroup.TYPE_SET] ?: definition).setMax ?: definition.setMax
            if (limit != null && limit <= 1) continue
            return group
        }
        return null
    }

    /**
     * The energy-saving flag, by exact key only: multiconfort is the STOVE mode select on this model
     * and must never be surfaced as eco.
     */
    private fun ecoSettingGroup(map: DeviceRegisterMap): RegisterGroup? {
        for (key in ECO_KEYS) {
            val group = map.groups[key] ?: continue
            if (group.byType.containsKey(RegisterGroup.TYPE_SET)) return group
        }
        return null
    }

    /** Pellet stock, from whichever ris_pellet_* flag the stove set (Device.getPelletStockStatus). */
    private fun pelletLevel(map: DeviceRegisterMap, values: Map<Int, Int>): PelletLevel {
        // The flags are bit fields: ris_pellet_empty is raw 16 with formula #/32, i.e. 0.5 when the
        // tank is fine. Only a value that rounds to 1 means the flag is set.
        var status = PelletLevel.UNKNOWN
        for ((key, group) in map.groups) {
            val token = PELLET_LEVEL_KEYS.find(key)?.groupValues?.get(1)?.lowercase() ?: continue
            val definition = group.main ?: continue
            val raw = values[definition.offset] ?: continue
            // 16 with formula #/32 is 0.5: rounding would wrongly count it as set.
            if (definition.unbake(raw) < 1.0) continue
            val level = when {
                token.startsWith("ok") -> PelletLevel.OK
                token.startsWith("ris") -> PelletLevel.LOW
                token.startsWith("empty") -> PelletLevel.EMPTY
                else -> PelletLevel.UNKNOWN
            }
            // A reported "ok" wins over a stale low/empty flag.
            if (status == PelletLevel.UNKNOWN || level == PelletLevel.OK) status = level
        }
        return status
    }

    /** Rebuilt exactly as Device.getFirmware does: FW-<fw1><fw2>-<fw3>-V-<fw4>-<fw5>-<fw6>. */
    private fun firmware(map: DeviceRegisterMap, values: Map<Int, Int>): String? {
        val parts = ArrayList<Int>(FIRMWARE_KEYS.size)
        for (key in FIRMWARE_KEYS) {
            parts += readRaw(map.group(key), values) ?: return null
        }
        val variant = parts[0].toChar().toString() + parts[1].toChar().toString()
        return listOf(
            "FW",
            variant,
            parts[2].toString(),
            "V",
            parts[3].toString(),
            parts[4].toString(),
            parts[5].toString(),
        ).joinToString("-")
    }

    private fun stoveClock(map: DeviceRegisterMap, values: Map<Int, Int>): String? {
        val hour = readRaw(map.group(KEY_CLOCK_HOUR), values) ?: return null
        val minute = readRaw(map.group(KEY_CLOCK_MINUTE), values) ?: return null
        return twoDigits(hour) + ":" + twoDigits(minute)
    }

    /** The three voltage/auger warnings Device.getWarnings raises, plus service due. */
    private fun warnings(map: DeviceRegisterMap, values: Map<Int, Int>, serviceDue: Boolean): List<StoveWarning> {
        val warnings = ArrayList<StoveWarning>()
        if (readRaw(map.group(KEY_POWER_FAILURE), values) == 0) {
            warnings += StoveWarning(StoveWarning.Kind.POWER_FAILURE)
        }
        if (readRaw(map.group(KEY_POWER_FAILURE_RESTART), values) == 0) {
            warnings += StoveWarning(StoveWarning.Kind.POWER_FAILURE_RESTART)
        }
        if (readRaw(map.group(KEY_AUGER_LOAD), values) == 1) {
            warnings += StoveWarning(StoveWarning.Kind.AUGER_LOAD)
        }
        if (serviceDue) warnings += StoveWarning(StoveWarning.Kind.SERVICE_DUE)
        return warnings
    }

    /** The weekly programs, assembled from chrono_pN_* records exactly as Device.getChronos does. */
    private fun chronoPrograms(map: DeviceRegisterMap, values: Map<Int, Int>): List<ChronoProgram> {
        val programs = ArrayList<ChronoProgram>()
        for (index in CHRONO_PROGRAM_INDEXES) {
            val prefix = "chrono_p" + index
            val enableRaw = readRaw(map.group(prefix + "_enable"), values)
            val startRaw = readRaw(map.group(prefix + "_start"), values)
            val stopRaw = readRaw(map.group(prefix + "_stop"), values)
            val temperature = readInteger(map.group(prefix + "_temperature"), values)
            val power = readInteger(map.group(prefix + "_power"), values)
            if (enableRaw == null && startRaw == null && stopRaw == null && temperature == null && power == null) {
                continue
            }
            val enabled = if (enableRaw != null) {
                enableRaw != 0
            } else {
                startRaw != null && startRaw != CHRONO_DISABLED_TIME &&
                    stopRaw != null && stopRaw != CHRONO_DISABLED_TIME
            }
            programs += ChronoProgram(
                index = index,
                enabled = enabled,
                start = chronoTime(startRaw),
                stop = chronoTime(stopRaw),
                temperature = temperature,
                power = power,
                daysMask = chronoDaysMask(map, values, prefix),
            )
        }
        return programs
    }

    private fun chronoDaysMask(map: DeviceRegisterMap, values: Map<Int, Int>, prefix: String): Int {
        var mask = 0
        for (flag in ChronoWeekday.ALL) {
            val definition = map.group(prefix + "_day_" + ChronoWeekday.keyFor(flag))?.main ?: continue
            val raw = values[definition.offset] ?: continue
            if (raw and definition.mask != 0) mask = mask or flag
        }
        return mask
    }

    /** Chrono times are stored in ten-minute units; 144 means unset ("--:--" in the stock app). */
    private fun chronoTime(raw: Int?): String? {
        if (raw == null || raw == CHRONO_DISABLED_TIME) return null
        val minutes = raw * CHRONO_MINUTES_PER_STEP
        val hour = minutes / 60
        if (hour > 23) return null
        return twoDigits(hour) + ":" + twoDigits(minutes % 60)
    }

    private fun twoDigits(value: Int): String = if (value in 0..9) "0" + value else value.toString()

    private fun rangeOf(group: RegisterGroup?): IntRange? {
        val definition = group?.byType?.get(RegisterGroup.TYPE_SET) ?: group?.main ?: return null
        val min = definition.setMin ?: return null
        val max = definition.setMax ?: return null
        if (max < min) return null
        return min..max
    }

    private fun parseValues(answer: JsonObject): Map<Int, Int> {
        val items = answer["Items"].elements()
        val values = answer["Values"].elements()
        val result = LinkedHashMap<Int, Int>()
        for (index in items.indices) {
            val offset = items[index].toIntOrNull() ?: continue
            val value = values.getOrNull(index)?.toIntOrNull() ?: continue
            result[offset] = value
        }
        return result
    }

    // ---------------------------------------------------------------- encoding writes

    private data class EncodedWrite(val definition: NativeDef, val rawValue: Int)

    private fun encodeWrites(map: DeviceRegisterMap, setting: StoveSetting): List<EncodedWrite>? {
        if (setting is StoveSetting.ChronoDays) {
            val prefix = "chrono_p" + setting.program
            return ChronoWeekday.ALL.mapNotNull { flag ->
                val definition = writeDefinition(map.group(prefix + "_day_" + ChronoWeekday.keyFor(flag)))
                    ?: return@mapNotNull null
                val on = setting.mask and flag != 0
                EncodedWrite(definition, definition.bake(if (on) 1 else 0))
            }
        }
        if (setting is StoveSetting.ChronoProgramEdit) {
            val prefix = "chrono_p" + setting.program
            val definition = writeDefinition(map.group(prefix + "_" + setting.chronoField.key)) ?: return null
            return listOf(EncodedWrite(definition, definition.bake(setting.value)))
        }
        return encodeWrite(map, setting)?.let { listOf(it) }
    }

    private fun encodeWrite(map: DeviceRegisterMap, setting: StoveSetting): EncodedWrite? {
        when (setting) {
            is StoveSetting.FanSpeed -> {
                val definition = writeDefinition(map.findByKeys(FAN_KEYS)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.level))
            }
            is StoveSetting.Setpoint -> {
                val definition = writeDefinition(map.findByKeys(SETPOINT_KEYS)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.celsius))
            }
            is StoveSetting.PowerLevel -> {
                val definition = writeDefinition(map.findByKeys(POWER_KEYS)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.level))
            }
            is StoveSetting.Eco -> {
                val definition = writeDefinition(ecoSettingGroup(map)) ?: return null
                val display = if (setting.enabled) (definition.setMax ?: 1) else (definition.setMin ?: 0)
                return EncodedWrite(definition, definition.bake(display))
            }
            is StoveSetting.NaturalMode -> {
                val definition = writeDefinition(map.group(KEY_NATURAL_MODE)) ?: return null
                val display = if (setting.enabled) 1 else 0
                return EncodedWrite(definition, definition.bake(display))
            }
            is StoveSetting.DuctRight -> {
                val definition = writeDefinition(map.group(KEY_DUCT_RIGHT)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.celsius))
            }
            is StoveSetting.DuctLeft -> {
                val definition = writeDefinition(map.group(KEY_DUCT_LEFT)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.celsius))
            }
            is StoveSetting.Multifire -> {
                val definition = writeDefinition(map.group(KEY_MULTIFIRE)) ?: return null
                return EncodedWrite(definition, definition.bake(setting.level))
            }
            is StoveSetting.Powered -> {
                val group = map.group(KEY_STATUS_MANAGED) ?: return null
                val definition = group.get ?: group.main ?: return null
                val token = if (setting.on) "ON" else "OFF"
                val display = definition.valueForLabelToken(token) ?: return null
                return EncodedWrite(definition, definition.bake(display))
            }
            is StoveSetting.QualityCorrection -> {
                val key = if (setting.lowBand) KEY_QUALITY_LOW else KEY_QUALITY_HIGH
                val definition = writeDefinition(map.group(key)) ?: return null
                val middle = ((definition.setMin ?: 0) + (definition.setMax ?: 10)) / 2
                return EncodedWrite(definition, definition.bake(middle + setting.value))
            }
            else -> return null
        }
    }

    private fun writeDefinition(group: RegisterGroup?): NativeDef? =
        group?.byType?.get(RegisterGroup.TYPE_SET) ?: group?.main

    // ---------------------------------------------------------------- transport

    private suspend fun call(
        action: String,
        payload: JsonObject = JsonObject(emptyMap()),
        method: String = "POST",
        affix: String = "",
        authenticated: Boolean = true,
        retryOnExpiry: Boolean = true,
    ): JsonObject {
        val attempt = rawCall(action, payload, method, affix, authenticated)
        if (attempt is CallResult.Failure) {
            val failure = attempt.exception
            if (failure is AguaException.SessionExpired && retryOnExpiry && refreshToken != null) {
                refreshSession()
                return call(action, payload, method, affix, authenticated, retryOnExpiry = false)
            }
            throw failure
        }
        val body = (attempt as CallResult.Success).body
        val success = body.boolean("Success") ?: body.string("Success")?.toBooleanStrictOrNull() ?: false
        if (success) return body
        val code = body.string("Error")
        // Visible in logcat so a failed login can be diagnosed without guesswork.
        android.util.Log.d(
            "SpiazzettaBridge",
            action + " failed: " + code + " " + body.string("Text") + " (" + method + ")",
        )
        if (code == ERROR_NOT_AUTHORIZED || code == ERROR_INVALID_REFRESH) {
            if (retryOnExpiry && refreshToken != null) {
                refreshSession()
                return call(action, payload, method, affix, authenticated, retryOnExpiry = false)
            }
            throw AguaException.SessionExpired()
        }
        throw AguaException.Bridge(code, body.string("Text"), action)
    }

    private suspend fun refreshSession() {
        val refresh = refreshToken ?: throw AguaException.SessionExpired()
        val response = rawCall(
            action = "refreshToken",
            payload = buildJsonObject { put("refresh_token", refresh) },
            method = "POST",
            affix = "",
            authenticated = true,
        )
        val body = when (response) {
            is CallResult.Success -> response.body
            is CallResult.Failure -> throw response.exception
        }
        token = body.string("token") ?: throw AguaException.SessionExpired()
    }

    private sealed interface CallResult {
        data class Success(val body: JsonObject) : CallResult
        data class Failure(val exception: AguaException) : CallResult
    }

    private suspend fun rawCall(
        action: String,
        payload: JsonObject,
        method: String,
        affix: String,
        authenticated: Boolean,
    ): CallResult = withContext(Dispatchers.IO) {
        val bodyToSend: JsonObject = if (method.equals("GET", ignoreCase = true)) {
            JsonObject(emptyMap())
        } else {
            JsonObject(payload.toMutableMap().apply { put("language", JsonPrimitive(languageTag())) })
        }
        val builder = Request.Builder()
            .url(baseUrl)
            .header("CustomerCode", CUSTOMER_CODE)
            .header("IdBrand", ID_BRAND)
            .header("ApplicationVersion", APP_VERSION)
            .header("brand", BRAND)
            .header("method", method)
            .header("url", action + affix)
            .header("authorization", if (authenticated) (token ?: installationId) else installationId)
            .header("local", if (authenticated && token != null) "false" else "true")
        userId?.let { builder.header("aguaid", it) }
        aguaId?.let { builder.header("userid", it) }
        if (method.equals("GET", ignoreCase = true)) {
            builder.get()
        } else {
            builder.method(method, json.encodeToString(JsonObject.serializer(), bodyToSend).toRequestBody(JSON_MEDIA))
        }
        val request = builder.build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(text).asObjectOrNull() }.getOrNull()
                when {
                    response.code == 401 || response.code == 403 -> CallResult.Failure(AguaException.Unauthorized())
                    parsed == null -> CallResult.Failure(
                        AguaException.InvalidResponse("non JSON answer, HTTP " + response.code),
                    )
                    response.code >= 400 -> {
                        val code = parsed.string("Error")
                        if (code == ERROR_NOT_AUTHORIZED || code == ERROR_INVALID_REFRESH) {
                            CallResult.Failure(AguaException.SessionExpired())
                        } else {
                            CallResult.Failure(AguaException.Server(response.code, text.take(400)))
                        }
                    }
                    else -> CallResult.Success(parsed)
                }
            }
        } catch (io: IOException) {
            CallResult.Failure(AguaException.Network(io))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/"
        const val CUSTOMER_CODE = "458632"
        const val ID_BRAND = "1"
        const val BRAND = "piazzetta"
        const val APP_VERSION = "2.0.0"
        const val DEFAULT_BUFFER_ID = 1
        const val INACTIVITY_CODE = 32768

        private const val ERROR_NOT_AUTHORIZED = "1106"
        private const val ERROR_INVALID_REFRESH = "1116"
        private const val ERROR_USER_NOT_FOUND = "1103"
        private const val ERROR_BAD_CREDENTIALS = "1102"
        private const val ERROR_NOT_ACTIVATED = "1134"
        private const val ERROR_APP_NOT_ACTIVE = "1144"

        private const val KEY_STATUS = "status"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_STATUS_MANAGED = "status_managed"
        private const val KEY_STATUS_MANAGED_ON = "status_managed_on"
        private const val KEY_STATUS_MANAGED_OFF = "status_managed_off"

        // Registers of the P220M captured from its live map (docs/protocol/PROTOCOL.md).
        private const val KEY_FLUE_TEMPERATURE = "temp_gas_flue"
        private const val KEY_DUCT_RIGHT = "canalization_dx"
        private const val KEY_DUCT_LEFT = "canalization_sx"
        private const val KEY_NATURAL_MODE = "natural_mode_new"
        private const val KEY_QUALITY_LOW = "p_quality_p12"
        private const val KEY_QUALITY_HIGH = "c_quality_p12"
        private const val KEY_MULTIFIRE = "multifire_1"
        private const val KEY_MULTIFIRE_MODALITY = "multifire_modality"
        private const val KEY_THERM_EXT = "therm_ext"
        private const val KEY_SERVICE_HOURS = "ore_service"
        private const val KEY_SERVICE_THRESHOLD = "ore_service_soglia"
        private const val KEY_CLOCK_HOUR = "clock_hour"
        private const val KEY_CLOCK_MINUTE = "clock_minute"
        private const val KEY_POWER_FAILURE = "mancata_tensione"
        private const val KEY_POWER_FAILURE_RESTART = "mancata_tensione_riavvio"
        private const val KEY_AUGER_LOAD = "coclea_load"

        private val FIRMWARE_KEYS = listOf("fw_1", "fw_2", "fw_3", "fw_4", "fw_5", "fw_6")
        private val PELLET_LEVEL_KEYS = Regex("^ris_pellet_([a-z]*)$")
        private val CHRONO_PROGRAM_INDEXES = 1..6
        private const val CHRONO_DISABLED_TIME = 144
        private const val CHRONO_MINUTES_PER_STEP = 10

        // Register keys proven present in the shipped app's own lookups: vent_main (fan speed),
        // temp_air (room setpoint), power (power level), status_managed (on/off), status/alarms.
        private val FAN_KEYS = listOf(
            "vent_main", "ventilazione", "ventilation", "fan_speed", "velocita_ventola", "ventola", "fan",
        )
        private val SETPOINT_KEYS = listOf(
            "temp_air", "setpoint", "set_point", "temperatura_set", "temp_set", "temperatura_desiderata",
        )
        private val POWER_KEYS = listOf(
            "power", "potenza", "potenza_fiamma", "power_level", "livello_potenza",
        )
        // multiconfort is the STOVE mode select on this stove, never the energy-saving flag.
        private val ECO_KEYS = listOf("eco", "risparmio_energetico")
        // temp_air2 is where the P220M reports the room temperature; the rest are older layouts.
        private val ROOM_TEMP_KEYS = listOf("temp_air2", "temp_room", "temp_ambiente")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
