package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject

internal object DeathCommandPayloadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is UploadPigDeathImageInput -> obj("report" to input.reportOperationId, "pigIndex" to input.pigIndex, "imageIndex" to input.imageIndex,
            "data" to input.dataUrl, "pig" to input.targetPigId, "task" to input.taskId)
        is ReportPigDeathInput -> obj("task" to input.taskId, "pigs" to JSONArray(input.pigs.map { pig ->
            obj("pig" to pig.pigId, "mother" to pig.motherId, "tag" to pig.earTag, "notch" to pig.earNotch, "location" to pig.locationCode,
                "reason" to pig.reason, "images" to JSONArray(pig.images.map { when (it) {
                    is DeathImage.Remote -> obj("kind" to "remote", "value" to it.url)
                    is DeathImage.Uploaded -> obj("kind" to "uploaded", "value" to it.operationId)
                } }))
        }))
        else -> null
    }
    fun decode(operation: String, value: JSONObject): CommandInput? = with(value) {
        when (operation) {
            "UploadPigDeathImage" -> {
                fields("report", "pigIndex", "imageIndex", "data", "pig", "task")
                UploadPigDeathImageInput(string("report"), integer("pigIndex"), integer("imageIndex"), string("data"), text("pig"), text("task"))
            }
            "ReportPigDeath" -> {
                fields("task", "pigs")
                ReportPigDeathInput(objects("pigs").map { pig ->
                    pig.fields("pig", "mother", "tag", "notch", "location", "reason", "images")
                    DeathPigInput(pig.text("pig"), pig.text("mother"), pig.text("tag"), pig.text("notch"), pig.text("location"), pig.text("reason"),
                        pig.objects("images").map { image ->
                            image.fields("kind", "value")
                            when (image.string("kind")) {
                                "remote" -> DeathImage.Remote(image.string("value"))
                                "uploaded" -> DeathImage.Uploaded(image.string("value"))
                                else -> error("Invalid death image kind")
                            }
                        })
                }, text("task"))
            }
            else -> null
        }
    }
    private fun JSONObject.fields(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid death payload string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) {
        is Int -> value
        is Long -> Math.toIntExact(value)
        else -> error("Invalid death payload integer")
    }
    private fun JSONObject.objects(key: String): List<JSONObject> {
        val list = get(key) as? JSONArray ?: error("Invalid death payload list")
        return (0 until list.length()).map { list.get(it) as? JSONObject ?: error("Invalid death payload object") }
    }
}
