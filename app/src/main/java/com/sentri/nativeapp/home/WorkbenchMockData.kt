package com.sentri.nativeapp.home

/** Fixed, in-memory fixtures aligned with Sentri-UI Workbench 6df878f (2026-10-19).
 * None of these counts or dates is a backend result. */
internal enum class WorkCategory { Production, Health, Other }
internal enum class TileState { Due, PartialOverdue, Waiting, PastPlan, Ready, Unknown }
internal data class WorkItem(
    val id: String,
    val section: String,
    val category: WorkCategory,
    val type: String,
    val batch: String,
    val icon: String,
    val units: List<Int>,
    val state: TileState = TileState.Due,
    val due: Int = 0,
    val overdue: Int = 0,
    val next: Int = 0,
    val day: Int = 1,
    val days: Int = 3,
    val overDays: Int = 0,
    val unitDue: Map<Int, Int> = emptyMap(),
    val unitProgress: Map<Int, Pair<Int, Int>> = emptyMap(),
    val otherTitle: String = "",
    val otherWhere: String = "",
    val dueUnit: String = "pigs",
    val blocked: Boolean = false,
    val closedAt: String? = null,
    val endedEarly: Boolean = false,
    val localRecords: Int = 0,
)
internal data class MarkedPig(val unit: Int, val tag: String, val pen: String, val care: String, val condition: String, val waited: Boolean = false)
internal data class BoardRow(val type: String, val label: String, val fullName: String, val cells: List<String>)
internal data class BoardBand(val category: WorkCategory, val rows: List<BoardRow>)

