package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.ScopeLease
import java.math.BigDecimal

/** Full list and today's offline prefetch have different server filters and cache identities. */
enum class TherapyReadMode { ALL, ACTIVE_OR_EXECUTED_IN_WINDOW }
enum class TherapyReadOrder { CREATED_DESC, ID_ASC }
data class TherapyPageRequest(
    val prescriptionId: String? = null,
    val statuses: Set<String> = emptySet(),
    val mode: TherapyReadMode = TherapyReadMode.ALL,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val order: TherapyReadOrder = TherapyReadOrder.CREATED_DESC,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    init {
        require(prescriptionId == null || prescriptionId.isNotBlank())
        require(statuses.all(String::isNotBlank) && offset >= 0 && limit in 1..200)
        if (mode == TherapyReadMode.ALL) require(windowStart == null && windowEnd == null)
        else {
            require(statuses.isEmpty() && windowStart != null && windowEnd != null)
            require(TimestampInstant.seconds(windowEnd!!) > TimestampInstant.seconds(windowStart!!))
        }
    }
    fun accepts(row: TherapyRow) = (prescriptionId == null || row.prescriptionId == prescriptionId) &&
        (statuses.isEmpty() || row.status in statuses) &&
        (mode == TherapyReadMode.ALL || row.status == "InProgress" || row.status == "NotStarted" || row.tasks.any {
            it.executedAt?.let { at ->
                val instant = TimestampInstant.seconds(at)
                instant >= TimestampInstant.seconds(requireNotNull(windowStart)) &&
                    instant < TimestampInstant.seconds(requireNotNull(windowEnd))
            } == true
        })
}

data class TherapyPage(val request: TherapyPageRequest, val rows: List<TherapyRow>, val hasMore: Boolean)
/** Complete membership from prescription relations, independently cached from therapy rows. */
data class TherapyPrescriptionContext(val id: String, val farmId: String, val status: String,
    val targetIds: Set<String>, val therapyStatuses: Map<String, String>)

data class TherapyName(val chinese: String, val english: String)
data class TherapyMedication(val id: String, val farmId: String, val chinese: String, val english: String)
data class TherapyMedicationCatalog(val id: String, val farmId: String, val medicationId: String,
    val chinese: String, val english: String, val formChinese: String?, val formEnglish: String?)
data class TherapyTreatment(val id: String, val chinese: String, val english: String,
    val detailChinese: String, val detailEnglish: String)
data class TherapyOperator(val displayName: String?, val avatarUrl: String?)
data class TherapyPrescription(val id: String, val farmId: String, val createdAt: String?, val operatorId: String?,
    val operator: TherapyOperator?, val symptoms: JsonValue?, val diseases: JsonValue?, val remark: String?,
    val status: String, val therapyCount: Int?)
data class TherapyPigLocation(val farmId: String, val penLocation: String?, val rowLocation: String?,
    val sectionLocation: String?, val unitLocation: String?, val zoneLocation: String?, val penNumber: Int,
    val zone: TherapyName?, val section: TherapyName?, val unit: TherapyName?, val rowCode: String?)
data class TherapyPig(val id: String, val farmId: String?, val breederCode: String?, val earNotch: String?,
    val earTag: String?, val lifecycleStatus: String, val locationCode: String?, val origin: String,
    val sex: String?, val breedType: String?, val birthday: String?, val teatCount: Int?, val role: String?,
    val currentBatchId: String?, val location: TherapyPigLocation?)
data class TherapyTargetTask(val farmId: String, val prescriptionId: String, val therapyId: String,
    val taskId: String, val targetId: String, val times: Int, val status: String, val scheduledAt: String?,
    val executedAt: String?, val executedBy: String?, val operatorId: String?, val remark: String?, val pig: TherapyPig?)
data class TherapyRow(val id: String, val farmId: String, val prescriptionId: String, val createdAt: String?,
    val dosage: BigDecimal, val interval: Int?, val medicationId: String, val medication: TherapyMedication?,
    val medicationCatalogId: String?, val medicationCatalog: TherapyMedicationCatalog?, val operatorId: String?,
    val remark: String?, val prescription: TherapyPrescription?, val startDate: String?, val status: String,
    val times: Int, val treatmentId: String, val treatment: TherapyTreatment?, val unit: String,
    val tasks: List<TherapyTargetTask>)

interface TherapyReadRemote {
    suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage
    suspend fun detail(lease: ScopeLease, id: String): TherapyRow?
    suspend fun medications(lease: ScopeLease): List<TherapyMedication>
    suspend fun catalogs(lease: ScopeLease): List<TherapyMedicationCatalog>
    suspend fun treatments(lease: ScopeLease): List<TherapyTreatment>
    suspend fun context(lease: ScopeLease, prescriptionId: String): TherapyPrescriptionContext?
}
