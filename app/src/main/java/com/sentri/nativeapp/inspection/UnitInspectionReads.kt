package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeUnitInspectionsQuery
import com.sentri.nativeapp.graphql.NativeUnitRemarksQuery
import com.sentri.nativeapp.graphql.type.Farm_view_unit_inspection_bool_exp
import com.sentri.nativeapp.graphql.type.Farm_view_unit_remark_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.graphql.type.Timestamptz_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

interface UnitInspectionRemote {
    suspend fun inspections(lease: ScopeLease, sinceInclusive: String?): List<UnitInspectionRecord>
    /** This read must come from the network: cached rows cannot confirm a write. */
    suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>): List<UnitInspectionRecord>
    suspend fun remarks(lease: ScopeLease, unitCode: String): List<UnitRemarkRecord>
}

class GraphqlUnitInspectionReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : UnitInspectionRemote {
    override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?): List<UnitInspectionRecord> {
        check(lease)
        val where = Farm_view_unit_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            created_at = if (sinceInclusive == null) Optional.Absent else Optional.Present(
                Timestamptz_comparison_exp(_gte = Optional.Present(sinceInclusive))))
        return read(lease, where)
    }
    override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>): List<UnitInspectionRecord> {
        require(ids.isNotEmpty() && ids.none(String::isBlank))
        val where = Farm_view_unit_inspection_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            id = Optional.Present(String_comparison_exp(_in = Optional.Present(ids.sorted()))))
        return read(lease, where).also { rows ->
            if (rows.any { it.id !in ids }) throw ApiFailure.Protocol()
        }
    }
    private suspend fun read(lease: ScopeLease, where: Farm_view_unit_inspection_bool_exp): List<UnitInspectionRecord> {
        check(lease)
        val rows = executor.query(NativeUnitInspectionsQuery(where)).farm_view_unit_inspection
        check(lease)
        val result = rows.map { row -> UnitInspectionRecord(row.id, row.farm_id, row.unit_code,
            row.created_at, row.operator_id, row.temperature, row.humidity, row.ammonia, row.co2,
            row.illumination, row.ventilation, row.water_consumption, row.feed_consumption,
            row.env_remarks, row.cleanliness, row.compliance, row.bio_remarks) }
        if (result.any { it.id.isBlank() || it.farmId != lease.scope.farmId || it.unitCode.isBlank() } ||
            result.map { it.id }.distinct().size != result.size) throw ApiFailure.Protocol()
        return result
    }
    override suspend fun remarks(lease: ScopeLease, unitCode: String): List<UnitRemarkRecord> {
        require(unitCode.isNotBlank()); check(lease)
        val where = Farm_view_unit_remark_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            unit_code = Optional.Present(String_comparison_exp(_eq = Optional.Present(unitCode))))
        val rows = executor.query(NativeUnitRemarksQuery(where)).farm_view_unit_remark
        check(lease)
        return rows.map { UnitRemarkRecord(it.farm_id, it.unit_code, it.remark, it.created_at, it.operator_id) }
            .also { result -> if (result.any { it.farmId != lease.scope.farmId || it.unitCode != unitCode }) throw ApiFailure.Protocol() }
    }
}

internal object UnitInspectionReadCodec {
    private fun JSONObject.optional(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.decimal(key: String) = optional(key)?.let(::BigDecimal)
    private fun JSONObject.putOptional(key: String, value: String?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.putDecimal(key: String, value: BigDecimal?) = putOptional(key, value?.toString())
    fun encodeInspections(rows: List<UnitInspectionRecord>): String = JSONObject().put("format", 1).put("rows", JSONArray().apply {
        rows.forEach { row -> put(JSONObject().put("id", row.id).put("farm", row.farmId).put("unit", row.unitCode)
            .putOptional("created", row.createdAt).putOptional("operator", row.operatorId)
            .putDecimal("temperature", row.temperature).putDecimal("humidity", row.humidity)
            .putDecimal("ammonia", row.ammonia).putDecimal("co2", row.co2)
            .putOptional("illumination", row.illumination).putOptional("ventilation", row.ventilation)
            .putDecimal("water", row.waterConsumption).putDecimal("feed", row.feedConsumption)
            .putOptional("environmentRemarks", row.environmentRemarks).putOptional("cleanliness", row.cleanliness)
            .putOptional("compliance", row.compliance).putOptional("bioRemarks", row.bioRemarks)) }
    }).toString()
    fun decodeInspections(encoded: String): List<UnitInspectionRecord> {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "rows") && root.getInt("format") == 1)
        val rows = root.getJSONArray("rows")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "farm", "unit", "created", "operator", "temperature",
                "humidity", "ammonia", "co2", "illumination", "ventilation", "water", "feed", "environmentRemarks",
                "cleanliness", "compliance", "bioRemarks"))
            UnitInspectionRecord(row.getString("id"), row.getString("farm"), row.getString("unit"), row.optional("created"),
                row.optional("operator"), row.decimal("temperature"), row.decimal("humidity"), row.decimal("ammonia"),
                row.decimal("co2"), row.optional("illumination"), row.optional("ventilation"), row.decimal("water"),
                row.decimal("feed"), row.optional("environmentRemarks"), row.optional("cleanliness"), row.optional("compliance"),
                row.optional("bioRemarks"))
        }
    }
    fun encodeRemarks(rows: List<UnitRemarkRecord>): String = JSONObject().put("format", 1).put("rows", JSONArray().apply {
        rows.forEach { row -> put(JSONObject().put("farm", row.farmId).put("unit", row.unitCode).put("remark", row.remark)
            .putOptional("created", row.createdAt).putOptional("operator", row.operatorId)) }
    }).toString()
    fun decodeRemarks(encoded: String): List<UnitRemarkRecord> {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "rows") && root.getInt("format") == 1)
        val rows = root.getJSONArray("rows")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("farm", "unit", "remark", "created", "operator"))
            UnitRemarkRecord(row.getString("farm"), row.getString("unit"), row.getString("remark"),
                row.optional("created"), row.optional("operator"))
        }
    }
}

