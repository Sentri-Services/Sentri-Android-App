package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeDeviceInspectionsQuery
import com.sentri.nativeapp.graphql.type.Farm_view_device_inspection_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject

interface DeviceInspectionRemote {
    suspend fun list(lease: ScopeLease): List<DeviceInspection>
    suspend fun forPen(lease: ScopeLease, penCode: String): List<DeviceInspection>
}

class GraphqlDeviceInspectionReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : DeviceInspectionRemote {
    override suspend fun list(lease: ScopeLease): List<DeviceInspection> {
        return read(lease, Farm_view_device_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId)))))
    }
    override suspend fun forPen(lease: ScopeLease, penCode: String): List<DeviceInspection> {
        require(penCode.isNotBlank())
        return read(lease, Farm_view_device_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            pen_code = Optional.Present(String_comparison_exp(_eq = Optional.Present(penCode))))).also { rows ->
            if (rows.any { it.penCode != penCode }) throw ApiFailure.Protocol()
        }
    }
    private suspend fun read(lease: ScopeLease, where: Farm_view_device_inspection_bool_exp): List<DeviceInspection> {
        check(lease)
        val result = ArrayList<DeviceInspection>()
        val ids = HashSet<String>()
        var expected: Int? = null
        do {
            val page = executor.query(NativeDeviceInspectionsQuery(where, result.size, 200))
            check(lease)
            val total = page.count.aggregate?.count ?: throw ApiFailure.Protocol()
            if (total < 0 || expected != null && expected != total || page.farm_view_device_inspection.size > 200 ||
                result.size + page.farm_view_device_inspection.size > total) throw ApiFailure.Protocol()
            expected = total
            page.farm_view_device_inspection.forEach { row ->
                val value = DeviceInspection(row.id, row.farm_id, row.pen_code, row.device_type,
                    row.device_name, row.device_status, row.remark, row.reported_at, row.reporter_id,
                    row.resolved_at, row.resolver_id)
                if (value.id.isBlank() || value.farmId != lease.scope.farmId || !ids.add(value.id))
                    throw ApiFailure.Protocol()
                result += value
            }
            if (result.size < total && page.farm_view_device_inspection.isEmpty()) throw ApiFailure.Protocol()
        } while (result.size < requireNotNull(expected))
        return result
    }
}

internal object DeviceInspectionReadCodec {
    fun encode(rows: List<DeviceInspection>): String = JSONObject().put("format", 1).put("rows", JSONArray().apply {
        rows.forEach { row -> put(JSONObject().put("id", row.id).put("farm", row.farmId).put("pen", row.penCode)
            .put("type", row.deviceType).put("name", row.deviceName ?: JSONObject.NULL)
            .put("status", row.status).put("remark", row.remark ?: JSONObject.NULL)
            .put("reported", row.reportedAt ?: JSONObject.NULL).put("reporter", row.reporterId ?: JSONObject.NULL)
            .put("resolved", row.resolvedAt ?: JSONObject.NULL).put("resolver", row.resolverId ?: JSONObject.NULL)) }
    }).toString()
    fun decode(encoded: String): List<DeviceInspection> {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "rows") && root.getInt("format") == 1)
        val rows = root.getJSONArray("rows")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "farm", "pen", "type", "name", "status", "remark", "reported", "reporter", "resolved", "resolver"))
            fun optional(key: String) = if (row.isNull(key)) null else row.getString(key)
            DeviceInspection(row.getString("id"), row.getString("farm"), row.getString("pen"), row.getString("type"),
                optional("name"), row.getString("status"), optional("remark"), optional("reported"),
                optional("reporter"), optional("resolved"), optional("resolver"))
        }
    }
}

class DeviceInspectionReads(private val remote: DeviceInspectionRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun list(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<DeviceInspection>> =
        snapshots.read(lease, "device_inspections", "all", cacheOnly, { remote.list(lease) },
            DeviceInspectionReadCodec::encode, DeviceInspectionReadCodec::decode) { rows ->
            require(rows.all { it.id.isNotBlank() && it.farmId == lease.scope.farmId })
            require(rows.map { it.id }.distinct().size == rows.size)
        }
    suspend fun forPen(lease: ScopeLease, penCode: String, cacheOnly: Boolean = false): CachedResult<List<DeviceInspection>> {
        require(penCode.isNotBlank())
        return snapshots.read(lease, "device_inspections_pen", penCode, cacheOnly,
            { remote.forPen(lease, penCode) }, DeviceInspectionReadCodec::encode,
            DeviceInspectionReadCodec::decode) { rows ->
            require(rows.all { it.id.isNotBlank() && it.farmId == lease.scope.farmId && it.penCode == penCode })
            require(rows.map { it.id }.distinct().size == rows.size)
        }
    }
}
