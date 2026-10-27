package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

internal object PigHealthPayloadCodec {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is UploadHealthyImageInput -> obj("data" to input.dataUrl)
        is AddPigDiseaseInput -> obj("pig" to input.pigId, "ids" to JSONArray(input.diseaseIds), "tag" to input.earTag,
            "images" to JSONArray(input.imageUrls), "pen" to input.penLocation, "reason" to input.reason,
            "triage" to input.triageLevel?.name)
        is AddPigSymptomInput -> obj("pig" to input.pigId, "ids" to JSONArray(input.symptomIds), "tag" to input.earTag,
            "images" to JSONArray(input.imageUrls), "pen" to input.penLocation, "reason" to input.reason,
            "triage" to input.triageLevel?.name)
        is RemovePigDiseaseInput -> obj("pig" to input.pigId, "ids" to JSONArray(input.diseaseIds),
            "recovered" to input.recovered, "pen" to input.penLocation)
        is RemovePigSymptomInput -> obj("pig" to input.pigId, "ids" to JSONArray(input.symptomIds),
            "recovered" to input.recovered, "pen" to input.penLocation)
        is SetPigTriageLevelInput -> obj("pig" to input.pigId, "tag" to input.earTag, "level" to input.level.name,
            "pen" to input.penLocation, "reason" to input.reason)
        is SetPigBodyTemperatureInput -> obj("pig" to input.pigId, "tag" to input.earTag,
            "temperature" to input.temperature.toString(), "unit" to input.unit.name,
            "triage" to input.triageLevel?.name, "pen" to input.penLocation)
        else -> null
    }
    fun decode(operation: String, value: JSONObject): CommandInput? = with(value) {
        fun fields(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
        fun string(key: String) = get(key) as? String ?: error("Invalid health payload string")
        fun nullable(key: String) = get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid health payload string") }
        fun strings(key: String): List<String> = (get(key) as? JSONArray ?: error("Invalid health payload list")).let { list ->
            (0 until list.length()).map { list.get(it) as? String ?: error("Invalid health payload list item") }
        }
        fun boolean(key: String) = get(key) as? Boolean ?: error("Invalid health payload boolean")
        fun triage(key: String) = nullable(key)?.let(PigTriageLevel::valueOf)
        when (operation) {
            "UploadHealthyImage" -> { fields("data"); UploadHealthyImageInput(string("data")) }
            "AddPigDisease" -> { fields("pig", "ids", "tag", "images", "pen", "reason", "triage")
                AddPigDiseaseInput(string("pig"), strings("ids"), nullable("tag"), strings("images"), nullable("pen"), nullable("reason"), triage("triage")) }
            "AddPigSymptom" -> { fields("pig", "ids", "tag", "images", "pen", "reason", "triage")
                AddPigSymptomInput(string("pig"), strings("ids"), nullable("tag"), strings("images"), nullable("pen"), nullable("reason"), triage("triage")) }
            "RemovePigDisease" -> { fields("pig", "ids", "recovered", "pen")
                RemovePigDiseaseInput(string("pig"), strings("ids"), boolean("recovered"), nullable("pen")) }
            "RemovePigSymptom" -> { fields("pig", "ids", "recovered", "pen")
                RemovePigSymptomInput(string("pig"), strings("ids"), boolean("recovered"), nullable("pen")) }
            "SetPigTriageLevel" -> { fields("pig", "tag", "level", "pen", "reason")
                SetPigTriageLevelInput(string("pig"), string("tag"), PigTriageLevel.valueOf(string("level")), nullable("pen"), nullable("reason")) }
            "SetPigBodyTemperature" -> { fields("pig", "tag", "temperature", "unit", "triage", "pen")
                SetPigBodyTemperatureInput(string("pig"), string("tag"), BigDecimal(string("temperature")),
                    TemperatureUnit.valueOf(string("unit")), triage("triage"), nullable("pen")) }
            else -> null
        }
    }
}
