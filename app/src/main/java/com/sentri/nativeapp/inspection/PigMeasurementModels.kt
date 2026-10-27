package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

enum class PigBackfatLevel { THIN, MODERATE, THICK }

sealed interface PigMeasurementCommandInput : CommandInput

data class PigWeightItem(val pigId: String, val weight: BigDecimal? = null,
    val earTag: String? = null, val locationCode: String? = null) {
    init { require(pigId.isNotBlank()) }
    fun graphql() = ProductionPigWeightItemInput(pig_id = pigId, weight = Optional.Present(weight),
        ear_tag = Optional.Present(earTag), location_code = Optional.Present(locationCode))
}

/** Null records, empty records and a total-only entry are distinct offline payloads. */
data class RecordPigWeightsInput(val records: List<PigWeightItem>?, val totalWeight: BigDecimal?,
    val measuredAt: String? = null) : PigMeasurementCommandInput {
    init {
        require(totalWeight != null || !records.isNullOrEmpty())
        measuredAt?.let(TimestampAdapter::validate)
    }
    override val operationName = "RecordPigWeights"
    override val productionType: String? = "unknown"
    override val taskId: String? = null
    override val targetPigIds get() = records.orEmpty().map { it.pigId }.toSet()
    override val groupKeys get() = super.groupKeys + (if (totalWeight != null) setOf("pigWeight:aggregate") else emptySet())
    fun graphql() = ProductionRecordPigWeightsInput(records = Optional.Present(records?.map(PigWeightItem::graphql)),
        total_weight = Optional.Present(totalWeight), measured_at = Optional.Present(measuredAt))
}

data class RecordPigBackfatInput(val pigId: String, val millimetres: BigDecimal?, val level: PigBackfatLevel?,
    val measuredAt: String? = null, val locationCode: String? = null, val remark: String? = null) : PigMeasurementCommandInput {
    init {
        require(pigId.isNotBlank() && (millimetres != null || level != null))
        measuredAt?.let(TimestampAdapter::validate)
        if (millimetres != null && level != null) require(level == when {
            millimetres < BigDecimal("15") -> PigBackfatLevel.THIN
            millimetres <= BigDecimal("25") -> PigBackfatLevel.MODERATE
            else -> PigBackfatLevel.THICK
        })
    }
    override val operationName = "RecordPigBackfat"
    override val productionType: String? = "unknown"
    override val taskId: String? = null
    override val targetPigIds = setOf(pigId)
    fun graphql() = ProductionRecordPigBackfatInput(pig_id = pigId,
        backfat_mm = Optional.Present(millimetres), backfat_level = Optional.Present(level?.let { ProductionPigBackfatLevel.valueOf(it.name) }),
        measured_at = Optional.Present(measuredAt), location_code = Optional.Present(locationCode), remark = Optional.Present(remark))
}

data class RecordPigTemperatureInput(val pigId: String, val temperature: BigDecimal,
    val unit: TemperatureUnit = TemperatureUnit.Celsius, val measuredAt: String? = null,
    val earTag: String? = null, val penLocation: String? = null,
    val triageLevel: PigTriageLevel? = null) : PigMeasurementCommandInput {
    init { require(pigId.isNotBlank()); measuredAt?.let(TimestampAdapter::validate) }
    override val operationName = "RecordPigTemperature"
    override val productionType: String? = "unknown"
    override val taskId: String? = null
    override val targetPigIds = setOf(pigId)
    fun graphql() = ProductionRecordPigTemperatureInput(pig_id = pigId, temperature = temperature,
        unit = Optional.Present(ProductionPigTemperatureUnit.valueOf(unit.name)), measured_at = Optional.Present(measuredAt),
        ear_tag = Optional.Present(earTag), pen_location = Optional.Present(penLocation),
        triage_level = Optional.Present(triageLevel?.let { ProductionPigTriageLevel.valueOf(it.name) }))
}

data class PigMeasurement(val pigId: String, val farmId: String, val weight: BigDecimal?,
    val backfatMm: BigDecimal?, val backfatLevel: String?, val backfatRecordedAt: String?,
    val bodyTemperature: BigDecimal?, val bodyTemperatureRecordedAt: String?)
