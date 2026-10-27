package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.ProductionNonIdentityBatchAllocationStrawberryInput
import com.sentri.nativeapp.graphql.type.ProductionReconcileLocationInventoryCommandStrawberryInput
import com.sentri.nativeapp.offline.CommandInput

data class NonIdentityBatchAllocation(val batchId: String, val count: Int) {
    init { require(batchId.isNotBlank() && count >= 0) }
    fun graphql() = ProductionNonIdentityBatchAllocationStrawberryInput(batch_id = batchId, count = count)
}

/** A reconciliation may create or remove pigs. The caller must review a fresh headcount first. */
data class ReconcileLocationInventoryInput(
    val locationCode: String,
    val targetCount: Int,
    val nonIdentityAllocations: List<NonIdentityBatchAllocation>?,
    val reason: String? = null,
) : CommandInput {
    init {
        require(locationCode.isNotBlank() && targetCount >= 0)
        require(nonIdentityAllocations.orEmpty().map { it.batchId }.distinct().size == nonIdentityAllocations.orEmpty().size)
        require(nonIdentityAllocations.orEmpty().sumOf { it.count.toLong() } <= targetCount.toLong())
    }
    override val operationName = "ReconcileLocationInventory"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    override val groupKeys get() = setOf("locationInventory:$locationCode")
    fun graphql() = ProductionReconcileLocationInventoryCommandStrawberryInput(
        location_code = locationCode, target_count = targetCount,
        non_identity_allocations = Optional.Present(nonIdentityAllocations?.map(NonIdentityBatchAllocation::graphql)),
        reason = Optional.Present(reason))
}

data class LocationInventoryPig(val pigId: String, val farmId: String, val locationCode: String,
    val lifecycleStatus: String, val earTag: String?, val earNotch: String?, val batchId: String?) {
    val hasIdentity get() = !earTag.isNullOrEmpty() || !earNotch.isNullOrEmpty()
}

data class LocationInventorySnapshot(val locationCode: String, val pigs: List<LocationInventoryPig>) {
    val totalCount get() = pigs.size
    val identityCount get() = pigs.count(LocationInventoryPig::hasIdentity)
    val nonIdentityByBatch get() = pigs.filterNot(LocationInventoryPig::hasIdentity).groupingBy(LocationInventoryPig::batchId).eachCount()
}
