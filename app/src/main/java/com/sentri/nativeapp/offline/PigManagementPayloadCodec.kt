package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.PigletSex
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

internal object PigManagementPayloadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is UpdatePigPropertyInput -> obj("pig" to input.pigId, "lifecycle" to input.lifecycle?.name, "sex" to input.sex?.name,
            "teats" to input.teats, "weight" to input.weight?.toString(), "remark" to input.remark)
        is UpdateBreederRoleInput -> obj("action" to input.action.name, "pigs" to JSONArray(input.pigs.map {
            obj("pig" to it.pigId, "tag" to it.earTag, "notch" to it.earNotch, "sex" to it.sex?.name, "mother" to it.motherId, "father" to it.fatherId)
        }), "at" to input.markedAt)
        else -> null
    }
    fun decode(operation: String, value: JSONObject): CommandInput? = with(value) {
        when (operation) {
            "UpdatePigProperty" -> {
                fields("pig", "lifecycle", "sex", "teats", "weight", "remark")
                UpdatePigPropertyInput(string("pig"), text("lifecycle")?.let(PigLifecycle::valueOf), text("sex")?.let(PigletSex::valueOf),
                    integer("teats"), text("weight")?.let(::BigDecimal), text("remark"))
            }
            "UpdateBreederRole" -> {
                fields("action", "pigs", "at")
                val rows = get("pigs") as? JSONArray ?: error("Invalid breeder pigs")
                val pigs = (0 until rows.length()).map {
                    val pig = rows.get(it) as? JSONObject ?: error("Invalid breeder pig")
                    pig.fields("pig", "tag", "notch", "sex", "mother", "father")
                    BreederPigInput(pig.string("pig"), pig.text("tag"), pig.text("notch"), pig.text("sex")?.let(PigletSex::valueOf), pig.text("mother"), pig.text("father"))
                }
                UpdateBreederRoleInput(BreederRoleAction.valueOf(string("action")), pigs, text("at"))
            }
            else -> null
        }
    }
    private fun JSONObject.fields(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid pig management string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Int? = when (val v = get(key)) {
        JSONObject.NULL -> null
        is Int -> v
        is Long -> Math.toIntExact(v)
        else -> error("Invalid pig management integer")
    }
}
