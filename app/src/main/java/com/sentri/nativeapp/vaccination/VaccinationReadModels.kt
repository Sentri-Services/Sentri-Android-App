package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.ScopeLease
import java.math.BigDecimal

enum class VaccinationReadMode { ALL, ACTIVE, ACTIVE_OR_COMPLETED_IN_WINDOW }
enum class VaccinationReadOrder { CREATED_DESC, ID_ASC }
data class VaccinationPageRequest(val mode: VaccinationReadMode = VaccinationReadMode.ALL,
    val windowStart: String? = null, val windowEnd: String? = null,
    val order: VaccinationReadOrder = VaccinationReadOrder.CREATED_DESC,
    val offset: Int = 0, val limit: Int = 50) {
    init {
        require(offset >= 0 && limit in 1..200)
        if (mode != VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW) require(windowStart == null && windowEnd == null)
        else {
            require(windowStart != null && windowEnd != null)
            TimestampAdapter.validate(windowStart!!); TimestampAdapter.validate(windowEnd!!)
            require(TimestampInstant.seconds(windowEnd) > TimestampInstant.seconds(windowStart))
        }
    }
    fun accepts(task: VaccinationTask): Boolean = mode == VaccinationReadMode.ALL ||
        (mode == VaccinationReadMode.ACTIVE && task.status in setOf("PENDING", "IN_PROGRESS")) ||
        (mode == VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW && task.status != "DELETED" &&
            (task.status != "COMPLETED" || task.modifiedAt?.let {
            val value = TimestampInstant.seconds(it)
            value >= TimestampInstant.seconds(requireNotNull(windowStart)) &&
                value < TimestampInstant.seconds(requireNotNull(windowEnd))
        } == true))
}
data class VaccinationPage(val request: VaccinationPageRequest, val rows: List<VaccinationTask>, val hasMore: Boolean)
data class VaccinationName(val code: String, val chinese: String, val english: String)
data class VaccinationLocation(val farmId: String, val penLocation: String?, val rowLocation: String?,
    val sectionLocation: String?, val unitLocation: String?, val zoneLocation: String?, val penNumber: Int,
    val zone: VaccinationName?, val section: VaccinationName?, val unit: VaccinationName?, val rowCode: String?)
data class VaccinationPig(val id: String, val farmId: String?, val earTag: String?, val earNotch: String?,
    val birthday: String?, val breedType: String?, val role: String?, val lifecycleStatus: String,
    val locationCode: String?, val currentBatchId: String?, val currentBatchSerial: String?,
    val location: VaccinationLocation?)
data class VaccinationVaccine(val id: String, val farmId: String, val chinese: String, val english: String, val status: String)
data class VaccinationBrand(val id: String, val farmId: String, val vaccineId: String,
    val chinese: String, val english: String, val dosageForm: String, val status: String)
data class VaccinationExemption(val farmId: String, val pigId: String, val hit: Boolean,
    val ruleCodes: JsonValue?, val reasons: JsonValue?, val evaluatedAt: String?,
    val effectiveUntil: String?, val modifiedAt: String?, val operatorId: String?)
/** One row per actual dose; a target is not complete merely because an earlier dose was executed. */
data class VaccinationTargetDose(val farmId: String, val taskId: String, val targetId: String,
    val vaccineId: String, val brandId: String, val doseIndex: Int, val status: String,
    val scheduledAt: String?, val executedAt: String?, val executedBy: String?, val operatorId: String?,
    val pig: VaccinationPig?, val exemption: VaccinationExemption? = null,
    val exemptionQueried: Boolean = false)
data class VaccinationTask(val id: String, val farmId: String, val vaccineId: String, val brandId: String,
    val status: String, val scheduledAt: String?, val modifiedAt: String?, val createdAt: String?,
    val completedAt: String?, val executedAt: String?, val executedBy: String?, val operatorId: String?,
    val route: String, val intervalValue: Int?, val intervalUnit: String?, val doseUnit: String,
    val doseCount: Int, val dose: BigDecimal, val completedTargetDoseCount: Int,
    val exemptedTargetCount: Int, val settledTargetCount: Int, val targetCount: Int,
    val taskType: String, val planId: String?, val relatedTaskId: String?, val effectReview: JsonValue?,
    val isDeleted: Boolean, val deletedAt: String?, val vaccine: VaccinationVaccine?,
    val brand: VaccinationBrand?, val targets: List<VaccinationTargetDose>)

interface VaccinationReadRemote {
    suspend fun page(lease: ScopeLease, request: VaccinationPageRequest): VaccinationPage
    suspend fun detail(lease: ScopeLease, id: String): VaccinationTask?
}
