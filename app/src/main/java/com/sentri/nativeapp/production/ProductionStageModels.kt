package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

enum class ActivityLevel { LAME, NORMAL, UNWILLING_TO_MOVE }
enum class BackfatThickness { MODERATE, THICK, THIN }
enum class BodyConditionScore { SCORE_1, SCORE_2, SCORE_3, SCORE_4, SCORE_5 }
enum class FeedingSituation { NORMAL, REDUCED_INTAKE, REFUSAL_TO_EAT }
enum class MastitisSeverity { MILD, MODERATE, NO_MASSTITIS, SEVERE }
enum class MilkProductionCapacity { GOOD, MEDIUM, POOR }
enum class SecretionStatus { ABNORMAL, NORMAL }
enum class ProlapseType { NONE, RECTAL, UTERINE, VAGINAL }
data class SowAssessment(val activity: ActivityLevel, val backfat: BackfatThickness, val body: BodyConditionScore,
    val feeding: FeedingSituation, val mastitis: MastitisSeverity, val milk: MilkProductionCapacity, val secretion: SecretionStatus,
    val prolapse: ProlapseType?, val recurred: Boolean?, val resolved: Boolean?) {
    fun graphql() = ProductionPostpartumCheckStatusInput(
        activity_level = ProductionActivityLevel.valueOf(activity.name), backfat_thickness = ProductionBackfatThickness.valueOf(backfat.name),
        body_condition_score = ProductionBodyConditionScore.valueOf(body.name), feeding_situation = ProductionFeedingSituation.valueOf(feeding.name),
        mastitis_severity = ProductionMastitisSeverity.valueOf(mastitis.name), milk_production_capacity = ProductionMilkProductionCapacity.valueOf(milk.name),
        secretion_status = ProductionSecretionStatus.valueOf(secretion.name), prolapse_type = Optional.Present(prolapse?.let { ProductionProlapseType.valueOf(it.name) }),
        prolapse_recurred = Optional.Present(recurred), prolapse_resolved = Optional.Present(resolved),
    )
}
data class SubmitPostpartumInput(override val taskId: String, override val pigId: String, val assessment: SowAssessment,
    val checkedAt: String?, val description: String?, val obsoleted: Boolean, val obsoletedReason: String?) : CarePigInput {
    override val operationName: String get() = "SubmitPostpartumPigStatus"
    override val productionType: String get() = "postpartum"
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); checkedAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitPostpartumPigStatusCommandStrawberryInput(task_id = taskId, pig_id = pigId, check_status = assessment.graphql(),
        check_at = Optional.Present(checkedAt), description = Optional.Present(description), is_obsoleted = Optional.Present(obsoleted), obsoleted_reason = Optional.Present(obsoletedReason))
}
data class SubmitLactationInput(override val taskId: String, override val pigId: String, val assessment: SowAssessment?,
    val checkedAt: String?, val description: String?, val obsoleted: Boolean, val obsoletedReason: String?, val femaleCount: Int, val maleCount: Int,
    val totalWeight: BigDecimal?, val identities: List<IdentityPigletInput>?, val counts: List<PigletCount>?) : CarePigInput {
    override val operationName: String get() = "SubmitLactationPigStatus"
    override val productionType: String get() = "lactation"
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && femaleCount >= 0 && maleCount >= 0); totalWeight?.let { require(it.signum() >= 0) }; checkedAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitLactationPigStatusCommandStrawberryInput(task_id = taskId, pig_id = pigId, check_status = Optional.Present(assessment?.graphql()),
        check_at = Optional.Present(checkedAt), description = Optional.Present(description), is_obsoleted = Optional.Present(obsoleted), obsoleted_reason = Optional.Present(obsoletedReason),
        female_count = Optional.Present(femaleCount), male_count = Optional.Present(maleCount), total_weight = Optional.Present(totalWeight),
        identity_piglets = Optional.Present(identities?.map { it.graphql() }), piglet_counts = Optional.Present(counts?.map { it.graphql() }))
}
enum class GrowingStage(val productionType: String) { NURSERY("nursery"), FATTENING("growing") }
data class GrowingAssessment(val healthyCount: Int, val malformedCount: Int, val femaleCount: Int, val maleCount: Int, val totalWeight: BigDecimal, val weakCount: Int) {
    init { require(listOf(healthyCount, malformedCount, femaleCount, maleCount, weakCount).all { it >= 0 } && totalWeight.signum() >= 0) }
    fun nurseryGraphql() = ProductionNurseryCheckStatusStrawberryInput(healthy_count = Optional.Present(healthyCount), malformed_count = Optional.Present(malformedCount),
        sex_female_count = Optional.Present(femaleCount), sex_male_count = Optional.Present(maleCount), total_weight = Optional.Present(totalWeight), weak_count = Optional.Present(weakCount))
    fun fatteningGraphql() = ProductionFatteningCheckStatusStrawberryInput(healthy_count = Optional.Present(healthyCount), malformed_count = Optional.Present(malformedCount),
        sex_female_count = Optional.Present(femaleCount), sex_male_count = Optional.Present(maleCount), total_weight = Optional.Present(totalWeight), weak_count = Optional.Present(weakCount))
}
data class GrowingIdentityInput(val pigId: String, val locationCode: String, val hasIdentity: Boolean, val breeder: Boolean,
    val earTag: String?, val earNotch: String?, val sex: PigletSex?, val weight: BigDecimal?) {
    init { require(pigId.isNotBlank() && locationCode.isNotBlank()); weight?.let { require(it.signum() >= 0) } }
    fun nurseryGraphql() = ProductionNurseryPigStrawberryInput(pig_id = pigId, location_code = locationCode, has_identity = Optional.Present(hasIdentity), is_breeder = Optional.Present(breeder),
        ear_tag = Optional.Present(earTag), ear_notch = Optional.Present(earNotch), sex = Optional.Present(sex?.let { ProductionPigSex.valueOf(it.name) }), weight = Optional.Present(weight))
    fun fatteningGraphql() = ProductionFatteningPigStrawberryInput(pig_id = pigId, location_code = locationCode, has_identity = Optional.Present(hasIdentity), is_breeder = Optional.Present(breeder),
        ear_tag = Optional.Present(earTag), ear_notch = Optional.Present(earNotch), sex = Optional.Present(sex?.let { ProductionPigSex.valueOf(it.name) }), weight = Optional.Present(weight))
}
/** One server command per task/location, including its aggregate and ordered identity list. */
data class CheckGrowingInput(override val taskId: String, val locationCode: String, val stage: GrowingStage, val assessment: GrowingAssessment,
    val identities: List<GrowingIdentityInput>?, val checkedAt: String?) : CommandInput {
    override val operationName: String get() = if (stage == GrowingStage.NURSERY) "CheckNurseryPigStatus" else "CheckFatteningPigStatus"
    override val productionType: String get() = stage.productionType
    override val targetPigIds: Set<String> get() = identities.orEmpty().map { it.pigId }.toSet()
    override val groupKeys: Set<String> get() = setOf("pigStatus:${taskId.length}:$taskId${locationCode.length}:$locationCode") + targetPigIds.map { "pig:$it" }
    init { require(taskId.isNotBlank() && locationCode.isNotBlank()); checkedAt?.let(TimestampAdapter::validate) }
    fun nurseryGraphql(): ProductionCheckNurseryPigStatusCommandStrawberryInput {
        require(stage == GrowingStage.NURSERY)
        return ProductionCheckNurseryPigStatusCommandStrawberryInput(task_id = taskId, location_code = locationCode, check_status = assessment.nurseryGraphql(),
            identity_pigs = Optional.Present(identities?.map { it.nurseryGraphql() }), check_at = Optional.Present(checkedAt))
    }
    fun fatteningGraphql(): ProductionCheckFatteningPigStatusCommandStrawberryInput {
        require(stage == GrowingStage.FATTENING)
        return ProductionCheckFatteningPigStatusCommandStrawberryInput(task_id = taskId, location_code = locationCode, check_status = assessment.fatteningGraphql(),
            identity_pigs = Optional.Present(identities?.map { it.fatteningGraphql() }), check_at = Optional.Present(checkedAt))
    }
}

