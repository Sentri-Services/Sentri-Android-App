package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeLocationInventoryQuery
import com.sentri.nativeapp.graphql.type.Farm_enum_pig_lifecycle_status_enum
import com.sentri.nativeapp.graphql.type.Farm_enum_pig_lifecycle_status_enum_comparison_exp
import com.sentri.nativeapp.graphql.type.Farm_view_pig_bool_exp
import com.sentri.nativeapp.graphql.type.Farm_view_pen_index_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject

interface LocationInventoryRemote {
    suspend fun current(lease: ScopeLease, unitId: String, locationCode: String): LocationInventorySnapshot
}

class GraphqlLocationInventoryReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : LocationInventoryRemote {
    override suspend fun current(lease: ScopeLease, unitId: String, locationCode: String): LocationInventorySnapshot {
        require(unitId.isNotBlank() && locationCode.isNotBlank()); check(lease)
        val where = Farm_view_pig_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            location_code = Optional.Present(String_comparison_exp(_eq = Optional.Present(locationCode))),
            location = Optional.Present(Farm_view_pen_index_bool_exp(
                unit_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitId))))),
            lifecycle_status = Optional.Present(Farm_enum_pig_lifecycle_status_enum_comparison_exp(
                _eq = Optional.Present(Farm_enum_pig_lifecycle_status_enum.ACTIVE))))
        val rows = executor.query(NativeLocationInventoryQuery(where)).farm_view_pig
        check(lease)
        if (rows.any { it.location?.unit_id != unitId }) throw ApiFailure.Protocol()
        val result = LocationInventorySnapshot(locationCode, rows.map { LocationInventoryPig(it.id, it.farm_id ?: "",
            it.location_code ?: "", it.lifecycle_status.rawValue, it.ear_tag, it.ear_notch, it.current_batch_id) })
        try { LocationInventoryReadCodec.validate(result, lease.scope.farmId, locationCode) }
        catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        return result
    }
}

internal object LocationInventoryReadCodec {
    fun validate(value: LocationInventorySnapshot, farmId: String, locationCode: String) {
        require(value.locationCode == locationCode)
        require(value.pigs.all { it.pigId.isNotBlank() && it.farmId == farmId &&
            it.locationCode == locationCode && it.lifecycleStatus == "ACTIVE" })
        require(value.pigs.map(LocationInventoryPig::pigId).distinct().size == value.pigs.size)
    }
    fun encode(value: LocationInventorySnapshot): String = JSONObject().put("format", 1)
        .put("location", value.locationCode).put("pigs", JSONArray().apply { value.pigs.forEach { pig ->
            put(JSONObject().put("id", pig.pigId).put("farm", pig.farmId).put("location", pig.locationCode)
                .put("status", pig.lifecycleStatus).put("tag", pig.earTag ?: JSONObject.NULL)
                .put("notch", pig.earNotch ?: JSONObject.NULL).put("batch", pig.batchId ?: JSONObject.NULL))
        } }).toString()
    fun decode(encoded: String): LocationInventorySnapshot {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "location", "pigs") && root.getInt("format") == 1)
        val rows = root.getJSONArray("pigs")
        return LocationInventorySnapshot(root.getString("location"), (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "farm", "location", "status", "tag", "notch", "batch"))
            fun optional(key: String) = if (row.isNull(key)) null else row.getString(key)
            LocationInventoryPig(row.getString("id"), row.getString("farm"), row.getString("location"),
                row.getString("status"), optional("tag"), optional("notch"), optional("batch"))
        })
    }
}

class LocationInventoryReads(private val remote: LocationInventoryRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun current(lease: ScopeLease, unitId: String, locationCode: String,
        cacheOnly: Boolean = false): CachedResult<LocationInventorySnapshot> {
        require(unitId.isNotBlank() && locationCode.isNotBlank())
        val key = JSONObject().put("unit", unitId).put("location", locationCode).toString()
        return snapshots.read(lease, "location_inventory", key, cacheOnly,
            { remote.current(lease, unitId, locationCode) },
            LocationInventoryReadCodec::encode, LocationInventoryReadCodec::decode) { value ->
            LocationInventoryReadCodec.validate(value, lease.scope.farmId, locationCode)
        }
    }
}
