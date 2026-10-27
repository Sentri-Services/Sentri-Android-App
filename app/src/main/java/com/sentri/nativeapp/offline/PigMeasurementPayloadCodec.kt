package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

internal object PigMeasurementPayloadCodec {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is RecordPigWeightsInput -> obj("records" to input.records?.let { JSONArray(it.map { item ->
            obj("pig" to item.pigId, "weight" to item.weight?.toString(), "tag" to item.earTag, "location" to item.locationCode)
        }) }, "total" to input.totalWeight?.toString(), "at" to input.measuredAt)
        is RecordPigBackfatInput -> obj("pig" to input.pigId, "mm" to input.millimetres?.toString(),
            "level" to input.level?.name, "at" to input.measuredAt, "location" to input.locationCode, "remark" to input.remark)
        is RecordPigTemperatureInput -> obj("pig" to input.pigId, "temperature" to input.temperature.toString(),
            "unit" to input.unit.name, "at" to input.measuredAt, "tag" to input.earTag,
            "pen" to input.penLocation, "triage" to input.triageLevel?.name)
        else -> null
    }
    fun decode(operation: String, value: JSONObject): CommandInput? = with(value) {
        fun fields(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
        fun string(key: String) = get(key) as? String ?: error("Invalid measurement string")
        fun nullable(key: String) = get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid measurement string") }
        fun decimal(key: String) = nullable(key)?.let(::BigDecimal)
        when (operation) {
            "RecordPigWeights" -> {
                fields("records", "total", "at")
                val records = if (get("records") == JSONObject.NULL) null else (get("records") as? JSONArray ?: error("Invalid weight records")).let { list ->
                    (0 until list.length()).map { index ->
                        val row = list.get(index) as? JSONObject ?: error("Invalid weight row")
                        require(row.keys().asSequence().toSet() == setOf("pig", "weight", "tag", "location"))
                        fun rowNullable(key: String) = row.get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid weight field") }
                        PigWeightItem(row.get("pig") as? String ?: error("Invalid pig ID"), rowNullable("weight")?.let(::BigDecimal),
                            rowNullable("tag"), rowNullable("location"))
                    }
                }
                RecordPigWeightsInput(records, decimal("total"), nullable("at"))
            }
            "RecordPigBackfat" -> { fields("pig", "mm", "level", "at", "location", "remark")
                RecordPigBackfatInput(string("pig"), decimal("mm"), nullable("level")?.let(PigBackfatLevel::valueOf),
                    nullable("at"), nullable("location"), nullable("remark")) }
            "RecordPigTemperature" -> { fields("pig", "temperature", "unit", "at", "tag", "pen", "triage")
                RecordPigTemperatureInput(string("pig"), BigDecimal(string("temperature")), TemperatureUnit.valueOf(string("unit")),
                    nullable("at"), nullable("tag"), nullable("pen"), nullable("triage")?.let(PigTriageLevel::valueOf)) }
            else -> null
        }
    }
}
