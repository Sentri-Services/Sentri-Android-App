package com.sentri.nativeapp.transfer

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput

data class TransferTarget(val pigId: String, val sourceLocationCode: String) {
    init { require(pigId.isNotBlank()) }
    fun graphql() = ProductionTransferTargetStrawberryInput(pig_id = pigId, source_location_code = sourceLocationCode)
}

sealed interface TransferTaskCommandInput : CommandInput {
    val transferId: String
    override val taskId get() = transferId
    override val productionType get() = "transfer"
}

data class TransferOutInput(override val transferId: String, val transferTargets: List<TransferTarget>?,
    val removeTargets: List<TransferTarget>?, val missingTargets: List<TransferTarget>?) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank() && listOf(transferTargets, removeTargets, missingTargets).any { !it.isNullOrEmpty() }) }
    override val operationName = "TransferOut"
    override val targetPigIds get() = (transferTargets.orEmpty() + removeTargets.orEmpty() + missingTargets.orEmpty()).map { it.pigId }.toSet()
    fun graphql() = ProductionTransferOutCommandStrawberryInput(transfer_id = transferId,
        transfer_targets = Optional.Present(transferTargets?.map(TransferTarget::graphql)),
        remove_targets = Optional.Present(removeTargets?.map(TransferTarget::graphql)),
        missing_targets = Optional.Present(missingTargets?.map(TransferTarget::graphql)))
}

data class TransferInInput(override val transferId: String, val penLocation: String,
    val transferTargets: List<TransferTarget>?, val noIdentityCount: Int = 0) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank() && penLocation.isNotBlank() && noIdentityCount >= 0 &&
        (!transferTargets.isNullOrEmpty() || noIdentityCount > 0)) }
    override val operationName = "TransferIn"
    override val targetPigIds get() = transferTargets.orEmpty().map { it.pigId }.toSet()
    fun graphql() = ProductionTransferInCommandStrawberryInput(transfer_id = transferId,
        pen_location = penLocation, transfer_targets = Optional.Present(transferTargets?.map(TransferTarget::graphql)),
        no_identity_count = Optional.Present(noIdentityCount))
}

data class AddTransferTaskItemsInput(override val transferId: String, val targets: List<TransferTarget>) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank()) }
    override val operationName = "AddTransferTaskItems"
    override val targetPigIds get() = targets.map(TransferTarget::pigId).toSet()
    fun graphql() = ProductionAddTransferTaskItemsCommandStrawberryInput(transfer_id = transferId,
        transfer_targets = targets.map(TransferTarget::graphql))
}

data class RemoveTransferTaskItemsInput(override val transferId: String, val pigIds: List<String>) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank() && pigIds.all(String::isNotBlank)) }
    override val operationName = "RemoveTransferTaskItems"
    override val targetPigIds get() = pigIds.toSet()
    fun graphql() = ProductionRemoveTransferTaskItemsCommandStrawberryInput(transfer_id = transferId, pig_ids = pigIds)
}

data class ResetTransferTaskItemsInput(override val transferId: String, val targets: List<TransferTarget>) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank()) }
    override val operationName = "ResetTransferTaskItems"
    override val targetPigIds get() = targets.map(TransferTarget::pigId).toSet()
    fun graphql() = ProductionResetTransferTaskItemsCommandStrawberryInput(transfer_id = transferId,
        transfer_targets = targets.map(TransferTarget::graphql))
}

data class AddTransferInUnitInput(override val transferId: String, val unitLocations: List<String>) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank() && unitLocations.all(String::isNotBlank)) }
    override val operationName = "AddTransferInUnit"
    override val targetPigIds = emptySet<String>()
    fun graphql() = ProductionAddTransferInUnitCommandStrawberryInput(transfer_id = transferId, unit_locations = unitLocations)
}

data class CompleteTransferTaskInput(override val transferId: String, val completedAt: String?) : TransferTaskCommandInput {
    init { require(transferId.isNotBlank()); completedAt?.let(TimestampAdapter::validate) }
    override val operationName = "CompleteTransferTask"
    override val targetPigIds = emptySet<String>()
    fun graphql() = ProductionCompleteTransferTaskCommandStrawberryInput(transfer_id = transferId,
        completed_at = Optional.Present(completedAt))
}

/** The backend transfer_pig mutation has no transfer task ID. */
data class TransferPigInput(val pigId: String, val destination: String) : CommandInput {
    init { require(pigId.isNotBlank() && destination.isNotBlank()) }
    override val operationName = "TransferPig"
    override val productionType: String? = "transfer"
    override val taskId: String? = null
    override val targetPigIds = setOf(pigId)
    fun graphql() = ProductionTransferPigCommandStrawberryInput(pig_id = pigId, destination = destination)
}

data class TransferUnitRecord(val unitCode: String, val count: Int, val totalCount: Int?,
    val lastOperationTime: String?, val operatorId: String?)
data class TransferItemRecord(val pigId: String, val batchId: String?, val status: String,
    val sourceLocationCode: String?, val destinationLocationCode: String?, val actualTransferTime: String?,
    val failureReason: String?, val remark: String?, val createdAt: String?, val modifiedAt: String?,
    val operatorId: String?, val earTag: String?, val earNotch: String?, val currentLocationCode: String?,
    val role: String?, val lifecycleStatus: String?)
data class TransferTaskRecord(val transferId: String, val farmId: String, val batchId: String?,
    val batchSn: String?, val transferType: String, val status: String, val plannedTransferTime: String?,
    val actualStartTime: String?, val completedAt: String?, val createdAt: String?, val modifiedAt: String?,
    val remark: String?, val operatorId: String?, val inUnits: List<TransferUnitRecord>,
    val outUnits: List<TransferUnitRecord>, val items: List<TransferItemRecord>)