internal object WorkbenchMockData {
    val tasks = listOf(
        WorkItem("heat34", "breeding", WorkCategory.Production, "Heat check", "Batch 34", "heat", listOf(1,2,3), due=20, day=2, days=3, unitDue=mapOf(1 to 8,2 to 6,3 to 6), unitProgress=mapOf(1 to (3 to 8),2 to (2 to 6),3 to (0 to 6))),
        WorkItem("heat35", "breeding", WorkCategory.Production, "Heat check", "Batch 35", "heat", listOf(2,4), due=10, day=1, days=3, unitDue=mapOf(2 to 4,4 to 6)),
        WorkItem("service34", "breeding", WorkCategory.Production, "Breeding", "Batch 34", "note", listOf(1,3), due=5, day=2, days=4, unitDue=mapOf(1 to 3,3 to 2)),
        WorkItem("return30", "gestation", WorkCategory.Production, "Return heat", "Batch 30", "return", listOf(7,8), due=10, day=3, days=7, unitDue=mapOf(7 to 4,8 to 6)),
        WorkItem("preg28", "gestation", WorkCategory.Production, "Pregnancy check", "Batch 28", "pregnancy", listOf(7,8), TileState.Ready, day=3, days=3, unitDue=mapOf(7 to 0,8 to 0), unitProgress=mapOf(7 to (10 to 10),8 to (8 to 8))),
        WorkItem("preg31", "gestation", WorkCategory.Production, "Pregnancy check", "Batch 31", "pregnancy", listOf(7,8,9), TileState.PartialOverdue, due=5, overdue=2, day=1, days=3, unitDue=mapOf(7 to 2,8 to 2,9 to 1), unitProgress=mapOf(7 to (2 to 6),8 to (4 to 8),9 to (2 to 6)),localRecords=2),
        WorkItem("preg32", "gestation", WorkCategory.Production, "Pregnancy check", "Batch 32", "pregnancy", listOf(7,9), TileState.Waiting, next=8, day=2, days=3, unitDue=mapOf(7 to 0,9 to 0)),
        WorkItem("farrow28", "farrowing", WorkCategory.Production, "Farrowing", "Batch 28", "farrow", listOf(6,7,8), due=2, day=2, days=7, unitDue=mapOf(6 to 1,7 to 1,8 to 0), unitProgress=mapOf(6 to (4 to 8),7 to (1 to 6),8 to (2 to 4))),
        WorkItem("piglet27", "farrowing", WorkCategory.Production, "Processing", "Batch 27", "note", listOf(6,7), TileState.Waiting, next=4, day=3, days=7, unitDue=mapOf(6 to 0,7 to 0), unitProgress=mapOf(6 to (1 to 4),7 to (0 to 4))),
        WorkItem("growth26", "nursery", WorkCategory.Production, "Growth check", "Batch 26", "barn", listOf(12,13), TileState.Waiting, next=80, day=1, days=3, unitDue=mapOf(12 to 0,13 to 0)),
        WorkItem("vax21", "gestation", WorkCategory.Health, "Vaccination", "Plan V-21", "health", listOf(7,8), TileState.PastPlan, due=6, overdue=6, day=3, days=3, overDays=1, unitDue=mapOf(7 to 4,8 to 2)),
        WorkItem("treat118", "gestation", WorkCategory.Health, "Treatment", "Order T-118", "treat", listOf(7,9), due=5, day=2, days=5, unitDue=mapOf(7 to 2,9 to 3)),
        WorkItem("ob77", "gestation", WorkCategory.Other, "Outbound", "OB-77", "send", listOf(7,8), due=24, otherTitle="Outbound · pickup 14:00",otherWhere="Units 7, 8",blocked=true),
        WorkItem("ob78", "gestation", WorkCategory.Other, "Outbound", "OB-78", "send", listOf(8), due=18, otherTitle="Outbound · pickup 16:30",otherWhere="Unit 8"),
        WorkItem("tr241", "gestation", WorkCategory.Other, "Pig transfer", "TR-241", "transfer", listOf(7,8), due=8, otherTitle="Move to Farrowing, Unit 6",otherWhere="From Units 7, 8"),
        WorkItem("tr242", "gestation", WorkCategory.Other, "Pig transfer", "TR-242", "transfer", listOf(8), due=8, otherTitle="Move to Unit 9",otherWhere="From Unit 8 · 12 of 20 moved"),
        WorkItem("s11", "gestation", WorkCategory.Other, "Sampling", "S-11", "note", listOf(7,9), due=10, otherTitle="PRRS blood samples",otherWhere="Units 7, 9"),
        WorkItem("s12", "gestation", WorkCategory.Other, "Sampling", "S-12", "note", listOf(8), due=4, dueUnit="pens", otherTitle="Oral fluid samples",otherWhere="Unit 8"),
        WorkItem("tr250", "farrowing", WorkCategory.Other, "Pig transfer", "TR-250", "transfer", listOf(6), due=96, otherTitle="Move to Nursery, Unit 12",otherWhere="Weaned pigs from Unit 6"),
        WorkItem("preg29done", "gestation", WorkCategory.Production, "Pregnancy check", "Batch 29", "pregnancy", listOf(7,8,9), closedAt="08:45"),
        WorkItem("service33done", "breeding", WorkCategory.Production, "Breeding", "Batch 33", "note", listOf(1,2,3,4), closedAt="08:50"),
        WorkItem("piglet26done", "farrowing", WorkCategory.Production, "Processing", "Batch 26", "note", listOf(6,7,8), closedAt="09:10"),
    )
    val manyTasks = (33..36).map { n -> WorkItem("preg$n", "gestation", WorkCategory.Production, "Pregnancy check", "Batch $n", "pregnancy", listOf(7,8), due=n-30, day=1, days=3, unitDue=mapOf(7 to 2,8 to 2)) }
    val marks = listOf(
        MarkedPig(7,"204318","B2","Hospital","Lameness",true), MarkedPig(7,"204522","C1","In place","Fever"),
        MarkedPig(7,"203977","C4","In place","Shoulder sore"), MarkedPig(8,"205140","A3","Hospital","Lameness"),
        MarkedPig(7,"204101","A2","","Coughing"), MarkedPig(7,"204277","B4","","Off feed"),
        MarkedPig(7,"203860","C2","","Thin"), MarkedPig(8,"205033","A1","","Coughing"),
        MarkedPig(8,"205219","B3","","Vulva discharge"), MarkedPig(9,"206012","A4","","Off feed"),
        MarkedPig(9,"206155","C3","","Thin"),
    )
    val stageOrder = listOf("Heat check","Breeding","Return heat","Pregnancy check","Farrowing","Processing","Growth check")
    fun production(section: String, many: Boolean = false): List<WorkItem> =
        (tasks + if (many && section == "gestation") manyTasks else emptyList())
            .filter { it.section == section && it.category == WorkCategory.Production && it.closedAt == null }
            .sortedWith(compareBy<WorkItem> { stageOrder.indexOf(it.type).let { rank -> if (rank < 0) 99 else rank } }
                .thenBy { it.batch.filter(Char::isDigit).toIntOrNull() ?: 0 }.thenBy { it.id })
    fun health(section: String) = tasks.filter { it.section == section && it.category == WorkCategory.Health && it.closedAt == null }
    fun other(section: String) = tasks.filter { it.section == section && it.category == WorkCategory.Other && it.closedAt == null }
    fun closed(section: String) = tasks.filter { it.section == section && it.closedAt != null }
    fun unitCount(section: String, unit: Int): Int = production(section).count { unit in it.units }
    fun unitPopulation(section: String, unit: Int): Pair<Int,Int> = when (section to unit) {
        "farrowing" to 6 -> 8 to 4; "farrowing" to 7 -> 7 to 4; "farrowing" to 8 -> 4 to 2
        "gestation" to 7 -> 86 to 8; "gestation" to 8 -> 74 to 7; "gestation" to 9 -> 86 to 8
        "breeding" to 1 -> 48 to 6; "breeding" to 2 -> 46 to 6; "breeding" to 3 -> 44 to 6; "breeding" to 4 -> 46 to 6
        "nursery" to 12 -> 180 to 6; "nursery" to 13 -> 180 to 6; else -> 0 to 0
    }
    fun board(section: String, week: Int): List<BoardBand> {
        if (section != "gestation" && week != 0) return emptyList()
        if (week == -1) return listOf(
            BoardBand(WorkCategory.Production,listOf(BoardRow("Return heat","B26","Batch 26",listOf("d3","d5","d4","d2","d1","","")),BoardRow("Pregnancy check","B27","Batch 27",listOf("","","d10","d9","d8","","")))),
            BoardBand(WorkCategory.Health,emptyList()),BoardBand(WorkCategory.Other,listOf(BoardRow("Outbound","9:00","Pickup 09:00 · Units 7, 9",listOf("","","d22","","","","")))))
        if (week == 1) return listOf(
            BoardBand(WorkCategory.Production,listOf(BoardRow("Return heat","B30","Batch 30",listOf("<e2","","","","","","")),BoardRow("Return heat","B39","Batch 39",listOf("<e5","e6","e5","e3","e2","e1","")),BoardRow("Pregnancy check","B34","Batch 34",listOf("","p7","p7","p6","","","")))),
            BoardBand(WorkCategory.Health,listOf(BoardRow("Treatment","Amox","Amoxicillin · Units 7, 9",listOf("<p5","","","","","","")))),
            BoardBand(WorkCategory.Other,listOf(BoardRow("Pig transfer","Mixed","To Units 6, 10, 11",listOf("","","p30","","","","")),BoardRow("Sampling","PRRS","Blood repeat · Unit 7",listOf("","p6","","","","","")))))
        return when (section) {
            "gestation" -> listOf(
                BoardBand(WorkCategory.Production,listOf(
                    BoardRow("Return heat","B30","Batch 30",listOf("","d2","d6","o10","e8","e6","e4>")),
                    BoardRow("Return heat","B39","Batch 39",listOf("","","","","","","e3>")),
                    BoardRow("Pregnancy check","B28","Batch 28",listOf("d0","d6","d6","n","","","")),
                    BoardRow("Pregnancy check","B29","Batch 29",listOf("","","d11","d6","","","")),
                    BoardRow("Pregnancy check","B31","Batch 31",listOf("","","","o5","p8","p7","")),
                    BoardRow("Pregnancy check","B32","Batch 32",listOf("","","","n","p8","","")),
                    BoardRow("Pregnancy check","B38","Batch 38",listOf("","","","","p22","u","u")))),
                BoardBand(WorkCategory.Health,listOf(BoardRow("Vaccination","PRRS","PRRS · Units 7, 8",listOf("","d4","w2","v6","","","")),BoardRow("Treatment","Amox","Amoxicillin · Units 7, 9",listOf("","","d5","o5","p5","p5","p5>")))),
                BoardBand(WorkCategory.Other,listOf(BoardRow("Outbound","14:00","Pickup 14:00 · Units 7, 8",listOf("","","","o24","","","")),BoardRow("Outbound","16:30","Pickup 16:30 · Unit 8",listOf("","","","o18","","","")),BoardRow("Pig transfer","B29","Batch 29 to Farrowing 6",listOf("","","","o8","","","")),BoardRow("Pig transfer","Mixed","To Unit 9 · from Unit 8",listOf("","","d12","o8","","","")),BoardRow("Sampling","PRRS","PRRS blood · Units 7, 9",listOf("","","","o10","","","")),BoardRow("Sampling","Oral","Oral fluids · Unit 8",listOf("","","","o4","","","")))))
            "breeding" -> listOf(BoardBand(WorkCategory.Production,listOf(BoardRow("Heat check","B34","Batch 34",listOf("","","d18","o20","e6","","")),BoardRow("Heat check","B35","Batch 35",listOf("","","","o10","e6","e2","")),BoardRow("Breeding","B34","Batch 34",listOf("","d9","d14","o5","e7","e3","e1>")))))
            "farrowing" -> listOf(BoardBand(WorkCategory.Production,listOf(BoardRow("Farrowing","B28","Batch 28",listOf("","","d4","o2","e5","e4","e3>")),BoardRow("Processing","B27","Batch 27",listOf("","d4","d4","n","p4","","")))),BoardBand(WorkCategory.Other,listOf(BoardRow("Pig transfer","B27","Batch 27 to Nursery 12",listOf("","","","o96","","","")))))
            else -> emptyList()
        }
    }
}
