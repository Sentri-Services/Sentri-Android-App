package com.sentri.nativeapp.sampling

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.BiosafetySubmitSampledInput
import com.sentri.nativeapp.offline.CommandInput

/** The server takes only task ID and optional remark; UI operator/time are not command input. */
data class SubmitSampledInput(val samplingTaskId: String, val remark: String?) : CommandInput {
    override val operationName: String = "SubmitSampled"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds: Set<String> = emptySet()
    override val groupKeys: Set<String> get() = setOf("sampling:$samplingTaskId")
    init { require(samplingTaskId.isNotBlank()) }
    fun graphql() = BiosafetySubmitSampledInput(sampling_task_id = samplingTaskId,
        remark = Optional.Present(remark))
}
