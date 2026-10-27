package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Catalog schemas contain only domain values; never serialize Apollo classes. */
object TaskCatalogCodecs {
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray(values.map(encode))
    private fun JSONObject.fields(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid catalog string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.at(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.integer(key: String): Int = when (val v = get(key)) { is Int -> v; is Long -> Math.toIntExact(v); else -> error("Invalid catalog integer") }
    private fun JSONObject.count(key: String) = integer(key).also { require(it >= 0) }
    private fun JSONObject.number(key: String): Int? = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid catalog boolean")
    private fun JSONObject.nested(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid catalog object")
    private fun JSONObject.items(key: String): List<JSONObject> = (get(key) as? JSONArray ?: error("Invalid catalog list")).let { a -> (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid catalog item") } }
    private fun JSONObject.strings(key: String): List<String> = (get(key) as? JSONArray ?: error("Invalid catalog strings")).let { a -> (0 until a.length()).map { a.get(it) as? String ?: error("Invalid catalog string item") } }
    private fun JSONObject.decimal(key: String) = BigDecimal(string(key)).also { require(it.signum() >= 0) }
    private fun request(value: TaskCatalogRequest, format: Int = 2) = obj("kinds" to JSONArray(value.kinds.map { it.name }.sorted()), "statuses" to JSONArray(value.statuses.map { it.name }.sorted()),
        "window" to value.completedWindow?.let { obj("from" to it.from, "until" to it.until) }, "batch" to value.batchId, "line" to value.lineId, "operator" to value.operatorId,
        "offset" to value.offset, "limit" to value.limit, "order" to value.order.name).apply {
            if (format == 2) put("pigIds", value.pigIds?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        }
    private fun request(value: JSONObject, format: Int): TaskCatalogRequest {
        val fields = arrayOf("kinds", "statuses", "window", "batch", "line", "operator", "offset", "limit", "order")
        value.fields(*(if (format == 2) fields + "pigIds" else fields))
        val window = value.nested("window")?.let { it.fields("from", "until"); TaskTimeWindow(it.string("from"), it.string("until")) }
        val kinds = value.strings("kinds"); val statuses = value.strings("statuses")
        require(kinds.distinct().size == kinds.size && statuses.distinct().size == statuses.size)
        val pigIds = if (format == 1 || value.get("pigIds") == JSONObject.NULL) null else value.strings("pigIds").also { require(it.distinct().size == it.size) }.toSet()
        return TaskCatalogRequest(kinds.map(ProductionTaskKind::valueOf).toSet(), statuses.map(ProductionTaskStatus::valueOf).toSet(), window, value.text("batch"), value.text("line"), value.text("operator"), value.count("offset"), value.count("limit"), TaskCatalogOrder.valueOf(value.string("order")), pigIds)
    }
    // Preserve legacy unfiltered keys; filtered reads cannot borrow an old full-catalog snapshot.
    fun key(value: TaskCatalogRequest) = request(value, if (value.pigIds == null) 1 else 2).toString()
    private fun metadata(value: TaskMetadata) = obj("id" to value.id, "farm" to value.farmId, "kind" to value.kind.name, "status" to value.status.name,
        "started" to value.startedAt, "ended" to value.endedAt, "created" to value.createdAt, "modified" to value.modifiedAt, "operatorId" to value.operatorId,
        "operator" to value.operator?.let { obj("id" to it.id, "name" to it.name, "avatar" to it.avatar) },
        "batch" to obj("id" to value.batch.id, "serial" to value.batch.serial, "profile" to value.batch.profile?.encoded,
            "line" to value.batch.line?.let { obj("id" to it.id, "farm" to it.farmId, "name" to it.name, "capacity" to it.sowCapacity) },
            "tasks" to array(value.batch.tasks) { obj("id" to it.id, "kind" to it.kind.name, "status" to it.status.name) }),
        "plan" to value.plan?.let { obj("started" to it.startedAt, "days" to it.durationDays) },
        "pigs" to array(value.pigs) { obj("id" to it.id, "disabled" to it.disabled, "tag" to it.earTag, "notch" to it.earNotch, "location" to it.locationCode, "mark" to it.mark) },
        "materials" to array(value.matingMaterials) { obj("id" to it.id, "boar" to it.boarId, "semen" to it.semenId, "breeder" to it.breederType, "collected" to it.collectedAt,
            "count" to it.count, "tag" to it.boarEarTag, "notch" to it.boarEarNotch) },
        "statistics" to array(value.recheckStatistics) { obj("at" to it.at, "value" to it.value?.encoded) },
        "locations" to array(value.nurseryLocations) { obj("code" to it.code, "checked" to it.checkedAt, "created" to it.createdAt, "operator" to it.operatorId, "count" to it.totalCount, "weight" to it.totalWeight.toString()) })
    private fun metadata(value: JSONObject): TaskMetadata = with(value) {
        fields("id", "farm", "kind", "status", "started", "ended", "created", "modified", "operatorId", "operator", "batch", "plan", "pigs", "materials", "statistics", "locations")
        val batch = requireNotNull(nested("batch")).let { b ->
            b.fields("id", "serial", "profile", "line", "tasks")
            val line = b.nested("line")?.let { it.fields("id", "farm", "name", "capacity"); TaskLine(it.string("id"), it.string("farm"), it.string("name"), it.count("capacity")) }
            TaskBatch(b.string("id"), b.string("serial"), b.text("profile")?.let(JsonValueAdapter::validate), line, b.items("tasks").map {
                it.fields("id", "kind", "status"); RelatedProductionTask(it.string("id"), ProductionTaskKind.valueOf(it.string("kind")), ProductionTaskStatus.valueOf(it.string("status"))) })
        }
        val operator = nested("operator")?.let { it.fields("id", "name", "avatar"); TaskOperator(it.string("id"), it.text("name"), it.text("avatar")) }
        val plan = nested("plan")?.let { it.fields("started", "days"); TaskPlan(it.at("started"), it.number("days")) }
        val pigs = items("pigs").map { it.fields("id", "disabled", "tag", "notch", "location", "mark"); TaskPigReference(it.string("id"), it.boolean("disabled"), it.text("tag"), it.text("notch"), it.text("location"), it.text("mark")) }
        val materials = items("materials").map {
            it.fields("id", "boar", "semen", "breeder", "collected", "count", "tag", "notch")
            MatingMaterial(it.integer("id"), it.text("boar"), it.text("semen"), it.text("breeder"), it.at("collected"), it.number("count")?.also { n -> require(n >= 0) }, it.text("tag"), it.text("notch"))
        }
        val statistics = items("statistics").map { it.fields("at", "value"); TaskRecheckStatistic(requireNotNull(it.at("at")), it.text("value")?.let(JsonValueAdapter::validate)) }
        val locations = items("locations").map { it.fields("code", "checked", "created", "operator", "count", "weight"); NurseryLocation(it.string("code"), it.at("checked"), it.at("created"), it.text("operator"), it.count("count"), it.decimal("weight")) }
        require(listOf(pigs.map { it.id }, batch.tasks.map { it.id }, materials.map { it.id }, statistics.map { it.at }, locations.map { it.code }).all { it.distinct().size == it.size })
        TaskMetadata(string("id"), string("farm"), ProductionTaskKind.valueOf(string("kind")), ProductionTaskStatus.valueOf(string("status")), at("started"), at("ended"), at("created"), at("modified"), text("operatorId"), operator, batch, plan, pigs, materials, statistics, locations)
    }
    fun encodeMetadata(value: TaskMetadata?) = obj("format" to 1, "kind" to "TASK_METADATA", "value" to value?.let(::metadata)).toString()
    fun decodeMetadata(text: String): TaskMetadata? = JSONObject(text).let {
        it.fields("format", "kind", "value"); require(it.integer("format") == 1 && it.string("kind") == "TASK_METADATA"); it.nested("value")?.let(::metadata)
    }
    fun encodePage(value: TaskCatalogPage) = obj("format" to 2, "kind" to "TASK_CATALOG_PAGE", "request" to request(value.request), "total" to value.total, "tasks" to array(value.tasks, ::metadata)).toString()
    fun decodePage(text: String): TaskCatalogPage = JSONObject(text).let {
        it.fields("format", "kind", "request", "total", "tasks"); val format = it.integer("format")
        require(format in 1..2 && it.string("kind") == "TASK_CATALOG_PAGE")
        TaskCatalogPage(request(requireNotNull(it.nested("request")), format), it.items("tasks").map(::metadata), it.count("total"))
    }
}
