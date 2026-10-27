package com.sentri.nativeapp.home

import com.sentri.nativeapp.graphql.NativeHomeUnitRegisteredPensQuery
import com.sentri.nativeapp.inspection.DeviceInspection
import com.sentri.nativeapp.inspection.DeviceInspectionReadCodec
import com.sentri.nativeapp.inspection.DeviceInspectionRemote
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.ScopedSnapshotReads
import com.sentri.nativeapp.pigs.SnapshotCache
import org.json.JSONArray
import org.json.JSONObject

/** Exact registered-pen membership and equipment records for one unit. */
data class HomeUnitMaintenance(val unitId: String, val penCodes: Set<String>,
    val records: List<DeviceInspection>) {
    init {
        require(unitId.isNotBlank() && penCodes.none(String::isBlank))
        require(records.all { it.penCode in penCodes })
        require(records.map(DeviceInspection::id).distinct().size == records.size)
    }
    val open get() = records.filter { it.status == "Fault" }
}

fun interface HomeUnitMaintenanceRemote {
    suspend fun current(lease: ScopeLease, unitId: String): HomeUnitMaintenance
}

class GraphqlHomeUnitMaintenanceRemote(private val executor: GraphqlExecutor,
    private val devices: DeviceInspectionRemote, private val check: (ScopeLease) -> Unit) : HomeUnitMaintenanceRemote {
    override suspend fun current(lease: ScopeLease, unitId: String): HomeUnitMaintenance {
        require(unitId.isNotBlank())
        check(lease)
        val codes = LinkedHashSet<String>()
        val penIds = HashSet<Int>()
        var offset = 0
        var expected: Int? = null
        do {
            val page = executor.query(NativeHomeUnitRegisteredPensQuery(
                lease.scope.farmId, unitId, offset, 200))
            check(lease)
            val total = page.count.aggregate?.count ?: throw ApiFailure.Protocol()
            if (expected != null && expected != total || total < 0 || page.pens.size > 200 ||
                offset + page.pens.size > total) throw ApiFailure.Protocol()
            expected = total
            page.pens.forEach { pen ->
                if (pen.farm_id != lease.scope.farmId || pen.unit_id != unitId || !penIds.add(pen.id))
                    throw ApiFailure.Protocol()
                pen.pen_location?.let { if (it.isBlank() || !codes.add(it)) throw ApiFailure.Protocol() }
            }
            offset += page.pens.size
            if (offset < total && page.pens.isEmpty()) throw ApiFailure.Protocol()
        } while (offset < requireNotNull(expected))
        if (penIds.size != expected) throw ApiFailure.Protocol()
        val rows = devices.list(lease)
        check(lease)
        if (rows.any { it.id.isBlank() || it.farmId != lease.scope.farmId } ||
            rows.map(DeviceInspection::id).distinct().size != rows.size) throw ApiFailure.Protocol()
        return HomeUnitMaintenance(unitId, codes, rows.filter { it.penCode in codes })
    }
}

class HomeUnitMaintenanceReads(private val remote: HomeUnitMaintenanceRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun current(lease: ScopeLease, unitId: String, cacheOnly: Boolean = false): CachedResult<HomeUnitMaintenance> {
        require(unitId.isNotBlank())
        return snapshots.read(lease, "home_unit_maintenance", unitId, cacheOnly,
            { remote.current(lease, unitId) },
            { value -> JSONObject().put("format", 1).put("unit", value.unitId)
                .put("codes", JSONArray(value.penCodes.sorted()))
                .put("records", DeviceInspectionReadCodec.encode(value.records)).toString() },
            { encoded ->
                val root = JSONObject(encoded)
                require(root.keys().asSequence().toSet() == setOf("format", "unit", "codes", "records") &&
                    root.getInt("format") == 1)
                val codes = root.getJSONArray("codes")
                HomeUnitMaintenance(root.getString("unit"),
                    (0 until codes.length()).map(codes::getString).toSet(),
                    DeviceInspectionReadCodec.decode(root.getString("records")))
            }) { value ->
            require(value.unitId == unitId && value.records.all { it.farmId == lease.scope.farmId })
        }
    }
}
