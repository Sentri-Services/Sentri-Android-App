package com.sentri.nativeapp.offline

import com.sentri.nativeapp.vaccination.*
import org.json.JSONArray
import org.json.JSONObject

internal object VaccinationPayloadCodec {
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is ExecuteVaccinationTaskInput -> JSONObject().put("task", input.vaccinationTaskId)
            .put("targets", JSONArray(input.targetIds)).put("at", input.executedAt ?: JSONObject.NULL)
        is FinishVaccinationTaskInput -> JSONObject().put("task", input.vaccinationTaskId)
            .put("at", input.finishedAt ?: JSONObject.NULL)
        else -> null
    }
    fun decode(operation: String, input: JSONObject): VaccinationCommandInput? = when (operation) {
        "ExecuteVaccinationTask" -> {
            input.fields("task", "targets", "at")
            val targets = input.get("targets") as? JSONArray ?: error("Invalid vaccination target list")
            ExecuteVaccinationTaskInput(input.string("task"),
                (0 until targets.length()).map { targets.get(it) as? String ?: error("Invalid vaccination target") }, input.text("at"))
        }
        "FinishVaccinationTask" -> {
            input.fields("task", "at")
            FinishVaccinationTaskInput(input.string("task"), input.text("at"))
        }
        else -> null
    }
    private fun JSONObject.fields(vararg expected: String) { require(keys().asSequence().toSet() == expected.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid vaccination string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
}
