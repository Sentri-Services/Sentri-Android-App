package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter

/** Preserve polymorphic target identity; do not require a pig/user relation for material or environment rows. */
object SamplingReadValidation {
    private fun time(value: String?) { value?.let(TimestampAdapter::validate) }
    fun task(farmId: String, value: SamplingTask) {
        require(value.id.isNotBlank() && value.farmId == farmId && value.samplingId.isNotBlank() &&
            value.samplingTestId.isNotBlank())
        time(value.createdAt); time(value.sampledAt); time(value.testedAt)
        value.testReportIds?.let { JsonValueAdapter.validate(it.encoded) }
        require(value.targets.map { it.id }.distinct().size == value.targets.size)
        value.targets.forEach { target ->
            require(target.id >= 0 && target.farmId == farmId && target.samplingTestId == value.samplingTestId &&
                target.targetId.isNotBlank() && target.type.isNotBlank())
            target.pig?.let { pig ->
                require(target.type == "PIG" && pig.id == target.targetId && pig.farmId == farmId &&
                    pig.lifecycleStatus.isNotBlank())
                pig.location?.let { require(it.farmId == farmId) }
            }
            target.user?.let { require(target.type == "PERSON" && it.id == target.targetId && it.farmId == farmId) }
        }
        value.samplingTest?.let { test ->
            require(test.id == value.samplingTestId && test.farmId == farmId &&
                test.samplingId == value.samplingId && test.samplingCode.isNotBlank() &&
                test.project.isNotBlank() && test.container.isNotBlank())
            time(test.createdAt)
            require(test.targets.map { it.id }.distinct().size == test.targets.size)
            test.targets.forEach { target ->
                require(target.id.isNotBlank() && target.farmId == farmId && target.samplingTestId == test.id &&
                    target.sampleType.isNotBlank() && target.targetType.isNotBlank())
                target.targetIds?.let { JsonValueAdapter.validate(it.encoded) }
            }
        }
        require(value.reports.map { it.indexId }.distinct().size == value.reports.size)
        value.reports.forEach { report ->
            require(report.indexId >= 0 && report.farmId == farmId && report.samplingTaskId == value.id &&
                report.reportId.isNotBlank())
            time(report.createdAt)
        }
    }
    fun page(farmId: String, request: SamplingPageRequest, page: SamplingPage) {
        require(page.request == request && page.rows.size <= request.limit && (!page.hasMore || page.rows.size == request.limit))
        require(page.rows.map { it.id }.distinct().size == page.rows.size)
        page.rows.forEach { task(farmId, it); require(request.accepts(it)) }
    }
}
