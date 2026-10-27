package com.sentri.nativeapp.sampling

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativeSamplingTasksQuery
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlSamplingReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : SamplingReadRemote {
    override suspend fun page(lease: ScopeLease, request: SamplingPageRequest): SamplingPage {
        check(lease)
        val values = executor.query(NativeSamplingTasksQuery(lease.scope.farmId, filter(request), order(),
            request.offset, request.limit + 1)).rows
        check(lease)
        val rows = values.map { task(it) }
        valid(rows.size <= request.limit + 1 && rows.map { it.id }.distinct().size == rows.size)
        verify { rows.forEach { SamplingReadValidation.task(lease.scope.farmId, it); require(request.accepts(it)) } }
        return SamplingPage(request, rows.take(request.limit), rows.size > request.limit).also {
            verify { SamplingReadValidation.page(lease.scope.farmId, request, it) }
        }
    }
    override suspend fun detail(lease: ScopeLease, id: String): SamplingTask? {
        require(id.isNotBlank()); check(lease)
        val values = executor.query(NativeSamplingTasksQuery(lease.scope.farmId,
            Farm_view_sampling_task_bool_exp(id = Optional.Present(String_comparison_exp(_eq = Optional.Present(id)))),
            order(), 0, 2)).rows
        check(lease); valid(values.size <= 1)
        return values.singleOrNull()?.let { task(it).also { result ->
            valid(result.id == id); verify { SamplingReadValidation.task(lease.scope.farmId, result) }
        } }
    }
    private fun filter(request: SamplingPageRequest): Farm_view_sampling_task_bool_exp =
        if (request.mode == SamplingReadMode.ALL) Farm_view_sampling_task_bool_exp() else {
            val unsampled = Farm_view_sampling_task_bool_exp(is_sampled = Optional.Present(
                Boolean_comparison_exp(_eq = Optional.Present(false))))
            val sampledInWindow = Farm_view_sampling_task_bool_exp(
                is_sampled = Optional.Present(Boolean_comparison_exp(_eq = Optional.Present(true))),
                sampled_at = Optional.Present(Timestamptz_comparison_exp(
                    _gte = Optional.Present(requireNotNull(request.windowStart)),
                    _lt = Optional.Present(requireNotNull(request.windowEnd)))))
            Farm_view_sampling_task_bool_exp(_or = Optional.Present(listOf(unsampled, sampledInWindow)))
        }
    private fun order() = listOf(Farm_view_sampling_task_order_by(created_at = Optional.Present(Order_by.desc)),
        Farm_view_sampling_task_order_by(id = Optional.Present(Order_by.asc)))
    private fun task(v: NativeSamplingTasksQuery.Row): SamplingTask {
        valid(v.targets_aggregate.aggregate?.count == v.targets.size)
        valid(v.reports_aggregate.aggregate?.count == v.reports.size)
        v.sampling_test?.let { valid(it.targets_aggregate.aggregate?.count == it.targets.size) }
        return SamplingTask(v.id, v.farm_id, v.sampling_id, v.sampling_test_id, v.created_at,
            v.created_by, v.disabled, v.used, v.is_sampled, v.sampled_at, v.sampled_by,
            v.remark, v.tested_at, v.tested_by, v.test_metric, v.negative_value,
            v.positive_value, v.test_value, v.test_result?.rawValue, v.test_report_ids, v.video_id,
            v.targets.map { target -> SamplingTarget(target.id, target.farm_id, target.sampling_test_id,
                target.target_id, target.target_type.rawValue,
                target.pig?.takeIf { target.target_type.rawValue == "PIG" }?.let { pig ->
                    SamplingPig(pig.id, pig.farm_id, pig.ear_tag, pig.ear_notch, pig.breeder_code,
                        pig.breed_type?.rawValue, pig.lifecycle_status.rawValue, pig.location_code,
                        pig.origin.rawValue, pig.sex?.rawValue, pig.location?.let { location ->
                            SamplingLocation(location.farm_id, location.zone_location, location.unit_location,
                                location.section_location, location.row_location, location.pen_location,
                                location.zone_id, location.unit_id, location.section_id, location.row_id,
                                location.group_id, location.id, location.pen_number,
                                location.zone?.let { SamplingName(it.code, it.name_cn, it.name_en) },
                                location.unit?.let { SamplingName(it.code, it.name_cn, it.name_en) },
                                location.section?.let { SamplingName(it.code, it.name_cn, it.name_en) },
                                location.row?.code, location.row?.row_number)
                        })
                }, target.user?.takeIf { target.target_type.rawValue == "PERSON" }?.let { user ->
                    SamplingUser(user.id, user.farm_id, user.first_name, user.last_name,
                        user.username, user.display_name, user.avatar_url)
                }) },
            v.sampling_test?.let { test -> SamplingTest(test.id, test.farm_id, test.sampling_id,
                test.sampling_code, test.project.rawValue, test.container.rawValue, test.created_at,
                test.targets.map { target -> SamplingTestTarget(target.id, target.farm_id,
                    target.sampling_test_id, target.remark, target.sample_type.rawValue,
                    target.target_type.rawValue, target.target_ids) }) },
            v.reports.map { index -> SamplingReport(index.id, index.farm_id, index.sampling_task_id,
                index.report_id, index.report?.also { valid(it.id == index.report_id && it.farm_id == v.farm_id) }?.content_type,
                index.report?.created_at, index.report?.name, index.report?.operator_id) })
    }
    private fun valid(ok: Boolean) { if (!ok) throw ApiFailure.Protocol() }
    private fun verify(block: () -> Unit) { try { block() } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() } }
}
