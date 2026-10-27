package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.ProductionMateWithBoarCommandStrawberryInput
import com.sentri.nativeapp.graphql.type.ProductionMateWithSemenCommandStrawberryInput
import com.sentri.nativeapp.offline.CommandInput

data class MateWithBoarInput(override val taskId: String, val pigId: String, val boarId: String) : CommandInput {
    override val operationName: String get() = "MateWithBoar"
    override val productionType: String get() = "mating"
    override val targetPigIds: Set<String> get() = setOf(pigId)
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && boarId.isNotBlank()) }
    fun graphql() = ProductionMateWithBoarCommandStrawberryInput(
        task_id = taskId, mating_pig_id = pigId, boar_id = boarId, auto_check_estrus = Optional.Present(false),
    )
}
data class MateWithSemenInput(override val taskId: String, val pigId: String, val semenId: String) : CommandInput {
    override val operationName: String get() = "MateWithSemen"
    override val productionType: String get() = "mating"
    override val targetPigIds: Set<String> get() = setOf(pigId)
    init {
        require(taskId.isNotBlank() && pigId.isNotBlank() && semenId.isNotBlank())
        // Existing local batch/storage placeholders are not server semen identifiers.
        require(!semenId.startsWith("local-semen-batch:") && !semenId.startsWith("local-semen-storage:"))
    }
    fun graphql() = ProductionMateWithSemenCommandStrawberryInput(
        task_id = taskId, mating_pig_id = pigId, semen_id = semenId, auto_check_estrus = Optional.Present(false),
    )
}
data class MatingMaterial(val id: Int, val boarId: String?, val semenId: String?, val breederType: String?, val collectedAt: String?, val count: Int?, val boarEarTag: String?, val boarEarNotch: String?)
data class MatingRecord(val at: String?, val times: Int, val with: String, val operatorId: String?, val boar: MatingMaterial?, val semen: MatingMaterial?)
data class MatingSummary(val count: Int, val estrusAt: String?, val createdAt: String?, val firstAt: String?, val firstWith: String?, val lastAt: String?, val lastWith: String?)
data class MatingPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val summary: MatingSummary?, val records: List<MatingRecord>, val location: TaskPigLocation? = null)
data class MatingTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<MatingPig>) : ProductionTaskSnapshot {
    override val type: String get() = "MATING"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
