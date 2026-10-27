package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.ScopeLease

enum class SamplingReadMode { ALL, UNSAMPLED_OR_SAMPLED_IN_WINDOW }
data class SamplingPageRequest(val mode: SamplingReadMode = SamplingReadMode.ALL,
    val windowStart: String? = null, val windowEnd: String? = null,
    val offset: Int = 0, val limit: Int = 50) {
    init {
        require(offset >= 0 && limit in 1..200)
        if (mode == SamplingReadMode.ALL) require(windowStart == null && windowEnd == null)
        else {
            require(windowStart != null && windowEnd != null)
            TimestampAdapter.validate(windowStart!!); TimestampAdapter.validate(windowEnd!!)
            require(TimestampInstant.seconds(windowEnd) > TimestampInstant.seconds(windowStart))
        }
    }
    fun accepts(task: SamplingTask): Boolean = mode == SamplingReadMode.ALL ||
        !task.isSampled || task.sampledAt?.let {
            val value = TimestampInstant.seconds(it)
            value >= TimestampInstant.seconds(requireNotNull(windowStart)) &&
                value < TimestampInstant.seconds(requireNotNull(windowEnd))
        } == true
}
data class SamplingPage(val request: SamplingPageRequest, val rows: List<SamplingTask>, val hasMore: Boolean)
data class SamplingName(val code: String, val chinese: String, val english: String)
data class SamplingLocation(val farmId: String, val zoneCode: String?, val unitCode: String?,
    val sectionCode: String?, val rowCode: String?, val penCode: String?,
    val zoneId: String, val unitId: String, val sectionId: String, val rowId: String,
    val groupId: String, val id: Int, val penNumber: Int,
    val zone: SamplingName?, val unit: SamplingName?, val section: SamplingName?,
    val rowCodeValue: String?, val rowNumber: String?)
data class SamplingPig(val id: String, val farmId: String?, val earTag: String?, val earNotch: String?,
    val breederCode: String?, val breedType: String?, val lifecycleStatus: String,
    val locationCode: String?, val origin: String?, val sex: String?, val location: SamplingLocation?)
data class SamplingUser(val id: String, val farmId: String, val firstName: String?, val lastName: String?,
    val username: String?, val displayName: String?, val avatarUrl: String?)
/** targetId is opaque for ENVIRONMENT, MATERIAL, and OTHER; a missing pig/user relation is not a missing target. */
data class SamplingTarget(val id: Int, val farmId: String, val samplingTestId: String,
    val targetId: String, val type: String, val pig: SamplingPig?, val user: SamplingUser?)
data class SamplingTestTarget(val id: String, val farmId: String, val samplingTestId: String,
    val remark: String?, val sampleType: String, val targetType: String, val targetIds: JsonValue?)
data class SamplingTest(val id: String, val farmId: String, val samplingId: String,
    val samplingCode: String, val project: String, val container: String,
    val createdAt: String?, val targets: List<SamplingTestTarget>)
data class SamplingReport(val indexId: Int, val farmId: String, val samplingTaskId: String,
    val reportId: String, val contentType: String?, val createdAt: String?,
    val name: String?, val operatorId: String?)
data class SamplingTask(val id: String, val farmId: String, val samplingId: String,
    val samplingTestId: String, val createdAt: String?, val createdBy: String?,
    val disabled: Boolean, val used: Boolean, val isSampled: Boolean,
    val sampledAt: String?, val sampledBy: String?, val remark: String?,
    val testedAt: String?, val testedBy: String?, val testMetric: String?,
    val negativeValue: String?, val positiveValue: String?, val testValue: String?,
    val testResult: String?, val testReportIds: JsonValue?, val videoId: String?,
    val targets: List<SamplingTarget>, val samplingTest: SamplingTest?, val reports: List<SamplingReport>)

interface SamplingReadRemote {
    suspend fun page(lease: ScopeLease, request: SamplingPageRequest): SamplingPage
    suspend fun detail(lease: ScopeLease, id: String): SamplingTask?
}
