package com.sentri.nativeapp.home

import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.production.ProductionTaskStatus
import java.math.BigDecimal

/** One unit list across task sources. Only known start times can affect order. */
internal sealed interface HomeUnitWorkItem {
    val key: String
    val scheduled: Boolean
    val startAt: String?
    val category: HomeWorkCategory

    data class Production(val scoped: HomeScopedTask) : HomeUnitWorkItem {
        override val key = "production:${scoped.task.id}"
        override val category = HomeWorkCategory.PRODUCTION
        override val scheduled = scoped.task.status == ProductionTaskStatus.SCHEDULED
        override val startAt = scoped.task.plan?.startedAt ?: scoped.task.startedAt
    }

    data class Vaccination(val scoped: HomeScopedVaccination) : HomeUnitWorkItem {
        override val key = "vaccination:${scoped.task.id}"
        override val category = HomeWorkCategory.VACCINATION
        override val scheduled = scoped.task.status == "PENDING"
        override val startAt = scoped.task.scheduledAt
    }

    data class Transfer(val scoped: HomeScopedTransfer) : HomeUnitWorkItem {
        override val key = "transfer:${scoped.task.id}"
        override val category = HomeWorkCategory.TRANSFER
        override val scheduled = scoped.task.status == "PLANNED"
        override val startAt = scoped.task.plannedAt
    }
}

internal enum class HomeWorkCategory { PRODUCTION, VACCINATION, TRANSFER }
internal data class HomeWorkGroup(val category: HomeWorkCategory, val items: List<HomeUnitWorkItem>)

/** Categories follow their first ranked instance; no task instance is hidden or merged. */
internal object HomeWorkGroups {
    const val GROUP_FROM = 5
    fun group(ranked: List<HomeUnitWorkItem>): List<HomeWorkGroup> = ranked.groupBy(HomeUnitWorkItem::category)
        .map { (category, items) -> HomeWorkGroup(category, items) }
}

internal object HomeUnitWorkOrder {
    fun order(production: List<HomeScopedTask>, vaccination: List<HomeScopedVaccination>,
        transfer: List<HomeScopedTransfer>): List<HomeUnitWorkItem> =
        (production.map(HomeUnitWorkItem::Production) +
            vaccination.map(HomeUnitWorkItem::Vaccination) +
            transfer.map(HomeUnitWorkItem::Transfer))
            .sortedWith(compareBy<HomeUnitWorkItem>(
                { if (it.scheduled) 0 else 1 },
                { it.startAt?.let(TimestampInstant::seconds) ?: BigDecimal.valueOf(Long.MAX_VALUE) },
                HomeUnitWorkItem::key))
}
