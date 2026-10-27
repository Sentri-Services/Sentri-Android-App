package com.sentri.nativeapp.offline

import com.sentri.nativeapp.therapy.*
import org.json.JSONArray
import org.json.JSONObject

internal object TherapyPayloadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    private fun targets(values: List<PrescriptionTargetInput>) = JSONArray(values.map {
        obj("id" to it.targetId, "tag" to it.earTag, "pen" to it.penCode, "symptoms" to JSONArray(it.symptoms), "diseases" to JSONArray(it.diseases))
    })
    fun encode(input: CommandInput): JSONObject? = if (input !is TherapyCommandInput) null else {
        val value = obj("prescription" to input.prescriptionId)
        when (input) {
            is CompleteTherapyTargetInput -> value.put("therapy", input.therapyId).put("task", input.therapyTaskId).put("targets", JSONArray(input.targetIds)).put("at", input.completedAt)
            is SkipTherapyTaskInput -> value.put("therapy", input.therapyId).put("task", input.therapyTaskId).put("targets", JSONArray(input.targetIds)).put("reason", input.reason ?: JSONObject.NULL)
            is AddPrescriptionTargetInput -> value.put("targets", targets(input.targets)).put("reason", input.reason ?: JSONObject.NULL)
            is RemovePrescriptionTargetInput -> value.put("targets", targets(input.targets)).put("reason", input.reason ?: JSONObject.NULL)
            is TerminateTherapyInput -> value.put("therapy", input.therapyId).put("reason", input.reason ?: JSONObject.NULL)
            is TerminatePrescriptionInput -> value.put("reason", input.reason ?: JSONObject.NULL)
        }
        value
    }
    fun decode(operation: String, value: JSONObject): TherapyCommandInput? = with(value) {
        when (operation) {
            "CompleteTherapyTarget" -> { fields("prescription", "therapy", "task", "targets", "at"); CompleteTherapyTargetInput(string("prescription"), string("therapy"), string("task"), strings("targets"), string("at")) }
            "SkipTherapyTask" -> { fields("prescription", "therapy", "task", "targets", "reason"); SkipTherapyTaskInput(string("prescription"), string("therapy"), string("task"), strings("targets"), text("reason")) }
            "AddPrescriptionTarget" -> { fields("prescription", "targets", "reason"); AddPrescriptionTargetInput(string("prescription"), readTargets(), text("reason")) }
            "RemovePrescriptionTarget" -> { fields("prescription", "targets", "reason"); RemovePrescriptionTargetInput(string("prescription"), readTargets(), text("reason")) }
            "TerminateTherapy" -> { fields("prescription", "therapy", "reason"); TerminateTherapyInput(string("prescription"), string("therapy"), text("reason")) }
            "TerminatePrescription" -> { fields("prescription", "reason"); TerminatePrescriptionInput(string("prescription"), text("reason")) }
            else -> null
        }
    }
    private fun JSONObject.readTargets(): List<PrescriptionTargetInput> = array("targets").let { rows -> (0 until rows.length()).map {
        val row = rows.get(it) as? JSONObject ?: error("Invalid prescription target")
        row.fields("id", "tag", "pen", "symptoms", "diseases")
        PrescriptionTargetInput(row.string("id"), row.text("tag"), row.text("pen"), row.strings("symptoms"), row.strings("diseases"))
    } }
    private fun JSONObject.fields(vararg expected: String) { require(keys().asSequence().toSet() == expected.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid therapy string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.array(key: String) = get(key) as? JSONArray ?: error("Invalid therapy list")
    private fun JSONObject.strings(key: String): List<String> = array(key).let { values -> (0 until values.length()).map { values.get(it) as? String ?: error("Invalid therapy list item") } }
}
