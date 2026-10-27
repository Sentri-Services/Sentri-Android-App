package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.network.JsonValue
import java.math.BigDecimal

enum class PigRole { BREEDER_BOAR, BREEDER_GILT, FATTENING, PIGLET, PRODUCTION_BOAR, PRODUCTION_SOW }
enum class PigProfileOrder { ID, LOCATION_IDENTITY }
data class PigProfileRequest(val filter: PigFilter = PigFilter(), val ids: Set<String>? = null,
    val roles: Set<PigRole> = emptySet(), val lifecycles: Set<PigLifecycle> = emptySet(), val batchId: String? = null,
    val order: PigProfileOrder = PigProfileOrder.ID, val offset: Int = 0, val includeCount: Boolean = true,
    val unitId: String? = null) {
    init { require(offset >= 0 && ids?.all(String::isNotBlank) != false &&
        (batchId == null || batchId.isNotBlank()) && (unitId == null || unitId.isNotBlank())) }
}
data class PigOperator(val id: String, val name: String?, val avatar: String?)
data class PigLocationName(val code: String, val nameCn: String, val nameEn: String)
data class PigProfileLocation(val zoneId: String, val unitId: String, val zoneLocation: String?, val unitLocation: String?,
    val sectionLocation: String?, val rowLocation: String?, val penLocation: String?, val penNumber: Int,
    val zone: PigLocationName?, val unit: PigLocationName?, val section: PigLocationName?, val rowCode: String?, val rowNumber: String?)
data class PigBatchTask(val id: String, val kind: String, val status: String)
data class PigProfileBatch(val id: String, val serial: String, val status: String, val lineName: String?,
    val tasks: List<PigBatchTask>, val farmId: String? = null)
data class PigSemenControl(val boarId: String, val farmId: String, val collectionStatus: String, val cooldownUntil: String?,
    val lastCollectedAt: String?, val manuallyMarked: Boolean, val markedSince: String?, val modifiedAt: String?,
    val offProduction: Boolean, val offProductionSince: String?)
data class DiseaseInfo(val id: String, val nameCn: String, val nameEn: String, val abbrCn: String, val abbrEn: String,
    val categoryCn: String, val categoryEn: String, val descriptionCn: String, val descriptionEn: String)
data class SymptomInfo(val id: String, val nameCn: String, val nameEn: String, val partCn: String, val partEn: String,
    val descriptionCn: String, val descriptionEn: String)
data class PigDisease(val id: String, val createdAt: String?, val operator: PigOperator?, val detail: DiseaseInfo?)
data class PigSymptom(val targetId: String, val id: String, val createdAt: String?, val operator: PigOperator?, val detail: SymptomInfo?)
data class PigTreatmentReference(val id: String, val nameCn: String, val nameEn: String, val detailCn: String, val detailEn: String)
data class PigTherapyReference(val treatment: PigTreatmentReference?, val medication: NamedReference?, val medicationCatalog: NamedReference?)
/** Null therapies means the prescription relationship is unavailable; empty means it is present with no therapies. */
data class PigPrescriptionReference(val id: String, val therapies: List<PigTherapyReference>?)
data class PigHealthHistory(val id: Int, val createdAt: String?, val event: String, val triage: String?, val remark: String?,
    val symptoms: JsonValue?, val diseases: JsonValue?, val operatorId: String?, val operator: PigOperator?)
data class PigHealthProfile(val id: String, val farmId: String, val triage: String?, val triageChangedAt: String?,
    val triageOperator: PigOperator?, val temperature: BigDecimal?, val temperatureOperatorId: String?, val temperatureMeasuredAt: String?,
    val temperatureOperator: PigOperator?, val modifiedAt: String?, val prescriptionCount: Int?,
    val prescriptions: List<PigPrescriptionReference>, val diseases: List<PigDisease>, val symptoms: List<PigSymptom>, val history: List<PigHealthHistory>)
/** Union of legacy full list/detail, single-pig and production-task pig selections; raw server codes are retained. */
data class PigProfile(val id: String, val farmId: String, val earTag: String?, val earNotch: String?, val extendedEarTag: String?,
    val role: String, val sex: String?, val lifecycle: String, val origin: String, val locationCode: String?,
    val birthday: String?, val entryDate: String?, val remark: String?, val weight: BigDecimal?, val parity: Int?,
    val breedType: String?, val breederCode: String?, val teatCount: Int?, val firstEstrusAt: String?, val lastEstrusAt: String?,
    val lastFarrowingAt: String?, val lastLactationAt: String?, val lastMatingAt: String?, val productionStatus: String?,
    val currentBatchId: String?, val breederProductionStatus: String?, val batch: PigProfileBatch?,
    val semen: PigSemenControl?, val location: PigProfileLocation?, val health: PigHealthProfile?, val deathImages: JsonValue?,
    val breederStatusUpdatedAt: String? = null)
data class PigProfilePage(val request: PigProfileRequest, val pigs: List<PigProfile>, val total: Int?)
/** All active profiles in one registered unit, captured only after every remote page agrees. */
data class UnitPigProfileRoster(val unitId: String, val pigs: List<PigProfile>, val total: Int)
interface PigProfileRemote {
    suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage
    /** Scan identity must match exactly; substring search is only for the manual Find flow. */
    suspend fun exactEarCode(lease: ScopeLease, code: String): List<PigProfile> =
        throw UnsupportedOperationException("Exact scanner lookup is unavailable")
}
