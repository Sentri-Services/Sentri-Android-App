package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.BiosafetySubmitUnitInspectionCommandStrawberryInput
import com.sentri.nativeapp.graphql.type.BiosafetySubmitUnitRemarkCommandStrawberryInput
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

/** Decimal text remains exact in the offline payload; the GraphQL Float contract transmits a Double. */
data class SubmitUnitInspectionInput(
    val unitCode: String,
    val unitInspectionId: String? = null,
    val temperature: BigDecimal? = null,
    val humidity: BigDecimal? = null,
    val ammonia: BigDecimal? = null,
    val co2: BigDecimal? = null,
    val illumination: String? = null,
    val ventilation: String? = null,
    val waterConsumption: BigDecimal? = null,
    val feedConsumption: BigDecimal? = null,
    val environmentRemarks: String? = null,
    val cleanliness: String? = null,
    val compliance: String? = null,
    val bioRemarks: String? = null,
) : CommandInput {
    init {
        require(unitCode.isNotBlank()); require(unitInspectionId == null || unitInspectionId.isNotBlank())
        listOf(temperature, humidity, ammonia, co2, waterConsumption, feedConsumption).filterNotNull().forEach { value ->
            val converted = value.toDouble()
            require(converted.isFinite() && (value.signum() == 0 || converted != 0.0)) {
                "GraphQL Float cannot represent this measurement"
            }
        }
    }
    override val operationName = "SubmitUnitInspection"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    // Check-ins with stable client IDs are separate walks. A later walk must not wait for an earlier ambiguous receipt.
    override val groupKeys get() = setOf(if (unitInspectionId == null) "unitInspection:$unitCode"
        else "unitCheckIn:$unitInspectionId")
    fun graphql(): BiosafetySubmitUnitInspectionCommandStrawberryInput {
        fun decimal(value: BigDecimal?): Optional<Double?> = Optional.Present(value?.toDouble())
        return BiosafetySubmitUnitInspectionCommandStrawberryInput(unit_code = unitCode,
            unit_inspection_id = Optional.Present(unitInspectionId),
            temperature = decimal(temperature), humidity = decimal(humidity), ammonia = decimal(ammonia),
            co2 = decimal(co2), illumination = Optional.Present(illumination),
            ventilation = Optional.Present(ventilation), water_consumption = decimal(waterConsumption),
            feed_consumption = decimal(feedConsumption), env_remarks = Optional.Present(environmentRemarks),
            cleanliness = Optional.Present(cleanliness), compliance = Optional.Present(compliance),
            bio_remarks = Optional.Present(bioRemarks))
    }
}

data class SubmitUnitRemarkInput(val unitCode: String, val remark: String, val unitInspectionId: String? = null) : CommandInput {
    init { require(unitCode.isNotBlank()); require(unitInspectionId == null || unitInspectionId.isNotBlank()) }
    override val operationName = "SubmitUnitRemark"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    override val groupKeys get() = setOf("unitInspection:$unitCode")
    fun graphql() = BiosafetySubmitUnitRemarkCommandStrawberryInput(unit_code = unitCode, remark = remark,
        unit_inspection_id = Optional.Present(unitInspectionId))
}

data class UnitInspectionRecord(
    val id: String, val farmId: String, val unitCode: String, val createdAt: String?, val operatorId: String?,
    val temperature: BigDecimal?, val humidity: BigDecimal?, val ammonia: BigDecimal?, val co2: BigDecimal?,
    val illumination: String?, val ventilation: String?, val waterConsumption: BigDecimal?,
    val feedConsumption: BigDecimal?, val environmentRemarks: String?, val cleanliness: String?,
    val compliance: String?, val bioRemarks: String?,
)
data class UnitRemarkRecord(val farmId: String, val unitCode: String, val remark: String,
    val createdAt: String?, val operatorId: String?)
