package com.sentri.nativeapp.home

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeHomeUnitPenRosterQuery
import com.sentri.nativeapp.graphql.NativeHomeUnitRegisteredPensQuery
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
import org.json.JSONArray
import org.json.JSONObject

data class HomePenCount(val code: String, val number: Int, val pigs: Int) {
    init { require(code.isNotBlank() && pigs > 0) }
}

data class HomeRegisteredPen(val id: Int, val code: String?, val number: Int) {
    init { require(id >= 0 && number >= 0 && (code == null || code.isNotBlank())) }
}

data class HomeUnitPenRoster(val unitId: String, val total: Int, val unassigned: Int,
    val pens: List<HomePenCount>, val registeredPens: List<HomeRegisteredPen>? = null) {
    init {
        require(unitId.isNotBlank() && total >= 0 && unassigned >= 0)
        require(pens.map(HomePenCount::code).distinct().size == pens.size)
        require(pens.sumOf { it.pigs } + unassigned == total)
        registeredPens?.let { registered ->
            require(registered.map(HomeRegisteredPen::id).distinct().size == registered.size)
            val codes = registered.mapNotNull(HomeRegisteredPen::code)
            require(codes.distinct().size == codes.size)
            val numberByCode = registered.mapNotNull { pen -> pen.code?.let { it to pen.number } }.toMap()
            require(pens.all { numberByCode[it.code] == it.number })
        }
    }
}

fun interface HomeUnitPenRosterRemote {
    suspend fun current(lease: ScopeLease, unitId: String): HomeUnitPenRoster
}

/** Pulls every lightweight ID/location page before producing a count; a partial roster is never cached. */
class GraphqlHomeUnitPenRosterRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : HomeUnitPenRosterRemote {
    override suspend fun current(lease: ScopeLease, unitId: String): HomeUnitPenRoster {
        require(unitId.isNotBlank()); check(lease)
        val where = Farm_view_pig_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            lifecycle_status = Optional.Present(Farm_enum_pig_lifecycle_status_enum_comparison_exp(
                _eq = Optional.Present(Farm_enum_pig_lifecycle_status_enum.ACTIVE))),
            location = Optional.Present(Farm_view_pen_index_bool_exp(
                unit_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitId))))))
        val seen = HashSet<String>()
        val penGroups = linkedMapOf<String, Pair<Int, Int>>()
        var unassigned = 0
        var offset = 0
        var expected: Int? = null
        do {
            val data = executor.query(NativeHomeUnitPenRosterQuery(where, offset, 200))
            check(lease)
            val count = data.count.aggregate?.count ?: throw ApiFailure.Protocol()
            if (expected != null && expected != count) throw ApiFailure.Protocol()
            expected = count
            if (data.pigs.size > 200 || offset + data.pigs.size > count) throw ApiFailure.Protocol()
            data.pigs.forEach { pig ->
                val location = pig.location ?: throw ApiFailure.Protocol()
                if (pig.id.isBlank() || !seen.add(pig.id) || pig.farm_id != lease.scope.farmId ||
                    pig.lifecycle_status.rawValue != "ACTIVE" || location.unit_id != unitId)
                    throw ApiFailure.Protocol()
                val pen = location.pen_location?.takeIf(String::isNotBlank)
                if (pen == null) unassigned++ else {
                    val previous = penGroups[pen]
                    if (previous != null && previous.first != location.pen_number) throw ApiFailure.Protocol()
                    penGroups[pen] = location.pen_number to ((previous?.second ?: 0) + 1)
                }
            }
            offset += data.pigs.size
            if (offset < count && data.pigs.isEmpty()) throw ApiFailure.Protocol()
        } while (offset < requireNotNull(expected))
        if (seen.size != expected) throw ApiFailure.Protocol()
        val registered = mutableListOf<HomeRegisteredPen>()
        var registeredOffset = 0
        var registeredExpected: Int? = null
        do {
            val data = executor.query(NativeHomeUnitRegisteredPensQuery(
                lease.scope.farmId, unitId, registeredOffset, 200))
            check(lease)
            val count = data.count.aggregate?.count ?: throw ApiFailure.Protocol()
            if (registeredExpected != null && registeredExpected != count || count < 0 ||
                data.pens.size > 200 || registeredOffset + data.pens.size > count) throw ApiFailure.Protocol()
            registeredExpected = count
            data.pens.forEach { pen ->
                if (pen.farm_id != lease.scope.farmId || pen.unit_id != unitId) throw ApiFailure.Protocol()
                registered += HomeRegisteredPen(pen.id, pen.pen_location, pen.pen_number)
            }
            registeredOffset += data.pens.size
            if (registeredOffset < count && data.pens.isEmpty()) throw ApiFailure.Protocol()
        } while (registeredOffset < requireNotNull(registeredExpected))
        if (registered.size != registeredExpected) throw ApiFailure.Protocol()
        return HomeUnitPenRoster(unitId, requireNotNull(expected), unassigned,
            penGroups.map { (code, value) -> HomePenCount(code, value.first, value.second) }
                .sortedWith(compareBy({ it.number }, { it.code })),
            registered.sortedWith(compareBy({ it.number }, { it.code ?: "" }, { it.id })))
    }
}

class HomeUnitPenRosterReads(remote: HomeUnitPenRosterRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    private val fetch = remote
    suspend fun current(lease: ScopeLease, unitId: String, cacheOnly: Boolean = false): CachedResult<HomeUnitPenRoster> {
        require(unitId.isNotBlank())
        return snapshots.read(lease, "home_unit_pen_roster", unitId, cacheOnly,
            { fetch.current(lease, unitId) },
            { value -> JSONObject().put("format", 2).put("unit", value.unitId)
                .put("total", value.total).put("unassigned", value.unassigned)
                .put("pens", JSONArray().apply { value.pens.forEach { pen ->
                    put(JSONObject().put("code", pen.code).put("number", pen.number).put("pigs", pen.pigs))
                } }).put("registered", value.registeredPens?.let { rows -> JSONArray().apply {
                    rows.forEach { pen -> put(JSONObject().put("id", pen.id).put("code", pen.code)
                        .put("number", pen.number)) }
                } } ?: JSONObject.NULL).toString() },
            { encoded ->
                val root = JSONObject(encoded)
                val format = root.getInt("format")
                require(format in 1..2 && root.keys().asSequence().toSet() ==
                    if (format == 1) setOf("format", "unit", "total", "unassigned", "pens")
                    else setOf("format", "unit", "total", "unassigned", "pens", "registered"))
                val rows = root.getJSONArray("pens")
                HomeUnitPenRoster(root.getString("unit"), root.getInt("total"), root.getInt("unassigned"),
                    (0 until rows.length()).map { index ->
                        val pen = rows.getJSONObject(index)
                        require(pen.keys().asSequence().toSet() == setOf("code", "number", "pigs"))
                        HomePenCount(pen.getString("code"), pen.getInt("number"), pen.getInt("pigs"))
                    }, if (format == 1 || root.isNull("registered")) null else root.getJSONArray("registered").let {
                        array -> (0 until array.length()).map { index ->
                            val pen = array.getJSONObject(index)
                            require(pen.keys().asSequence().toSet() == setOf("id", "code", "number"))
                            HomeRegisteredPen(pen.getInt("id"), if (pen.isNull("code")) null
                                else pen.getString("code"), pen.getInt("number"))
                        }
                    })
            }) { value -> require(value.unitId == unitId) }
    }
}
