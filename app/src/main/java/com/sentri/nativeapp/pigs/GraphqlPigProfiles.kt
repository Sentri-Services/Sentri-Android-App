package com.sentri.nativeapp.pigs

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.NativePigProfilesQuery
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor

class GraphqlPigProfiles(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : PigProfileRemote {
    override suspend fun exactEarCode(lease: ScopeLease, code: String): List<PigProfile> {
        require(code.isNotBlank())
        check(lease)
        val request = PigProfileRequest(PigFilter(search = code, pageSize = 2),
            lifecycles = setOf(PigLifecycle.ACTIVE), includeCount = false)
        val exact = String_comparison_exp(_eq = Optional.Present(code))
        val where = where(lease.scope, request).copy(_or = Optional.Present(listOf(
            Farm_view_pig_bool_exp(ear_tag = Optional.Present(exact)),
            Farm_view_pig_bool_exp(ear_notch = Optional.Present(exact)),
            Farm_view_pig_bool_exp(ear_tag_ex = Optional.Present(exact)),
        )))
        val rows = executor.query(NativePigProfilesQuery(where, 0, 2, order(PigProfileOrder.ID), false)).pigs.map(::profile)
        check(lease)
        if (rows.map(PigProfile::id).distinct().size != rows.size || rows.any {
                it.farmId != lease.scope.farmId || it.lifecycle != PigLifecycle.ACTIVE.name ||
                    code !in listOf(it.earTag, it.earNotch, it.extendedEarTag)
            }) throw ApiFailure.Protocol()
        return rows
    }
    override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
        check(lease)
        val data = executor.query(NativePigProfilesQuery(where(lease.scope, request), request.offset, request.filter.pageSize, order(request.order), request.includeCount))
        check(lease)
        val page = PigProfilePage(request, data.pigs.map(::profile), if (request.includeCount) data.count?.aggregate?.count ?: throw ApiFailure.Protocol() else null)
        try { PigProfileValidation.page(lease.scope, request, page) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        return page
    }
    companion object {
        fun where(scope: DataScope, request: PigProfileRequest): Farm_view_pig_bool_exp = GraphqlPigRemote.where(scope, request.filter).let { base -> base.copy(
            location = request.unitId?.let { id -> Optional.Present(Farm_view_pen_index_bool_exp(
                unit_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(id))),
                pen_location = request.filter.penLocation?.let { Optional.Present(String_comparison_exp(_eq = Optional.Present(it))) } ?: Optional.Absent)) }
                ?: base.location,
            _or = if (request.filter.search.isEmpty()) base._or else Optional.Present(listOf(
                Farm_view_pig_bool_exp(ear_tag = Optional.Present(String_comparison_exp(_like = Optional.Present(GraphqlPigRemote.literalContains(request.filter.search))))),
                Farm_view_pig_bool_exp(ear_notch = Optional.Present(String_comparison_exp(_like = Optional.Present(GraphqlPigRemote.literalContains(request.filter.search))))),
                Farm_view_pig_bool_exp(ear_tag_ex = Optional.Present(String_comparison_exp(_like = Optional.Present(GraphqlPigRemote.literalContains(request.filter.search))))),
                Farm_view_pig_bool_exp(location = Optional.Present(Farm_view_pen_index_bool_exp(
                    pen_location = Optional.Present(String_comparison_exp(_like = Optional.Present(GraphqlPigRemote.literalContains(request.filter.search)))))))
            )),
            id = request.ids?.let { Optional.Present(String_comparison_exp(_in = Optional.Present(it.sorted()))) } ?: Optional.Absent,
            role = if (request.roles.isEmpty()) Optional.Absent else Optional.Present(Farm_enum_pig_role_enum_comparison_exp(_in = Optional.Present(request.roles.sortedBy { it.name }.map { Farm_enum_pig_role_enum.safeValueOf(it.name) }))),
            lifecycle_status = if (request.lifecycles.isEmpty()) Optional.Absent else Optional.Present(Farm_enum_pig_lifecycle_status_enum_comparison_exp(_in = Optional.Present(request.lifecycles.sortedBy { it.name }.map { Farm_enum_pig_lifecycle_status_enum.safeValueOf(it.name) }))),
            current_batch_id = request.batchId?.let { Optional.Present(String_comparison_exp(_eq = Optional.Present(it))) } ?: Optional.Absent,
        ) }
        private fun order(value: PigProfileOrder): List<Farm_view_pig_order_by> = when (value) {
            PigProfileOrder.ID -> listOf(Farm_view_pig_order_by(id = Optional.Present(Order_by.asc)))
            PigProfileOrder.LOCATION_IDENTITY -> listOf(Farm_view_pig_order_by(location_code = Optional.Present(Order_by.asc)),
                Farm_view_pig_order_by(ear_tag = Optional.Present(Order_by.asc)), Farm_view_pig_order_by(ear_notch = Optional.Present(Order_by.asc)),
                Farm_view_pig_order_by(id = Optional.Present(Order_by.asc)))
        }
        private fun profile(p: NativePigProfilesQuery.Pig) = PigProfile(p.id, p.farm_id ?: throw ApiFailure.Protocol(), p.ear_tag, p.ear_notch, p.ear_tag_ex,
            p.role.rawValue, p.sex?.rawValue, p.lifecycle_status.rawValue, p.origin.rawValue, p.location_code, p.birthday, p.entry_date, p.remark,
            p.weight, p.sow_parity, p.breed_type?.rawValue, p.breeder_code, p.teat_count, p.first_estrus_date, p.last_estrus_date,
            p.last_farrowing_at, p.last_lactation_at, p.last_mating_at, p.last_production_status?.rawValue, p.current_batch_id,
            p.current_breeder_status?.production_status?.rawValue,
            p.current_batch?.takeIf { b -> b.id == p.current_batch_id && b.farm_id == p.farm_id }
                ?.let { b -> PigProfileBatch(b.id, b.sn, b.status.rawValue, b.line?.name,
                    b.tasks.map { PigBatchTask(it.id, it.task_type.rawValue, it.status.rawValue) }, b.farm_id) },
            p.semen_control?.let { s -> PigSemenControl(s.boar_id, s.farm_id, s.collection_status.rawValue, s.cooldown_until, s.last_collected_at,
                s.manually_marked_to_collect, s.marked_to_collect_since, s.modified_at, s.off_production, s.off_production_since) },
            p.location?.let { l -> PigProfileLocation(l.zone_id, l.unit_id, l.zone_location, l.unit_location, l.section_location, l.row_location, l.pen_location, l.pen_number,
                l.zone?.let { PigLocationName(it.code, it.name_cn, it.name_en) }, l.unit?.let { PigLocationName(it.code, it.name_cn, it.name_en) },
                l.section?.let { PigLocationName(it.code, it.name_cn, it.name_en) }, l.row?.code, l.row?.row_number) },
            p.healthy?.let { h -> PigHealthProfile(h.id, h.farm_id, h.triage_level?.rawValue, h.triage_changed_at,
                h.triage_operator?.let { PigOperator(it.id, it.display_name, it.avatar_url) }, h.body_temperature_celsius,
                h.body_temperature_operator_id, h.body_temperature_measured_at, h.body_temperature_operator?.let { PigOperator(it.id, it.display_name, it.avatar_url) },
                h.modified_at, h.prescriptions_aggregate.aggregate?.count,
                h.prescriptions.map { r -> PigPrescriptionReference(r.prescription_id, r.prescription?.therapies?.map { t -> PigTherapyReference(
                    t.treatment?.let { PigTreatmentReference(it.id, it.name_cn, it.name_en, it.detail_cn, it.detail_en) },
                    t.medication?.let { NamedReference(it.id, it.name_cn, it.name_en) }, t.medication_catalog?.let { NamedReference(it.id, it.name_cn, it.name_en) }) }) },
                h.diseases.map { d -> PigDisease(d.disease_id, d.created_at, d.operator?.let { PigOperator(it.id, it.display_name, it.avatar_url) },
                    d.disease?.let { DiseaseInfo(it.id, it.name_cn, it.name_en, it.abbr_cn, it.abbr_en, it.category_cn, it.category_en, it.description_cn, it.description_en) }) },
                h.symptoms.map { s -> PigSymptom(s.target_id, s.symptom_id, s.created_at, s.operator?.let { PigOperator(it.id, it.display_name, it.avatar_url) },
                    s.symptom?.let { SymptomInfo(it.id, it.name_cn, it.name_en, it.part_cn, it.part_en, it.description_cn, it.description_en) }) },
                h.healthy_records.map { r -> PigHealthHistory(r.id, r.created_at, r.healthy_event, r.triage_level, r.remark, r.symptoms, r.diseases, r.operator_id,
                    r.operator?.let { PigOperator(it.id, it.display_name, it.avatar_url) }) }) }, p.death_image_urls,
            p.current_breeder_status?.status_updated_at)
    }
}
