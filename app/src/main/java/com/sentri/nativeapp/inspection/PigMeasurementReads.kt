package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativePigMeasurementsQuery
import com.sentri.nativeapp.graphql.type.Farm_view_pig_bool_exp
import com.sentri.nativeapp.graphql.type.String_comparison_exp
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

interface PigMeasurementRemote { suspend fun measurements(lease: ScopeLease, pigIds: Set<String>): List<PigMeasurement> }

class GraphqlPigMeasurementReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : PigMeasurementRemote {
    override suspend fun measurements(lease: ScopeLease, pigIds: Set<String>): List<PigMeasurement> {
        require(pigIds.isNotEmpty() && pigIds.all(String::isNotBlank)); check(lease)
        val where = Farm_view_pig_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(lease.scope.farmId))),
            id = Optional.Present(String_comparison_exp(_in = Optional.Present(pigIds.sorted()))))
        val rows = executor.query(NativePigMeasurementsQuery(where)).farm_view_pig
        check(lease)
        val result = rows.map { PigMeasurement(it.id, it.farm_id ?: "", it.weight, it.backfat_mm,
            it.backfat_level, it.backfat_recorded_at, it.body_temperature, it.body_temperature_recorded_at) }
        try { PigMeasurementReadCodec.validate(result, lease.scope.farmId, pigIds) }
        catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        return result
    }
}

internal object PigMeasurementReadCodec {
    fun validate(rows: List<PigMeasurement>, farmId: String, pigIds: Set<String>) {
        require(rows.all { it.pigId.isNotBlank() && it.pigId in pigIds && it.farmId == farmId })
        require(rows.map(PigMeasurement::pigId).distinct().size == rows.size)
    }
    fun encode(rows: List<PigMeasurement>): String = JSONObject().put("format", 1).put("rows", JSONArray().apply {
        rows.forEach { row -> put(JSONObject().put("pig", row.pigId).put("farm", row.farmId)
            .put("weight", row.weight?.toString() ?: JSONObject.NULL)
            .put("mm", row.backfatMm?.toString() ?: JSONObject.NULL)
            .put("level", row.backfatLevel ?: JSONObject.NULL)
            .put("backfatAt", row.backfatRecordedAt ?: JSONObject.NULL)
            .put("temperature", row.bodyTemperature?.toString() ?: JSONObject.NULL)
            .put("temperatureAt", row.bodyTemperatureRecordedAt ?: JSONObject.NULL)) }
    }).toString()
    fun decode(encoded: String): List<PigMeasurement> {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "rows") && root.getInt("format") == 1)
        val rows = root.getJSONArray("rows")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("pig", "farm", "weight", "mm", "level",
                "backfatAt", "temperature", "temperatureAt"))
            fun optional(key: String) = if (row.isNull(key)) null else row.getString(key)
            fun decimal(key: String) = optional(key)?.let(::BigDecimal)
            PigMeasurement(row.getString("pig"), row.getString("farm"), decimal("weight"), decimal("mm"),
                optional("level"), optional("backfatAt"), decimal("temperature"), optional("temperatureAt"))
        }
    }
}

class PigMeasurementReads(private val remote: PigMeasurementRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun measurements(lease: ScopeLease, pigIds: Set<String>, cacheOnly: Boolean = false): CachedResult<List<PigMeasurement>> {
        require(pigIds.isNotEmpty() && pigIds.all(String::isNotBlank))
        val key = pigIds.sorted().joinToString("") { "${it.length}:$it" }
        return snapshots.read(lease, "pig_measurements", key, cacheOnly, { remote.measurements(lease, pigIds) },
            PigMeasurementReadCodec::encode, PigMeasurementReadCodec::decode) { rows ->
            PigMeasurementReadCodec.validate(rows, lease.scope.farmId, pigIds)
        }
    }
}
