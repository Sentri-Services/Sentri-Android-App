package com.sentri.nativeapp.home

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeHomeUnitStockQuery
import com.sentri.nativeapp.graphql.type.Farm_enum_pig_lifecycle_status_enum
import com.sentri.nativeapp.graphql.type.Farm_enum_pig_lifecycle_status_enum_comparison_exp
import com.sentri.nativeapp.graphql.type.Farm_view_pen_index_bool_exp
import com.sentri.nativeapp.graphql.type.Farm_view_pig_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.ScopedSnapshotReads
import com.sentri.nativeapp.pigs.SnapshotCache
import org.json.JSONObject

data class HomeUnitStock(val unitId: String, val pigCount: Int, val occupiedPens: Int) {
    init { require(unitId.isNotBlank() && pigCount >= 0 && occupiedPens in 0..pigCount) }
}

fun interface HomeUnitStockRemote { suspend fun current(lease: ScopeLease, unitId: String): HomeUnitStock }

/** Two aggregates share the same ACTIVE/farm/unit predicate; pen count excludes unassigned pigs. */
class GraphqlHomeUnitStockRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : HomeUnitStockRemote {
    override suspend fun current(lease: ScopeLease, unitId: String): HomeUnitStock {
        require(unitId.isNotBlank()); check(lease)
        val base = Farm_view_pig_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            lifecycle_status = Optional.Present(Farm_enum_pig_lifecycle_status_enum_comparison_exp(
                _eq = Optional.Present(Farm_enum_pig_lifecycle_status_enum.ACTIVE))),
            location = Optional.Present(Farm_view_pen_index_bool_exp(
                unit_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitId))))))
        val withPen = base.copy(location = Optional.Present(Farm_view_pen_index_bool_exp(
            unit_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitId))),
            pen_location = Optional.Present(String_comparison_exp(
                _is_null = Optional.Present(false), _neq = Optional.Present(""))))))
        val data = executor.query(NativeHomeUnitStockQuery(base, withPen))
        check(lease)
        val total = data.current.aggregate?.count ?: throw ApiFailure.Protocol()
        val pens = data.occupied.aggregate?.pens ?: throw ApiFailure.Protocol()
        return try { HomeUnitStock(unitId, total, pens) }
        catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
    }
}

class HomeUnitStockReads(remote: HomeUnitStockRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    private val fetch = remote
    suspend fun current(lease: ScopeLease, unitId: String, cacheOnly: Boolean = false): CachedResult<HomeUnitStock> {
        require(unitId.isNotBlank())
        return snapshots.read(lease, "home_unit_stock", unitId, cacheOnly,
            { fetch.current(lease, unitId) },
            { value -> JSONObject().put("format", 1).put("unit", value.unitId)
                .put("pigs", value.pigCount).put("pens", value.occupiedPens).toString() },
            { encoded ->
                val root = JSONObject(encoded)
                require(root.keys().asSequence().toSet() == setOf("format", "unit", "pigs", "pens") && root.getInt("format") == 1)
                HomeUnitStock(root.getString("unit"), root.getInt("pigs"), root.getInt("pens"))
            }) { value -> require(value.unitId == unitId) }
    }
}
