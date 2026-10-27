package com.sentri.nativeapp.pigs

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor

class GraphqlPigRemote(private val executor: GraphqlExecutor) : PigRemote {
    override suspend fun locations(scope: DataScope): Locations {
        val data = executor.query(NativeLocationsQuery(scope.farmId))
        if (!data.units.all { it.farm_id == scope.farmId } || !data.pens.all { it.farm_id == scope.farmId }) throw ApiFailure.Protocol()
        return Locations(data.units.map {
            FarmUnit(it.unit_id, it.farm_id, it.name_cn, it.name_en,
                listOfNotNull(it.section?.zone?.code, it.section?.code, it.code).joinToString("-"), it.area_type,
                it.section?.section_id, it.section?.code, it.section?.name_cn, it.section?.name_en,
                it.section?.zone?.code, it.section?.zone?.name_cn, it.section?.zone?.name_en)
        }, data.pens.map { FarmPen(it.id, it.farm_id, it.unit_id, it.pen_location, it.pen_number) })
    }

    override suspend fun page(scope: DataScope, filter: PigFilter, offset: Int): PigPage {
        require(offset >= 0)
        val response = executor.query(NativePigListQuery(where(scope, filter), offset, filter.pageSize))
        return PigPage(response.pigs.map { map(it, scope) }, response.count.aggregate?.count ?: throw ApiFailure.Protocol(), offset, filter.pageSize)
    }

    override suspend fun detail(scope: DataScope, id: String): PigDetail? {
        require(id.isNotBlank())
        val row = executor.query(NativePigDetailQuery(id, scope.farmId)).pigs.singleOrNull() ?: return null
        val pig = map(row, scope)
        if (pig.id != id) throw ApiFailure.Protocol()
        return PigDetail(pig, row.death_image_urls, row.healthy?.healthy_records.orEmpty().map {
            HealthRecord(it.id, it.created_at, it.healthy_event, it.remark, it.symptoms, it.diseases)
        })
    }

    companion object {
        /** SQL LIKE escapes preserve the old JS includes search, including literal '%' and '_'. */
        fun literalContains(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        fun where(scope: DataScope, filter: PigFilter): Farm_view_pig_bool_exp = Farm_view_pig_bool_exp(
            farm_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(scope.farmId))),
            location_code = if (filter.unitCode.isEmpty()) Optional.Absent else Optional.Present(String_comparison_exp(_iregex = Optional.Present(filter.unitCode))),
            location = filter.penLocation?.let { Optional.Present(Farm_view_pen_index_bool_exp(pen_location = Optional.Present(String_comparison_exp(_eq = Optional.Present(it))))) } ?: Optional.Absent,
            _or = if (filter.search.isEmpty()) Optional.Absent else Optional.Present(listOf(
                Farm_view_pig_bool_exp(ear_tag = Optional.Present(String_comparison_exp(_like = Optional.Present(literalContains(filter.search))))),
                Farm_view_pig_bool_exp(ear_notch = Optional.Present(String_comparison_exp(_like = Optional.Present(literalContains(filter.search)))))
            )),
        )
        private fun map(row: NativePigListQuery.Pig, scope: DataScope): Pig {
            if (row.farm_id != scope.farmId) throw ApiFailure.Protocol()
            return Pig(row.id, scope.farmId, row.ear_tag, row.ear_notch, row.location_code,
                row.role.rawValue, row.sex?.rawValue, row.lifecycle_status.rawValue, row.last_production_status?.rawValue,
                row.sow_parity, row.weight, row.birthday, row.entry_date, row.remark, row.current_batch?.id, row.current_batch?.sn,
                row.location?.unit_id, row.location?.pen_location, row.healthy?.triage_level?.rawValue, row.healthy?.body_temperature_celsius,
                row.healthy?.symptoms.orEmpty().mapNotNull { it.symptom?.let { name -> NamedReference(name.id, name.name_cn, name.name_en) } },
                row.healthy?.diseases.orEmpty().mapNotNull { it.disease?.let { name -> NamedReference(name.id, name.name_cn, name.name_en) } })
        }
        private fun map(row: NativePigDetailQuery.Pig, scope: DataScope): Pig {
            if (row.farm_id != scope.farmId) throw ApiFailure.Protocol()
            return Pig(row.id, scope.farmId, row.ear_tag, row.ear_notch, row.location_code,
                row.role.rawValue, row.sex?.rawValue, row.lifecycle_status.rawValue, row.last_production_status?.rawValue,
                row.sow_parity, row.weight, row.birthday, row.entry_date, row.remark, row.current_batch?.id, row.current_batch?.sn,
                row.location?.unit_id, row.location?.pen_location, row.healthy?.triage_level?.rawValue, row.healthy?.body_temperature_celsius,
                row.healthy?.symptoms.orEmpty().mapNotNull { it.symptom?.let { name -> NamedReference(name.id, name.name_cn, name.name_en) } },
                row.healthy?.diseases.orEmpty().mapNotNull { it.disease?.let { name -> NamedReference(name.id, name.name_cn, name.name_en) } })
        }
    }
}