class UnitInspectionReads(private val remote: UnitInspectionRemote, cache: SnapshotCache,
    private val check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun inspections(lease: ScopeLease, sinceInclusive: String? = null, cacheOnly: Boolean = false): CachedResult<List<UnitInspectionRecord>> {
        sinceInclusive?.let(TimestampAdapter::validate)
        val key = sinceInclusive?.let { "${it.length}:$it" } ?: "all"
        return snapshots.read(lease, "unit_inspections", key, cacheOnly, { remote.inspections(lease, sinceInclusive) },
            UnitInspectionReadCodec::encodeInspections, UnitInspectionReadCodec::decodeInspections) { rows ->
            require(rows.all { it.id.isNotBlank() && it.unitCode.isNotBlank() && it.farmId == lease.scope.farmId })
            require(rows.map { it.id }.distinct().size == rows.size)
        }
    }
    /** A history row's details are read by exact ID and unit, independently of check-in confirmation. */
    suspend fun byId(lease: ScopeLease, unitCode: String, id: String,
        cacheOnly: Boolean = false): CachedResult<UnitInspectionRecord?> {
        require(unitCode.isNotBlank() && id.isNotBlank())
        val key = "${unitCode.length}:$unitCode:${id.length}:$id"
        return snapshots.read(lease, "unit_inspection_detail", key, cacheOnly,
            { remote.inspectionsByIds(lease, setOf(id)) },
            UnitInspectionReadCodec::encodeInspections, UnitInspectionReadCodec::decodeInspections) { rows ->
            if (rows.size > 1 || rows.any { it.id != id || it.farmId != lease.scope.farmId ||
                    it.unitCode != unitCode }) throw ApiFailure.Protocol()
            rows.forEach { it.createdAt?.let(TimestampAdapter::validate) }
        }.let { result -> CachedResult(result.value.singleOrNull(), result.source,
            result.fetchedAt, result.refreshFailure) }
    }
    suspend fun remarks(lease: ScopeLease, unitCode: String, cacheOnly: Boolean = false): CachedResult<List<UnitRemarkRecord>> {
        require(unitCode.isNotBlank())
        return snapshots.read(lease, "unit_remarks", unitCode, cacheOnly, { remote.remarks(lease, unitCode) },
            UnitInspectionReadCodec::encodeRemarks, UnitInspectionReadCodec::decodeRemarks) { rows ->
            require(rows.all { it.farmId == lease.scope.farmId && it.unitCode == unitCode })
        }
    }
    /** Only an exact network row from this farm and unit confirms a queued check-in. */
    suspend fun confirmedIds(lease: ScopeLease, expected: Map<String, String>): Set<String> {
        require(expected.isNotEmpty() && expected.all { (id, unit) -> id.isNotBlank() && unit.isNotBlank() })
        check(lease)
        val rows = remote.inspectionsByIds(lease, expected.keys)
        check(lease)
        if (rows.any { it.farmId != lease.scope.farmId || it.id !in expected || it.unitCode.isBlank() } ||
            rows.map { it.id }.distinct().size != rows.size) throw ApiFailure.Protocol()
        return rows.filter { expected[it.id] == it.unitCode }.mapTo(mutableSetOf()) { it.id }
    }
}
