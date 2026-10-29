package me.bers.spiazzetta.data.agua

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** One translated value the cloud attaches to a register (e.g. status code 5 -> "Pulizia"). */
data class EncodedValue(val value: Int, val lang: String, val description: String)

/**
 * A single native register definition as delivered by deviceGetRegistersMap.
 * Field names mirror the wire names so the mapping stays auditable against docs/protocol/PROTOCOL.md.
 */
data class NativeDef(
    val offset: Int,
    val mask: Int,
    val regType: String,
    val regKey: String,
    val regKeyGroup: String,
    val setMin: Int?,
    val setMax: Int?,
    val step: Int?,
    val bigEndian: Boolean,
    val isWord: Boolean,
    val isHex: Boolean,
    val isTemperature: Boolean,
    val formula: String,
    val formulaInverse: String,
    val formatString: String?,
    val displayOrder: Int,
    val encodedValues: List<EncodedValue>,
    val enabledValues: List<Int>,
) {
    /** Raw integer the stove needs for a user-facing value, per the reverse-engineered encoding. */
    fun bake(displayValue: Int): Int {
        val computed = Formula.evaluate(formulaInverse, displayValue)
        return Math.round(computed).toInt()
    }

    /** Converts a raw register value into the number the UI shows. */
    fun unbake(raw: Int): Double = Formula.evaluate(formula, raw)

    fun labelFor(raw: Int, languageTag: String): String? {
        val matching = encodedValues.filter { it.value == raw }
        if (matching.isEmpty()) return null
        val language = languageTag.lowercase().substringBefore('-')
        return matching.firstOrNull { it.lang.lowercase().startsWith(language) }?.description
            ?: matching.firstOrNull()?.description
    }

    /** The raw value whose translated label is ON/OFF, as the stock app resolves the power button. */
    fun valueForLabelToken(token: String): Int? =
        encodedValues.firstOrNull { it.description.trim().equals(token, ignoreCase = true) }?.value

    val endianess: String get() = if (bigEndian) "B" else "L"
    val bitData: Int get() = if (isWord) 16 else 8
}

/** All registers of one logical group (status, alarms, sonda_ambiente, ...). */
data class RegisterGroup(val key: String, val byType: Map<String, NativeDef>) {
    val main: NativeDef? get() = byType[TYPE_GET] ?: byType[TYPE_SET] ?: byType[TYPE_ENABLE]
    val get: NativeDef? get() = byType[TYPE_GET]

    companion object {
        const val TYPE_GET = "GET"
        const val TYPE_SET = "SET"
        const val TYPE_MIN = "MIN"
        const val TYPE_MAX = "MAX"
        const val TYPE_ENABLE = "ENABLE"
    }
}

/** The register map of one product, grouped by register key, exactly as the stock app builds it. */
data class DeviceRegisterMap(val groups: Map<String, RegisterGroup>) {

    fun group(key: String): RegisterGroup? = groups[key]

    fun main(key: String): NativeDef? = groups[key]?.main

    /** Finds the first group matching a candidate key exactly, then by substring. */
    fun findByKeys(candidates: List<String>): RegisterGroup? {
        for (candidate in candidates) {
            groups[candidate]?.let { return it }
        }
        for (group in groups.values) {
            val key = group.key.lowercase()
            if (candidates.any { key.contains(it) }) return group
        }
        return null
    }

    companion object {
        fun parse(json: JsonElement?): DeviceRegisterMap {
            val registers = json.asObjectOrNull()?.get("registers").asArrayOrNull()
                ?: return DeviceRegisterMap(emptyMap())
            val grouped = LinkedHashMap<String, MutableMap<String, NativeDef>>()
            for (element in registers) {
                val definition = parseDef(element.asObjectOrNull() ?: continue) ?: continue
                grouped.getOrPut(definition.regKeyGroup) { LinkedHashMap() }[definition.regType] = definition
            }
            val groups = LinkedHashMap<String, RegisterGroup>()
            for ((key, value) in grouped) groups[key] = RegisterGroup(key, value)
            return DeviceRegisterMap(groups)
        }

        private fun parseDef(obj: JsonObject): NativeDef? {
            val group = obj.string("reg_key_group") ?: return null
            return NativeDef(
                offset = obj.int("offset") ?: 0,
                mask = obj.int("mask") ?: 0xFFFF,
                regType = obj.string("reg_type") ?: RegisterGroup.TYPE_GET,
                regKey = obj.string("reg_key") ?: group,
                regKeyGroup = group,
                setMin = obj.int("set_min"),
                setMax = obj.int("set_max"),
                step = obj.int("step"),
                bigEndian = obj.boolean("big_endian") ?: false,
                isWord = obj.boolean("is_word") ?: false,
                isHex = obj.boolean("is_hex") ?: false,
                isTemperature = obj.boolean("is_temperature") ?: false,
                formula = obj.string("formula") ?: "#",
                formulaInverse = obj.string("formula_inverse") ?: "#",
                formatString = obj.string("format_string"),
                displayOrder = obj.int("display_order") ?: 0,
                encodedValues = parseEncodedValues(obj["enc_val"]),
                enabledValues = parseEnabledValues(obj["enable_val"]),
            )
        }

        private fun parseEncodedValues(element: JsonElement?): List<EncodedValue> {
            val array = element.asArrayOrNull() ?: return emptyList()
            return array.mapNotNull { item ->
                val obj = item.asObjectOrNull() ?: return@mapNotNull null
                val value = obj.int("value") ?: return@mapNotNull null
                EncodedValue(
                    value = value,
                    lang = obj.string("lang") ?: "it_IT",
                    description = obj.string("description").orEmpty(),
                )
            }
        }

        private fun parseEnabledValues(element: JsonElement?): List<Int> {
            val array = element.asArrayOrNull() ?: return emptyList()
            return array.mapNotNull { it.asObjectOrNull()?.int("value") }
        }
    }
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject.string(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content
}

internal fun JsonObject.int(key: String): Int? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}

internal fun JsonObject.boolean(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.content.toBooleanStrictOrNull()
}

internal fun JsonElement?.elements(): List<JsonElement> = this.asArrayOrNull() ?: emptyList()

internal fun JsonElement?.toIntOrNull(): Int? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}
