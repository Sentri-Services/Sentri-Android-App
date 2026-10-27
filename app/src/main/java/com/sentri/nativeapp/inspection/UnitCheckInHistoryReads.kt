package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeLatestUnitCheckInQuery
import com.sentri.nativeapp.graphql.type.Farm_view_unit_inspection_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.ScopedSnapshotReads
import com.sentri.nativeapp.pigs.SnapshotCache
import org.json.JSONObject

data class UnitCheckInSummary(val id: String, val farmId: String, val unitCode: String,
    val createdAt: String?, val operatorName: String?)

fun interface UnitCheckInHistoryRemote {
    suspend fun latest(lease: ScopeLease, unitCode: String): UnitCheckInSummary?
}

class GraphqlUnitCheckInHistoryRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : UnitCheckInHistoryRemote {
    override suspend fun latest(lease: ScopeLease, unitCode: String): UnitCheckInSummary? {
        require(unitCode.isNotBlank())
        check(lease)
        val where = Farm_view_unit_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            unit_code = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitCode))))
        val rows = executor.query(NativeLatestUnitCheckInQuery(where)).farm_view_unit_inspection
        check(lease)
        if (rows.size > 1) throw ApiFailure.Protocol()
        return rows.singleOrNull()?.let { row ->
            UnitCheckInSummary(row.id, row.farm_id, row.unit_code, row.created_at,
                row.operator?.display_name)
        }.also { item ->
            if (item != null && (item.id.isBlank() || item.farmId != lease.scope.farmId ||
                    item.unitCode != unitCode)) throw ApiFailure.Protocol()
        }
    }
}

class UnitCheckInHistoryReads(remote: UnitCheckInHistoryRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    private val fetch = remote
    suspend fun latest(lease: ScopeLease, unitCode: String, cacheOnly: Boolean = false): CachedResult<UnitCheckInSummary?> {
        require(unitCode.isNotBlank())
        return snapshots.read(lease, "unit_check_in_latest", unitCode, cacheOnly,
            { fetch.latest(lease, unitCode) }, ::encode, ::decode) { item ->
            require(item == null || (item.id.isNotBlank() && item.farmId == lease.scope.farmId &&
                item.unitCode == unitCode))
            item?.createdAt?.let(TimestampAdapter::validate)
        }
    }

    private fun encode(item: UnitCheckInSummary?): String = JSONObject().put("format", 1).apply {
        put("item", item?.let { JSONObject().put("id", it.id).put("farm", it.farmId)
            .put("unit", it.unitCode).put("created", it.createdAt ?: JSONObject.NULL)
            .put("operator", it.operatorName ?: JSONObject.NULL) } ?: JSONObject.NULL)
    }.toString()

    private fun decode(encoded: String): UnitCheckInSummary? {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "item") && root.getInt("format") == 1)
        if (root.isNull("item")) return null
        val item = root.getJSONObject("item")
        require(item.keys().asSequence().toSet() == setOf("id", "farm", "unit", "created", "operator"))
        return UnitCheckInSummary(item.getString("id"), item.getString("farm"), item.getString("unit"),
            if (item.isNull("created")) null else item.getString("created"),
            if (item.isNull("operator")) null else item.getString("operator"))
    }
}
