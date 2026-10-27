package com.sentri.nativeapp.offline

import com.sentri.nativeapp.production.CheckEstrusInput
import com.sentri.nativeapp.production.EstrusStatus
import com.sentri.nativeapp.production.SubmitEstrusInput
import com.sentri.nativeapp.production.MateWithBoarInput
import com.sentri.nativeapp.production.MateWithSemenInput
import com.sentri.nativeapp.production.CheckReEstrusInput
import com.sentri.nativeapp.production.SubmitReEstrusInput
import com.sentri.nativeapp.production.CheckPregnancyInput
import com.sentri.nativeapp.production.SubmitPregnancyInput
import com.sentri.nativeapp.production.ReEstrusStatus
import com.sentri.nativeapp.production.PregnancyStatus
import org.json.JSONArray
import org.json.JSONObject

/** Version 1 is the original CheckEstrus record; version 2 adds a typed operation envelope. */
object CommandPayloadCodec {
    const val VERSION = 2
    fun encode(input: CommandInput): String {
        val fields = when (input) {
            is CheckEstrusInput -> JSONObject().put("task", input.taskId).put("pig", input.pigId)
                .put("status", input.status.name).put("at", input.checkAt ?: JSONObject.NULL)
            is SubmitEstrusInput -> JSONObject().put("task", input.taskId).put("pigs", JSONArray(input.pigIds))
                .put("at", input.submitAt ?: JSONObject.NULL).put("autoEstrus", input.autoEstrus)
            is MateWithBoarInput -> JSONObject().put("task", input.taskId).put("pig", input.pigId).put("boar", input.boarId)
            is MateWithSemenInput -> JSONObject().put("task", input.taskId).put("pig", input.pigId).put("semen", input.semenId)
            is CheckReEstrusInput -> JSONObject().put("task", input.taskId).put("pig", input.pigId)
                .put("status", input.status.name).put("at", input.checkAt ?: JSONObject.NULL).put("unit", input.unitCode)
            is CheckPregnancyInput -> JSONObject().put("task", input.taskId).put("pig", input.pigId)
                .put("status", input.status.name).put("at", input.checkAt ?: JSONObject.NULL).put("days", input.reCheckDays ?: JSONObject.NULL).put("unit", input.unitCode)
            is SubmitReEstrusInput -> JSONObject().put("task", input.taskId).put("pigs", JSONArray(input.pigIds))
                .put("at", input.submitAt ?: JSONObject.NULL).put("unit", input.unitCode)
            is SubmitPregnancyInput -> JSONObject().put("task", input.taskId).put("pigs", JSONArray(input.pigIds))
                .put("at", input.submitAt ?: JSONObject.NULL).put("unit", input.unitCode)
            else -> CareCommandPayloadCodec.encode(input) ?: PigManagementPayloadCodec.encode(input) ?: DeathCommandPayloadCodec.encode(input) ?: TherapyPayloadCodec.encode(input) ?: VaccinationPayloadCodec.encode(input) ?: SamplingPayloadCodec.encode(input) ?: DeviceInspectionPayloadCodec.encode(input) ?: UnitInspectionPayloadCodec.encode(input) ?: PigHealthPayloadCodec.encode(input) ?: PigMeasurementPayloadCodec.encode(input) ?: LocationInventoryPayloadCodec.encode(input) ?: TransferPayloadCodec.encode(input) ?: error("Unsupported command input")
        }
        return JSONObject().put("operation", input.operationName).put("input", fields).toString()
    }
    fun decode(version: Int, encoded: String): CommandInput {
        val root = JSONObject(encoded)
        if (version == 1) return check(root)
        check(version == VERSION) { "Unsupported command payload version" }
        root.fields("operation", "input")
        val input = root.get("input") as? JSONObject ?: error("Invalid command input")
        return when (root.string("operation")) {
            "CheckEstrus" -> check(input)
            "CheckReEstrusPig" -> {
                input.fields("task", "pig", "status", "at", "unit")
                CheckReEstrusInput(input.string("task"), input.string("pig"), ReEstrusStatus.valueOf(input.string("status")), input.nullableString("at"), input.string("unit"))
            }
            "CheckPregnancyPig" -> {
                input.fields("task", "pig", "status", "at", "days", "unit")
                CheckPregnancyInput(input.string("task"), input.string("pig"), PregnancyStatus.valueOf(input.string("status")), input.nullableString("at"), input.nullableInteger("days"), input.string("unit"))
            }
            "SubmitReEstrusStatus" -> {
                input.fields("task", "pigs", "at", "unit")
                SubmitReEstrusInput(input.string("task"), input.strings("pigs"), input.nullableString("at"), input.string("unit"))
            }
            "SubmitPregnancyStatus" -> {
                input.fields("task", "pigs", "at", "unit")
                SubmitPregnancyInput(input.string("task"), input.strings("pigs"), input.nullableString("at"), input.string("unit"))
            }
            "MateWithBoar" -> {
                input.fields("task", "pig", "boar")
                MateWithBoarInput(input.string("task"), input.string("pig"), input.string("boar"))
            }
            "MateWithSemen" -> {
                input.fields("task", "pig", "semen")
                MateWithSemenInput(input.string("task"), input.string("pig"), input.string("semen"))
            }
            "SubmitEstrusStatus" -> {
                input.fields("task", "pigs", "at", "autoEstrus")
                SubmitEstrusInput(input.string("task"), input.strings("pigs"), input.nullableString("at"), input.get("autoEstrus") as? Boolean ?: error("Invalid autoEstrus"))
            }
            else -> CareCommandPayloadCodec.decode(root.string("operation"), input) ?: PigManagementPayloadCodec.decode(root.string("operation"), input) ?: DeathCommandPayloadCodec.decode(root.string("operation"), input) ?: TherapyPayloadCodec.decode(root.string("operation"), input) ?: VaccinationPayloadCodec.decode(root.string("operation"), input) ?: SamplingPayloadCodec.decode(root.string("operation"), input) ?: DeviceInspectionPayloadCodec.decode(root.string("operation"), input) ?: UnitInspectionPayloadCodec.decode(root.string("operation"), input) ?: PigHealthPayloadCodec.decode(root.string("operation"), input) ?: PigMeasurementPayloadCodec.decode(root.string("operation"), input) ?: LocationInventoryPayloadCodec.decode(root.string("operation"), input) ?: TransferPayloadCodec.decode(root.string("operation"), input) ?: error("Unsupported command operation")
        }
    }
    private fun check(input: JSONObject): CheckEstrusInput {
        input.fields("task", "pig", "status", "at")
        return CheckEstrusInput(input.string("task"), input.string("pig"), EstrusStatus.valueOf(input.string("status")), input.nullableString("at"))
    }
    private fun JSONObject.fields(vararg expected: String) {
        check(keys().asSequence().toSet() == expected.toSet()) { "Invalid command payload fields" }
    }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid command string")
    private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.nullableInteger(key: String): Int? = when (val value = get(key)) {
        JSONObject.NULL -> null
        is Int -> value
        is Long -> Math.toIntExact(value)
        else -> error("Invalid command integer")
    }
    private fun JSONObject.strings(key: String): List<String> {
        val values = get(key) as? JSONArray ?: error("Invalid command list")
        return (0 until values.length()).map { values.get(it) as? String ?: error("Invalid command string") }
    }
}
