package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.SubmitUnitInspectionInput
import com.sentri.nativeapp.inspection.SubmitUnitRemarkInput
import org.json.JSONObject
import java.math.BigDecimal

internal object UnitInspectionPayloadCodec {
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is SubmitUnitInspectionInput -> JSONObject().put("unit", input.unitCode)
            .put("id", input.unitInspectionId ?: JSONObject.NULL)
            .put("temperature", input.temperature?.toString() ?: JSONObject.NULL)
            .put("humidity", input.humidity?.toString() ?: JSONObject.NULL)
            .put("ammonia", input.ammonia?.toString() ?: JSONObject.NULL)
            .put("co2", input.co2?.toString() ?: JSONObject.NULL)
            .put("illumination", input.illumination ?: JSONObject.NULL)
            .put("ventilation", input.ventilation ?: JSONObject.NULL)
            .put("water", input.waterConsumption?.toString() ?: JSONObject.NULL)
            .put("feed", input.feedConsumption?.toString() ?: JSONObject.NULL)
            .put("environmentRemarks", input.environmentRemarks ?: JSONObject.NULL)
            .put("cleanliness", input.cleanliness ?: JSONObject.NULL)
            .put("compliance", input.compliance ?: JSONObject.NULL)
            .put("bioRemarks", input.bioRemarks ?: JSONObject.NULL)
        is SubmitUnitRemarkInput -> JSONObject().put("unit", input.unitCode).put("id", input.unitInspectionId ?: JSONObject.NULL)
            .put("remark", input.remark)
        else -> null
    }
    fun decode(operation: String, input: JSONObject): CommandInput? {
        fun string(key: String) = input.get(key) as? String ?: error("Invalid unit inspection string")
        fun nullable(key: String) = input.get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid unit inspection string") }
        fun decimal(key: String) = nullable(key)?.let(::BigDecimal)
        return when (operation) {
            "SubmitUnitInspection" -> {
                require(input.keys().asSequence().toSet() == setOf("unit", "id", "temperature", "humidity", "ammonia", "co2",
                    "illumination", "ventilation", "water", "feed", "environmentRemarks", "cleanliness", "compliance", "bioRemarks"))
                SubmitUnitInspectionInput(string("unit"), nullable("id"), decimal("temperature"), decimal("humidity"),
                    decimal("ammonia"), decimal("co2"), nullable("illumination"), nullable("ventilation"), decimal("water"),
                    decimal("feed"), nullable("environmentRemarks"), nullable("cleanliness"), nullable("compliance"), nullable("bioRemarks"))
            }
            "SubmitUnitRemark" -> {
                require(input.keys().asSequence().toSet() == setOf("unit", "id", "remark"))
                SubmitUnitRemarkInput(string("unit"), string("remark"), nullable("id"))
            }
            else -> null
        }
    }
}
