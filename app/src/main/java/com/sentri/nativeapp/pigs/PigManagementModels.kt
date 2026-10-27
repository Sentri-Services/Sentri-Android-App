package com.sentri.nativeapp.pigs

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput
import com.sentri.nativeapp.production.PigletSex
import java.math.BigDecimal

enum class PigLifecycle { ACTIVE, DEAD, MISSING, REMOVED, SOLD }
enum class BreederRoleAction { CANCEL_TO_FATTENING, CANCEL_TO_PIGLET, MARK_AS_BREEDER, UPGRADE_TO_PRODUCTION }
/** Nulls retain the existing backend's no-change semantics; empty remark and zero values remain explicit. */
data class UpdatePigPropertyInput(val pigId: String, val lifecycle: PigLifecycle? = null, val sex: PigletSex? = null,
    val teats: Int? = null, val weight: BigDecimal? = null, val remark: String? = null) : CommandInput {
    override val operationName: String get() = "UpdatePigProperty"
    override val productionType: String get() = "unknown"
    override val taskId: String? get() = null
    override val targetPigIds: Set<String> get() = setOf(pigId)
    init { require(pigId.isNotBlank()) }
    fun graphql() = ProductionUpdatePigPropertyCommandStrawberryInput(pig_id = pigId,
        lifecycle_status = Optional.Present(lifecycle?.let { ProductionPigLifecycleStatus.valueOf(it.name) }),
        sex = Optional.Present(sex?.let { ProductionPigSex.valueOf(it.name) }), teats = Optional.Present(teats),
        weight = Optional.Present(weight), remark = Optional.Present(remark))
}
data class BreederPigInput(val pigId: String, val earTag: String?, val earNotch: String?, val sex: PigletSex?,
    val motherId: String?, val fatherId: String?) {
    init { require(pigId.isNotBlank() && listOfNotNull(motherId, fatherId).all(String::isNotBlank)) }
    fun graphql() = ProductionBreederPigStrawberryInput(pig_id = pigId, ear_tag = Optional.Present(earTag), ear_notch = Optional.Present(earNotch),
        sex = Optional.Present(sex?.let { ProductionPigSex.valueOf(it.name) }), mother_id = Optional.Present(motherId), father_id = Optional.Present(fatherId))
}
data class UpdateBreederRoleInput(val action: BreederRoleAction, val pigs: List<BreederPigInput>, val markedAt: String?) : CommandInput {
    override val operationName: String get() = "UpdateBreederRole"
    override val productionType: String get() = "unknown"
    override val taskId: String? get() = null
    override val targetPigIds: Set<String> get() = pigs.map { it.pigId }.toSet()
    init { markedAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionUpdateBreederRoleCommandStrawberryInput(action = ProductionBreederRoleAction.valueOf(action.name),
        breeder_pigs = pigs.map(BreederPigInput::graphql), marked_at = Optional.Present(markedAt))
}
