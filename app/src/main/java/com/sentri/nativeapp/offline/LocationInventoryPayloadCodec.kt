package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.NonIdentityBatchAllocation
import com.sentri.nativeapp.inspection.ReconcileLocationInventoryInput
import org.json.JSONArray
import org.json.JSONObject

internal object LocationInventoryPayloadCodec {
    fun encode(input: CommandInput): JSONObject? = (input as? ReconcileLocationInventoryInput)?.let { value ->
        JSONObject().put("location", value.locationCode).put("target", value.targetCount)
            .put("allocations", value.nonIdentityAllocations?.let { allocations -> JSONArray().apply {
                allocations.forEach { put(JSONObject().put("batch", it.batchId).put("count", it.count)) }
            } } ?: JSONObject.NULL).put("reason", value.reason ?: JSONObject.NULL)
    }
    fun decode(operation: String, value: JSONObject): CommandInput? {
        if (operation != "ReconcileLocationInventory") return null
        require(value.keys().asSequence().toSet() == setOf("location", "target", "allocations", "reason"))
        val allocations = if (value.isNull("allocations")) null else value.getJSONArray("allocations").let { rows ->
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == setOf("batch", "count"))
                NonIdentityBatchAllocation(row.getString("batch"), row.getInt("count"))
            }
        }
        return ReconcileLocationInventoryInput(value.getString("location"), value.getInt("target"),
            allocations, if (value.isNull("reason")) null else value.getString("reason"))
    }
}
