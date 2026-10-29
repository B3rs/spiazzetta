package me.bers.spiazzetta.data.agua

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.bers.spiazzetta.domain.PelletLevel
import me.bers.spiazzetta.domain.StoveSetting
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays the register map captured from a real Piazzetta P220M (evidence/30-register-map.txt),
 * including the traps that broke the first build: sonda_ambiente is a 0/1 probe switch, not a
 * temperature, and the room probe lives on temp_air2 with a /2 formula.
 */
class P220MFeaturesTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: HttpAguaRepository
    private val writtenPayloads = mutableListOf<JsonObject>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.getHeader("url") ?: ""
                val body = runCatching {
                    Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                return when {
                    action == "appSignup" -> json("""{"Success":true,"id_app":"app-1"}""")
                    action == "userLogin" -> json("""{"Success":true,"token":"tok","refresh_token":"ref"}""")
                    action == "userDetail" -> json("""{"Success":true,"User":{"id":1,"agua_id":2}}""")
                    action == "deviceList" -> json(DEVICE_LIST)
                    action == "deviceGetRegistersMap" -> json(REGISTER_MAP)
                    action == "deviceGetBufferReading" -> json("""{"Success":true,"idRequest":"read-1"}""")
                    action == "deviceRequestWriting" -> {
                        writtenPayloads += body
                        json("""{"Success":true,"idRequest":"write-1"}""")
                    }
                    action.startsWith("deviceJobStatus") ->
                        json("""{"Success":true,"jobAnswerStatus":"completed","jobAnswerData":$ANSWER}""")
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
    fun tearDown() = server.shutdown()

    @Test
    fun roomTemperatureComesFromTempAir2AndIgnoresTheProbeSwitch() = runBlocking {
        repository.login("user@example.com", "secret")
        val snapshot = repository.readSnapshot("p220m")

        // temp_air2 raw 43 with #/2 -> 21.5; sonda_ambiente raw 1 must never be read as degrees.
        assertEquals(21.5, snapshot.roomTemperature!!, 0.01)
        assertTrue("1.0 °C is the bug this test locks down", snapshot.roomTemperature!! > 5.0)
        assertEquals(21, snapshot.setpoint)
    }

    @Test
    fun theStoveDetailsAreParsedFromItsOwnMap() = runBlocking {
        repository.login("user@example.com", "secret")
        val details = repository.readSnapshot("p220m").details

        assertEquals(30.0, details.flueTemperature!!, 0.01)
        assertEquals(true, details.naturalMode)
        assertEquals(20, details.ductRightSetpoint)
        assertEquals(19, details.ductLeftSetpoint)
        assertEquals(7..30, details.ductRightRange)
        assertEquals(1, details.multifireLevel)
        assertEquals(0..3, details.multifireRange)
        assertEquals(PelletLevel.OK, details.pelletLevel)
        assertEquals(450, details.serviceHours)
        assertEquals(500, details.serviceThreshold)
        assertNotNull(details.firmware)
    }

    @Test
    fun thisStoveHasNoFanSpeedAndNoFakeEco() = runBlocking {
        repository.login("user@example.com", "secret")
        val capabilities = repository.readSnapshot("p220m").capabilities

        assertNull("the P220M exposes no ventilation register", capabilities.fanSpeedRange)
        assertTrue(capabilities.canNaturalMode)
        assertTrue(capabilities.canPower)
        assertTrue("multiconfort is the STOVE mode, never eco", !capabilities.canEco)
        assertTrue("no eco register on this stove", repository.readSnapshot("p220m").ecoEnabled == null)
    }

    @Test
    fun naturalModeIsWrittenAsOneTimesSixteen() = runBlocking {
        repository.login("user@example.com", "secret")
        repository.sendSetting("p220m", StoveSetting.NaturalMode(true))

        val payload = writtenPayloads.single()
        assertEquals("32836", payload["Items"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("16", payload["Values"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("255", payload["Masks"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("L", payload["Endianess"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("8", payload["BitData"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun aDuctSetpointIsWrittenToItsOwnRegister() = runBlocking {
        repository.login("user@example.com", "secret")
        repository.sendSetting("p220m", StoveSetting.DuctRight(23))

        val payload = writtenPayloads.single()
        assertEquals("32893", payload["Items"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("23", payload["Values"]!!.jsonArray[0].jsonPrimitive.content)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        val DEVICE_LIST = """{"Success":true,"device":[{"id":"p220m","id_device":"77","id_product":"42",
            "mac":"AA:BB:CC:DD:EE:FF","product_serial":"P220M0001","name":"P220M",
            "product_info":{"registration_number":"P220M0001"}}]}"""

        /** Values exactly as the stove reported them, keyed by register offset. */
        val ANSWER = """{"Items":[33,232,51,1,68,33041,2,32893,33025,32836,32897,33001,33002,33010,33011,60,61,62,63,64,65],
            "Values":[0,1,0,42,43,1,0,20,19,18,1,1,16,450,500,70,87,49,86,50,51]}"""

        val REGISTER_MAP = """{"Success":true,"device_registers_map":{"registers_map":[{"registers":[
          {"reg_key_group":"status","reg_key":"status_get","reg_type":"GET","offset":33,"mask":255,"formula":"#","formula_inverse":"#",
           "enc_val":[{"value":0,"lang":"it_IT","description":"OFF"},{"value":3,"lang":"it_IT","description":"In funzione"}]},
          {"reg_key_group":"status_managed","reg_key":"status_managed_get","reg_type":"GET","offset":232,"mask":255,"formula":"#","formula_inverse":"#",
           "enc_val":[{"value":1,"lang":"it_IT","description":"ON"},{"value":0,"lang":"it_IT","description":"OFF"}]},
          {"reg_key_group":"status_managed_on","reg_key":"status_managed_on_enable","reg_type":"ENABLE","offset":33,"mask":255,"formula":"#","formula_inverse":"#","enable_val":[{"value":0}]},
          {"reg_key_group":"status_managed_off","reg_key":"status_managed_off_enable","reg_type":"ENABLE","offset":33,"mask":255,"formula":"#","formula_inverse":"#","enable_val":[{"value":3}]},
          {"reg_key_group":"alarms","reg_key":"alarms_enable","reg_type":"ENABLE","offset":51,"mask":255,"formula":"#","formula_inverse":"#","enable_val":[{"value":10}]},
          {"reg_key_group":"alarms","reg_key":"alarms_get","reg_type":"GET","offset":51,"mask":255,"formula":"#","formula_inverse":"#",
           "enc_val":[{"value":0,"lang":"it_IT","description":"NO AL"}]},
          {"reg_key_group":"temp_air","reg_key":"temp_air_get","reg_type":"GET","offset":1,"mask":255,"formula":"#/2","formula_inverse":"#*2","is_temperature":true},
          {"reg_key_group":"temp_air","reg_key":"temp_air_set","reg_type":"SET","offset":1,"mask":255,"formula":"#/2","formula_inverse":"#*2","is_temperature":true,"set_min":7,"set_max":30},
          {"reg_key_group":"temp_air2","reg_key":"temp_air2_get","reg_type":"GET","offset":68,"mask":255,"formula":"#/2","formula_inverse":"#*2"},
          {"reg_key_group":"sonda_ambiente","reg_key":"sonda_ambiente_get","reg_type":"GET","offset":33041,"mask":255,"formula":"#","formula_inverse":"#",
           "enc_val":[{"value":1,"lang":"it_IT","description":"ON"}]},
          {"reg_key_group":"temp_gas_flue","reg_key":"temp_gas_flue_get","reg_type":"GET","offset":2,"mask":255,"formula":"#+30","formula_inverse":"#-30","is_temperature":true},
          {"reg_key_group":"canalization_dx","reg_key":"canalization_dx_set","reg_type":"SET","offset":32893,"mask":255,"formula":"#","formula_inverse":"#","set_min":7,"set_max":30},
          {"reg_key_group":"canalization_sx","reg_key":"canalization_sx_set","reg_type":"SET","offset":33025,"mask":255,"formula":"#","formula_inverse":"#","set_min":7,"set_max":30},
          {"reg_key_group":"natural_mode_new","reg_key":"natural_mode_new_set","reg_type":"SET","offset":32836,"mask":255,"formula":"#/16","formula_inverse":"#*16","set_min":0,"set_max":1},
          {"reg_key_group":"multifire_1","reg_key":"multifire_1_set","reg_type":"SET","offset":32897,"mask":255,"formula":"#","formula_inverse":"#","set_min":0,"set_max":3},
          {"reg_key_group":"multiconfort","reg_key":"multiconfort_set","reg_type":"SET","offset":32838,"mask":255,"formula":"#","formula_inverse":"#","set_min":0,"set_max":1,
           "enc_val":[{"value":0,"lang":"it_IT","description":"STOVE"}]},
          {"reg_key_group":"ris_pellet_ok","reg_key":"ris_pellet_ok_get","reg_type":"GET","offset":33001,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"ris_pellet_empty","reg_key":"ris_pellet_empty_get","reg_type":"GET","offset":33002,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"ore_service","reg_key":"ore_service_get","reg_type":"GET","offset":33010,"mask":65535,"formula":"#","formula_inverse":"#","is_word":true},
          {"reg_key_group":"ore_service_soglia","reg_key":"ore_service_soglia_get","reg_type":"GET","offset":33011,"mask":65535,"formula":"#","formula_inverse":"#","is_word":true},
          {"reg_key_group":"fw_1","reg_key":"fw_1_get","reg_type":"GET","offset":60,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"fw_2","reg_key":"fw_2_get","reg_type":"GET","offset":61,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"fw_3","reg_key":"fw_3_get","reg_type":"GET","offset":62,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"fw_4","reg_key":"fw_4_get","reg_type":"GET","offset":63,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"fw_5","reg_key":"fw_5_get","reg_type":"GET","offset":64,"mask":255,"formula":"#","formula_inverse":"#"},
          {"reg_key_group":"fw_6","reg_key":"fw_6_get","reg_type":"GET","offset":65,"mask":255,"formula":"#","formula_inverse":"#"}
        ]}]}}"""
    }
}
