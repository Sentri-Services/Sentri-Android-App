package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeRecentUnitCheckInsQuery
import com.sentri.nativeapp.graphql.type.Farm_view_unit_inspection_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.ScopedSnapshotReads
import com.sentri.nativeapp.pigs.SnapshotCache
import org.json.JSONArray
import org.json.JSONObject

data class RecentUnitCheckInsPage(val entries: List<UnitCheckInSummary>, val offset: Int,
    val hasMore: Boolean)

fun interface RecentUnitCheckInsRemote {
    suspend fun page(lease: ScopeLease, unitCode: String, offset: Int): RecentUnitCheckInsPage
}

class GraphqlRecentUnitCheckInsRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : RecentUnitCheckInsRemote {
    override suspend fun page(lease: ScopeLease, unitCode: String, offset: Int): RecentUnitCheckInsPage {
        require(unitCode.isNotBlank() && offset >= 0 && offset % RecentUnitCheckInsReads.PAGE_SIZE == 0)
        check(lease)
        val where = Farm_view_unit_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            unit_code = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitCode))))
        val rows = executor.query(NativeRecentUnitCheckInsQuery(where, offset)).farm_view_unit_inspection
        check(lease)
        if (rows.size > RecentUnitCheckInsReads.PAGE_SIZE + 1) throw ApiFailure.Protocol()
        val entries = rows.map { row -> UnitCheckInSummary(row.id, row.farm_id, row.unit_code,
            row.created_at, row.operator?.display_name) }
        if (entries.any { it.id.isBlank() || it.farmId != lease.scope.farmId ||
                it.unitCode != unitCode } || entries.map(UnitCheckInSummary::id).distinct().size != entries.size)
            throw ApiFailure.Protocol()
        entries.forEach { it.createdAt?.let(TimestampAdapter::validate) }
        return RecentUnitCheckInsPage(entries.take(RecentUnitCheckInsReads.PAGE_SIZE), offset,
            entries.size > RecentUnitCheckInsReads.PAGE_SIZE)
    }
}

class RecentUnitCheckInsReads(remote: RecentUnitCheckInsRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    private val fetch = remote

    suspend fun page(lease: ScopeLease, unitCode: String, offset: Int = 0,
        cacheOnly: Boolean = false): CachedResult<RecentUnitCheckInsPage> {
        require(unitCode.isNotBlank() && offset >= 0 && offset % PAGE_SIZE == 0)
        return snapshots.read(lease, "unit_check_in_recent", "$unitCode:$offset", cacheOnly,
            { fetch.page(lease, unitCode, offset) }, ::encode, ::decode) { result ->
            require(result.offset == offset && result.entries.size <= PAGE_SIZE)
            require(result.entries.all { it.id.isNotBlank() && it.farmId == lease.scope.farmId &&
                it.unitCode == unitCode })
            require(result.entries.map(UnitCheckInSummary::id).distinct().size == result.entries.size)
            result.entries.forEach { it.createdAt?.let(TimestampAdapter::validate) }
        }
    }

    private fun encode(page: RecentUnitCheckInsPage) = JSONObject().put("format", 1)
        .put("offset", page.offset).put("more", page.hasMore).put("rows", JSONArray().apply {
            page.entries.forEach { put(JSONObject().put("id", it.id).put("farm", it.farmId)
                .put("unit", it.unitCode).put("created", it.createdAt ?: JSONObject.NULL)
                .put("operator", it.operatorName ?: JSONObject.NULL)) }
        }).toString()

    private fun decode(encoded: String): RecentUnitCheckInsPage {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "offset", "more", "rows") &&
            root.getInt("format") == 1)
        val rows = root.getJSONArray("rows")
        require(rows.length() <= PAGE_SIZE)
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "farm", "unit", "created", "operator"))
            UnitCheckInSummary(row.getString("id"), row.getString("farm"), row.getString("unit"),
                if (row.isNull("created")) null else row.getString("created"),
                if (row.isNull("operator")) null else row.getString("operator"))
        }
        return RecentUnitCheckInsPage(entries, root.getInt("offset"), root.getBoolean("more"))
    }

    companion object { const val PAGE_SIZE = 20 }
}