data class SowCheckResult(val disabled: Boolean, val checkedAt: String?, val assessment: JsonValue?, val description: String?, val farrowingAt: String?,
    val farrowed: Boolean, val obsoleted: Boolean, val obsoletedReason: String?, val operatorId: String?)
data class PostpartumPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: SowCheckResult?)
data class PostpartumTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<PostpartumPig>) : ProductionTaskSnapshot {
    override val type: String get() = "POSTPARTUM"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
data class LactationIdentity(val piglet: IdentityPiglet, val breeder: Boolean)
data class LactationResult(val sow: SowCheckResult, val femaleCount: Int, val maleCount: Int, val noIdentityCount: Int, val initialTotalCount: Int?,
    val totalWeight: BigDecimal?, val identities: List<LactationIdentity>, val counts: List<PigletCount>)
data class LactationPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: LactationResult?)
data class LactationTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<LactationPig>) : ProductionTaskSnapshot {
    override val type: String get() = "LACTATION"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
data class GrowingPigResult(val hasIdentity: Boolean, val breeder: Boolean, val locationCode: String)
data class GrowingPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation,
    val sex: PigletSex?, val weight: BigDecimal?, val result: GrowingPigResult?)
data class NurseryLocation(val code: String, val checkedAt: String?, val createdAt: String?, val operatorId: String?, val totalCount: Int, val totalWeight: BigDecimal)
data class GrowingTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String,
    val stage: GrowingStage, val pigs: List<GrowingPig>, val locations: List<NurseryLocation>) : ProductionTaskSnapshot {
    override val type: String get() = stage.name
    override val pigIds: List<String> get() = pigs.map { it.id }
    init { require(stage == GrowingStage.NURSERY || locations.isEmpty()) }
}
