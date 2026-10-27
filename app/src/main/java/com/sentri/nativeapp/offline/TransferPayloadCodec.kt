package com.sentri.nativeapp.offline

import com.sentri.nativeapp.transfer.*
import org.json.JSONArray
import org.json.JSONObject

internal object TransferPayloadCodec {
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun targets(values: List<TransferTarget>?) = values?.let { JSONArray().apply {
        it.forEach { target -> put(obj("pig" to target.pigId, "source" to target.sourceLocationCode)) }
    } }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is TransferOutInput -> obj("task" to input.transferId, "targets" to targets(input.transferTargets),
            "removed" to targets(input.removeTargets), "missing" to targets(input.missingTargets))
        is TransferInInput -> obj("task" to input.transferId, "pen" to input.penLocation,
            "targets" to targets(input.transferTargets), "unidentified" to input.noIdentityCount)
        is AddTransferTaskItemsInput -> obj("task" to input.transferId, "targets" to targets(input.targets))
        is RemoveTransferTaskItemsInput -> obj("task" to input.transferId, "pigs" to JSONArray(input.pigIds))
        is ResetTransferTaskItemsInput -> obj("task" to input.transferId, "targets" to targets(input.targets))
        is AddTransferInUnitInput -> obj("task" to input.transferId, "units" to JSONArray(input.unitLocations))
        is CompleteTransferTaskInput -> obj("task" to input.transferId, "at" to input.completedAt)
        is TransferPigInput -> obj("pig" to input.pigId, "destination" to input.destination)
        else -> null
    }
    fun decode(operation: String, value: JSONObject): CommandInput? = with(value) {
        fun fields(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
        fun string(key: String) = get(key) as? String ?: error("Invalid transfer string")
        fun nullable(key: String) = get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid transfer string") }
        fun strings(key: String): List<String> = (get(key) as? JSONArray ?: error("Invalid transfer list")).let { list ->
            (0 until list.length()).map { list.get(it) as? String ?: error("Invalid transfer item") }
        }
        fun targets(key: String): List<TransferTarget>? {
            if (get(key) == JSONObject.NULL) return null
            val rows = get(key) as? JSONArray ?: error("Invalid transfer targets")
            return (0 until rows.length()).map { index ->
                val row = rows.get(index) as? JSONObject ?: error("Invalid transfer target")
                require(row.keys().asSequence().toSet() == setOf("pig", "source"))
                TransferTarget(row.get("pig") as? String ?: error("Invalid pig ID"),
                    row.get("source") as? String ?: error("Invalid source"))
            }
        }
        when (operation) {
            "TransferOut" -> { fields("task", "targets", "removed", "missing")
                TransferOutInput(string("task"), targets("targets"), targets("removed"), targets("missing")) }
            "TransferIn" -> { fields("task", "pen", "targets", "unidentified")
                TransferInInput(string("task"), string("pen"), targets("targets"), getInt("unidentified")) }
            "AddTransferTaskItems" -> { fields("task", "targets")
                AddTransferTaskItemsInput(string("task"), requireNotNull(targets("targets"))) }
            "RemoveTransferTaskItems" -> { fields("task", "pigs")
                RemoveTransferTaskItemsInput(string("task"), strings("pigs")) }
            "ResetTransferTaskItems" -> { fields("task", "targets")
                ResetTransferTaskItemsInput(string("task"), requireNotNull(targets("targets"))) }
            "AddTransferInUnit" -> { fields("task", "units")
                AddTransferInUnitInput(string("task"), strings("units")) }
            "CompleteTransferTask" -> { fields("task", "at")
                CompleteTransferTaskInput(string("task"), nullable("at")) }
            "TransferPig" -> { fields("pig", "destination")
                TransferPigInput(string("pig"), string("destination")) }
            else -> null
        }
    }
}
