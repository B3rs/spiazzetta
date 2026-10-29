package me.bers.spiazzetta.data.agua

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.bers.spiazzetta.domain.AguaException
import me.bers.spiazzetta.domain.ConfirmedWriter
import me.bers.spiazzetta.domain.StoveOperatingState
import me.bers.spiazzetta.domain.StoveSetting
import me.bers.spiazzetta.domain.WriteOutcome
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real HTTP client against a mock Agua bridge that speaks the contract documented in
 * docs/protocol/PROTOCOL.md (action in the "url" header, jobs polled through deviceJobStatus).
 */
class AguaProtocolTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: HttpAguaRepository
    private val writtenPayloads = mutableListOf<JsonObject>()
    private val requests = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()

    /** Fan speed currently reported by the fake stove; a write changes it only when allowed. */
    private var stoveFanSpeed = 2
    private var stoveAcceptsWrites = true
    private var stoveSetpoint = 22
    private var stovePowerLevel = 3
    private var stoveStatus = 3

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val action = request.getHeader("url") ?: ""
                val rawBody = request.body.readUtf8()
                bodies += rawBody
                val body = runCatching {
                    Json.parseToJsonElement(rawBody).jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                return when {
                    action == "userLogin" -> json("""{"Success":true,"token":"tok-1","refresh_token":"ref-1"}""")
                    action == "userDetail" -> json("""{"Success":true,"User":{"id":11,"agua_id":22}}""")
                    action == "deviceList" -> json(DEVICE_LIST)
                    action == "deviceGetRegistersMap" -> json(REGISTER_MAP)
                    action == "deviceGetBufferReading" -> json("""{"Success":true,"idRequest":"job-read"}""")
                    action == "deviceRequestWriting" -> {
                        writtenPayloads += body
                        if (body["Values"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content == stoveFanSpeed.toString()) {
                            // A write that matches the current value: the stove never really changes.
                        }
                        if (stoveAcceptsWrites) {
                            body["Items"]?.jsonArray?.let { items ->
                                val values = body["Values"]!!.jsonArray
                                for (index in items.indices) {
                                    val offset = items[index].jsonPrimitive.content.toInt()
                                    val value = values[index].jsonPrimitive.content.toInt()
                                    when (offset) {
                                        FAN_OFFSET -> stoveFanSpeed = value
                                        SETPOINT_OFFSET -> stoveSetpoint = value
                                        POWER_OFFSET -> stovePowerLevel = value
                                    }
                                }
                            }
                        }
                        json("""{"Success":true,"idRequest":"job-write"}""")
                    }
                    action.startsWith("deviceJobStatus") -> json(jobAnswer())
                    else -> json("""{"Success":false,"Error":"9999"}""")
                }
            }
        }
        server.start()
        repository = HttpAguaRepository(
            baseUrl = server.url("/api/bridge/endpoint/").toString(),
            languageTag = { "it" },
            jobPollIntervalMillis = 1,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginReturnsTheStovesOfTheAccount() = runBlocking {
        val stoves = repository.login("user@example.com", "secret")

        assertEquals(1, stoves.size)
        assertEquals("Stufa soggiorno", stoves[0].name)
        assertEquals("P123456", stoves[0].registrationNumber)

        val loginIndex = requests.indexOfFirst { it.getHeader("url") == "userLogin" }
        val loginRequest = requests[loginIndex]
        assertEquals("458632", loginRequest.getHeader("CustomerCode"))
        assertEquals("1", loginRequest.getHeader("IdBrand"))
        assertEquals("true", loginRequest.getHeader("local"))
        assertEquals("piazzetta", loginRequest.getHeader("brand"))
        val loginBody = bodies[loginIndex]
        assertTrue(loginBody.contains("user@example.com"))
        assertTrue("login body was: " + loginBody, loginBody.contains("\"language\":\"it\""))
    }

    @Test
    fun readSnapshotDecodesServerRegisters() = runBlocking {
        repository.login("user@example.com", "secret")
        val snapshot = repository.readSnapshot("12345")

        assertEquals(StoveOperatingState.ON, snapshot.state)
        assertEquals("In funzione", snapshot.stateLabel)
        assertEquals(2, snapshot.fanSpeed)
        assertEquals(22, snapshot.setpoint)
        assertEquals(3, snapshot.powerLevel)
        assertEquals(21.5, snapshot.roomTemperature!!, 0.01)
        assertEquals(1..5, snapshot.capabilities.fanSpeedRange)
        assertEquals(16..30, snapshot.capabilities.setpointRange)
    }

    @Test
    fun fanSpeedWriteSendsTheExactRegisterPayload() = runBlocking {
        repository.login("user@example.com", "secret")
        repository.sendSetting("12345", StoveSetting.FanSpeed(4))

        assertEquals(1, writtenPayloads.size)
        val payload = writtenPayloads.single()
        assertEquals(FAN_OFFSET.toString(), payload["Items"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(4.toString(), payload["Values"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("255", payload["Masks"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("L", payload["Endianess"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("8", payload["BitData"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("RWMSmaster", payload["Protocol"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWriteTheStoveIgnoresIsRetriedAndReportedAsFailure() = runBlocking {
        repository.login("user@example.com", "secret")
        stoveAcceptsWrites = false
        val writer = ConfirmedWriter(repository, attempts = 3, pause = { })

        val outcome = writer.write("12345", StoveSetting.FanSpeed(4))

        assertTrue("write must never be silently accepted", outcome is WriteOutcome.NotConfirmed)
        val notConfirmed = outcome as WriteOutcome.NotConfirmed
        assertEquals(3, notConfirmed.attempts)
        assertEquals(WriteOutcome.NotConfirmed.Cause.READ_BACK_MISMATCH, notConfirmed.cause)
        assertEquals(3, writtenPayloads.size)
    }

    @Test
    fun aWriteTheStoveAppliesIsReportedAsConfirmed() = runBlocking {
        repository.login("user@example.com", "secret")
        val writer = ConfirmedWriter(repository, attempts = 3, pause = { })

        val outcome = writer.write("12345", StoveSetting.FanSpeed(1))

        assertTrue(outcome is WriteOutcome.Confirmed)
        val confirmed = outcome as WriteOutcome.Confirmed
        assertEquals(1, confirmed.snapshot.fanSpeed)
        assertEquals(1, confirmed.attempts)
    }

    @Test
    fun unauthorizedLoginIsNotSwallowed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                json("""{"Success":false,"Error":"1134"}""")
        }
        val failure = runCatching { repository.login("user@example.com", "wrong") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is AguaException)
    }

    @Test
    fun anUnknownAccountIsReportedAsABadLoginNotAsAStoveRefusal() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = json(
                """{"Error":"1103","Text":"LOGIN_USER_NOT_FOUND","Success":false,"Source":"BRIDGE"}""",
            )
        }
        val failure = runCatching { repository.login("nobody@example.com", "secret") }.exceptionOrNull()
        assertTrue("expected Unauthorized, got " + failure, failure is AguaException.Unauthorized)
    }

    @Test
    fun aWriteWithoutAJobTicketIsNotReportedAsSuccess() = runBlocking {
        repository.login("user@example.com", "secret")
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("url") == "deviceRequestWriting") {
                    json("""{"Success":true}""")
                } else {
                    original.dispatch(request)
                }
        }
        val failure = runCatching { repository.sendSetting("12345", StoveSetting.FanSpeed(2)) }.exceptionOrNull()
        assertTrue("expected InvalidResponse, got " + failure, failure is AguaException.InvalidResponse)
    }

    @Test
    fun readOnlyModeRefusesToSendAnything() = runBlocking {
        val guarded = HttpAguaRepository(
            baseUrl = server.url("/api/bridge/endpoint/").toString(),
            languageTag = { "it" },
            jobPollIntervalMillis = 1,
            readOnly = true,
        )
        guarded.login("user@example.com", "secret")
        val requestsBefore = requests.size

        val failure = runCatching { guarded.sendSetting("12345", StoveSetting.FanSpeed(3)) }.exceptionOrNull()

        assertTrue("expected ReadOnly, got " + failure, failure is AguaException.ReadOnly)
        assertEquals("no write request may leave the device", requestsBefore, requests.size)
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun jobAnswer(): String {
        val status = if (repository.hasSession()) stoveStatus else 3
        return """
            {"Success":true,"jobAnswerStatus":"completed","jobAnswerData":{
              "Items":[1,2,3,$FAN_OFFSET,$SETPOINT_OFFSET,$POWER_OFFSET],
              "Values":[$status,1,215,$stoveFanSpeed,$stoveSetpoint,$stovePowerLevel]}}
        """.trimIndent()
    }

    private companion object {
        const val FAN_OFFSET = 100
        const val SETPOINT_OFFSET = 101
        const val POWER_OFFSET = 102

        val DEVICE_LIST = """
            {"Success":true,"device":[{"id":"12345","id_device":"77","id_product":"42","id_client":"mypiazzetta",
             "mac":"AA:BB:CC:DD:EE:FF","product_serial":"P123456","name":"Stufa soggiorno","is_managed":true,
             "product_info":{"registration_number":"P123456"}}]}
        """.trimIndent()

        val REGISTER_MAP = """
            {"Success":true,"device_registers_map":{"registers_map":[{"registers":[
              {"reg_key_group":"status","reg_key":"status_get","reg_type":"GET","offset":1,"mask":255,
               "formula":"#","formula_inverse":"#","is_word":false,"big_endian":false,
               "enc_val":[{"value":0,"lang":"it_IT","description":"Spenta"},
                          {"value":3,"lang":"it_IT","description":"In funzione"}]},
              {"reg_key_group":"status_managed","reg_key":"status_managed_get","reg_type":"GET","offset":2,"mask":255,
               "formula":"#","formula_inverse":"#",
               "enc_val":[{"value":1,"lang":"it_IT","description":"ON"},
                          {"value":0,"lang":"it_IT","description":"OFF"}]},
              {"reg_key_group":"status_managed_on","reg_key":"status_managed_on_enable","reg_type":"ENABLE",
               "offset":2,"mask":255,"formula":"#","formula_inverse":"#","enable_val":[{"value":0}]},
              {"reg_key_group":"status_managed_off","reg_key":"status_managed_off_enable","reg_type":"ENABLE",
               "offset":2,"mask":255,"formula":"#","formula_inverse":"#","enable_val":[{"value":3}]},
              {"reg_key_group":"alarms","reg_key":"alarms_enable","reg_type":"ENABLE","offset":9,"mask":255,
               "formula":"#","formula_inverse":"#","enable_val":[{"value":10}]},
              {"reg_key_group":"temp_room","reg_key":"temp_room_get","reg_type":"GET","offset":3,"mask":65535,
               "formula":"#/10","formula_inverse":"#*10","is_word":true,"big_endian":false,"is_temperature":true},
              {"reg_key_group":"vent_main","reg_key":"vent_main_set","reg_type":"SET","offset":100,"mask":255,
               "formula":"#","formula_inverse":"#","set_min":1,"set_max":5,"step":1},
              {"reg_key_group":"temp_air","reg_key":"temp_air_get","reg_type":"GET","offset":101,"mask":65535,
               "formula":"#","formula_inverse":"#","is_word":true},
              {"reg_key_group":"temp_air","reg_key":"temp_air_set","reg_type":"SET","offset":101,"mask":65535,
               "formula":"#","formula_inverse":"#","is_word":true,"set_min":16,"set_max":30,"step":1},
              {"reg_key_group":"power","reg_key":"power_set","reg_type":"SET","offset":102,"mask":255,
               "formula":"#","formula_inverse":"#","set_min":1,"set_max":4,"step":1}
            ]}]}}
        """.trimIndent()
    }
}
