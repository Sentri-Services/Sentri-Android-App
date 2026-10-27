package com.sentri.nativeapp.offline

import com.sentri.nativeapp.sampling.SubmitSampledInput
import org.json.JSONObject

internal object SamplingPayloadCodec {
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is SubmitSampledInput -> JSONObject().put("task", input.samplingTaskId)
            .put("remark", input.remark ?: JSONObject.NULL)
        else -> null
    }
    fun decode(operation: String, input: JSONObject): SubmitSampledInput? = when (operation) {
        "SubmitSampled" -> {
            require(input.keys().asSequence().toSet() == setOf("task", "remark"))
            val task = input.get("task") as? String ?: error("Invalid sampling task")
            val remark = input.get("remark").let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid sampling remark") }
            SubmitSampledInput(task, remark)
        }
        else -> null
    }
}
