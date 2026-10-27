package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

enum class FarrowingPigletType { HEALTHY, MALFORMED, MUMMY, OTHER, STILLBORN, WEAK }
enum class FarrowingStatus { COMPLETED, FARROWING, NOT_FARROWING }
enum class PigletHandlingItem { CASTRATION, HEALTH_CARE, INFO_SUBMISSION, IRON_SUPPLEMENT, LIGAMENT_CUTTING, NASAL_DROPS, TAIL_DOCKING, TEETH_CLIPPING }
enum class PigletSex { FEMALE, MALE }
enum class FosterStage(val productionType: String) { FARROWING("farrowing"), PIGLET_PROCESSING("piglet"), LACTATION("lactation") }

interface CarePigInput : CommandInput {
    override val taskId: String
    val pigId: String
    override val targetPigIds: Set<String> get() = setOf(pigId)
    override val groupKeys: Set<String> get() = targetPigIds.map { "pig:$it" }.toSet()
}
data class PigletCount(val type: FarrowingPigletType, val count: Int) {
    init { require(count >= 0) }
    fun graphql() = ProductionFarrowingPigletCountInput(count, ProductionFarrowingPigletType.valueOf(type.name))
}
data class IdentityPigletInput(val birthWeight: BigDecimal?, val earTag: String?, val earNotch: String?, val breeder: Boolean = false, val sex: PigletSex?) {
    init { birthWeight?.let { require(it.signum() >= 0) } }
    fun graphql() = ProductionFarrowingPigletStrawberryInput(
        birth_weight = Optional.Present(birthWeight), ear_tag = Optional.Present(earTag), ear_notch = Optional.Present(earNotch),
        is_breeder = Optional.Present(breeder), sex = Optional.Present(sex?.let { ProductionPigSex.valueOf(it.name) }),
    )
}
data class SubmitFarrowingInput(override val taskId: String, override val pigId: String, val completed: Boolean, val counts: List<PigletCount>?, val submitAt: String?) : CarePigInput {
    override val operationName: String get() = "SubmitFarrowingPiglet"
    override val productionType: String get() = "farrowing"
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitFarrowingPigletCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, is_completed = completed, piglet_counts = Optional.Present(counts?.map { it.graphql() }), submit_at = Optional.Present(submitAt),
    )
}
data class SubmitPigletItemsInput(override val taskId: String, override val pigId: String, val items: List<PigletHandlingItem>, val submitAt: String?) : CarePigInput {
    override val operationName: String get() = "SubmitPigletHandlingItem"
    override val productionType: String get() = "piglet"
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitPigletHandlingItemCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, items = Optional.Present(items.map { ProductionPigletHandlingItemType.valueOf(it.name) }), submit_at = Optional.Present(submitAt),
    )
}
data class SubmitPigletInfoInput(override val taskId: String, override val pigId: String, val femaleCount: Int, val maleCount: Int, val identities: List<IdentityPigletInput>?,
    val totalWeight: BigDecimal?, val increaseReason: String?, val reduceReason: String?, val submitAt: String?) : CarePigInput {
    override val operationName: String get() = "SubmitPigletInfo"
    override val productionType: String get() = "piglet"
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && femaleCount >= 0 && maleCount >= 0); totalWeight?.let { require(it.signum() >= 0) }; submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitPigletInfoCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, female_counts = femaleCount, male_counts = maleCount,
        identity_piglets = Optional.Present(identities?.takeIf { it.isNotEmpty() }?.map { it.graphql() }), total_weight = Optional.Present(totalWeight),
        increase_reason = Optional.Present(increaseReason), reduce_reason = Optional.Present(reduceReason), submit_at = Optional.Present(submitAt),
    )
}
data class SubmitPigletWeightInput(override val taskId: String, override val pigId: String, val totalWeight: BigDecimal, val submitAt: String?) : CarePigInput {
    override val operationName: String get() = "SubmitPigletTotalWeight"
    override val productionType: String get() = "piglet"
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && totalWeight.signum() >= 0); submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitPigletTotalWeightCommandStrawberryInput(task_id = taskId, pig_id = pigId, total_weight = totalWeight, submit_at = Optional.Present(submitAt))
}
data class FosterPigletInput(override val taskId: String, override val pigId: String, val adoptPigId: String, val stage: FosterStage,
    val earTags: List<String>?, val earNotches: List<String>?, val noIdentityCount: Int, val transferAt: String?) : CarePigInput {
    override val operationName: String get() = "FosterPiglet"
    override val productionType: String get() = stage.productionType
    override val targetPigIds: Set<String> get() = setOf(pigId, adoptPigId)
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && adoptPigId.isNotBlank() && pigId != adoptPigId && noIdentityCount >= 0); transferAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionFosterPigletCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, adopt_pig_id = adoptPigId, ear_tags = Optional.Present(earTags), ear_notches = Optional.Present(earNotches),
        no_identity_count = Optional.Present(noIdentityCount), transfer_at = Optional.Present(transferAt),
    )
    fun legacyFarrowingGraphql(): ProductionFosterFarrowingPigletCommandStrawberryInput {
        require(stage == FosterStage.FARROWING)
        return ProductionFosterFarrowingPigletCommandStrawberryInput(task_id = taskId, pig_id = pigId, adopt_pig_id = adoptPigId,
            ear_tags = Optional.Present(earTags), ear_notches = Optional.Present(earNotches), no_identity_count = noIdentityCount, transfer_at = Optional.Present(transferAt))
    }
    fun legacyPigletGraphql(): ProductionFosterPigletHandlingCommandStrawberryInput {
        require(stage == FosterStage.PIGLET_PROCESSING)
        return ProductionFosterPigletHandlingCommandStrawberryInput(task_id = taskId, pig_id = pigId, adopt_pig_id = adoptPigId,
            ear_tags = Optional.Present(earTags), ear_notches = Optional.Present(earNotches), no_identity_count = noIdentityCount, transfer_at = Optional.Present(transferAt))
    }
}

data class IdentityPiglet(val sequence: Int, val birthWeight: BigDecimal?, val earTag: String?, val earNotch: String?, val sex: PigletSex?)
data class FarrowingRecord(val id: Int, val aliveCount: Int, val deadCount: Int, val submittedAt: String?, val operatorId: String?)
data class FarrowingResult(val status: FarrowingStatus, val disabled: Boolean, val adoptCount: Int, val fosterCount: Int, val noIdentityCount: Int,
    val firstAt: String?, val lastAt: String?, val matingAt: String?, val matingBy: String?, val reCheckAt: String?, val operatorId: String?,
    val identities: List<IdentityPiglet>, val counts: List<PigletCount>, val records: List<FarrowingRecord>)
data class FarrowingPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: FarrowingResult?)
data class FarrowingTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<FarrowingPig>) : ProductionTaskSnapshot {
    override val type: String get() = "FARROWING"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
data class PigletTodo(val type: PigletHandlingItem, val planDays: Int, val completedAt: String?, val operatorId: String?)
data class PigletResult(val disabled: Boolean, val farrowed: Boolean, val adoptCount: Int, val fosterCount: Int, val noIdentityCount: Int, val femaleCount: Int, val maleCount: Int,
    val litterNumber: Int, val lastFarrowingAt: String?, val totalWeight: BigDecimal?, val items: JsonValue?, val identities: List<IdentityPiglet>, val todo: List<PigletTodo>)
data class PigletPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: PigletResult?)
data class PigletTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<PigletPig>) : ProductionTaskSnapshot {
    override val type: String get() = "PIGLET_PROCESSING"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
