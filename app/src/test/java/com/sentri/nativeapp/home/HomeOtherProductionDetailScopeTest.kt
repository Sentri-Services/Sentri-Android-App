package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.production.*
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeOtherProductionDetailScopeTest {
    private val places = HomePlaces.from(Locations(listOf(
        FarmUnit("u7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"),
        FarmUnit("u8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production")), emptyList()))
    private val first = TaskPigLocation("Z-A-7-P1", "u7", "Z-A-7", "Z-A-7-P1")
    private val second = TaskPigLocation("Z-A-8-P1", "u8", "Z-A-8", "Z-A-8-P1")

    private fun catalog(kind: ProductionTaskKind): HomeScopedTask {
        val metadata = TaskMetadata("task", "farm", kind, ProductionTaskStatus.RUNNING,
            null, null, null, null, null, null,
            TaskBatch("batch", "34", null, null, emptyList()), null,
            listOf(TaskPigReference("p7", false, "E-7", null, first.locationCode, null),
                TaskPigReference("p8", false, "E-8", null, second.locationCode, null)),
            emptyList(), emptyList(), emptyList())
        return HomeTaskScopeProjector.project(listOf(metadata), places, 1).visible.single()
    }

    private fun detail(kind: ProductionTaskKind): ProductionTaskSnapshot = when (kind) {
        ProductionTaskKind.FARROWING -> FarrowingTask("task", "farm", "batch", "RUNNING",
            listOf(FarrowingPig("p7", "E-7", null, false, first, null),
                FarrowingPig("p8", "E-8", null, false, second, null)))
        ProductionTaskKind.PIGLET_PROCESSING -> PigletTask("task", "farm", "batch", "RUNNING",
            listOf(PigletPig("p7", "E-7", null, false, first, null),
                PigletPig("p8", "E-8", null, false, second, null)))
        ProductionTaskKind.POSTPARTUM -> PostpartumTask("task", "farm", "batch", "RUNNING",
            listOf(PostpartumPig("p7", "E-7", null, false, first, null),
                PostpartumPig("p8", "E-8", null, false, second, null)))
        ProductionTaskKind.LACTATION -> LactationTask("task", "farm", "batch", "RUNNING",
            listOf(LactationPig("p7", "E-7", null, false, first, null),
                LactationPig("p8", "E-8", null, false, second, null)))
        ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING -> {
            val stage = if (kind == ProductionTaskKind.NURSERY) GrowingStage.NURSERY else GrowingStage.FATTENING
            GrowingTask("task", "farm", "batch", "RUNNING", stage,
                listOf(GrowingPig("p7", "E-7", null, false, first, null, null, null),
                    GrowingPig("p8", "E-8", null, false, second, null, null, null)), emptyList())
        }
        else -> error("Not a remaining task")
    }

    @Test fun sixTaskKindsUseFreshTypedMembershipAndRejectChanges() {
        val kinds = listOf(ProductionTaskKind.FARROWING, ProductionTaskKind.PIGLET_PROCESSING,
            ProductionTaskKind.POSTPARTUM, ProductionTaskKind.LACTATION,
            ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING)
        kinds.forEach { kind ->
            val scoped = catalog(kind)
            val current = detail(kind)
            assertNotNull(kind.name, consistentOtherProductionDetail(scoped, current, places, 1))
            val changedBatch = when (current) {
                is FarrowingTask -> current.copy(batchId = "other")
                is PigletTask -> current.copy(batchId = "other")
                is PostpartumTask -> current.copy(batchId = "other")
                is LactationTask -> current.copy(batchId = "other")
                is GrowingTask -> current.copy(batchId = "other")
                else -> error("Unexpected type")
            }
            assertNull(kind.name, consistentOtherProductionDetail(scoped, changedBatch, places, 1))
            val moved = when (current) {
                is FarrowingTask -> current.copy(pigs = current.pigs.dropLast(1))
                is PigletTask -> current.copy(pigs = current.pigs.dropLast(1))
                is PostpartumTask -> current.copy(pigs = current.pigs.dropLast(1))
                is LactationTask -> current.copy(pigs = current.pigs.dropLast(1))
                is GrowingTask -> current.copy(pigs = current.pigs.dropLast(1))
                else -> error("Unexpected type")
            }
            assertNull(kind.name, consistentOtherProductionDetail(scoped, moved, places, 1))
        }
    }

    @Test fun typedUnitMustAgreeWithLocationScope() {
        val task = detail(ProductionTaskKind.POSTPARTUM) as PostpartumTask
        val mismatched = task.copy(pigs = task.pigs.map { pig ->
            if (pig.id == "p7") pig.copy(location = first.copy(unitId = "u8")) else pig
        })
        assertNull(consistentOtherProductionDetail(catalog(ProductionTaskKind.POSTPARTUM),
            mismatched, places, 1))
    }
}
