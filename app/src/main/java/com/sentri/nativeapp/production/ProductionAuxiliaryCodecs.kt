package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.TimestampAdapter
import org.json.JSONArray
import org.json.JSONObject

/** Auxiliary reads keep their own format-1 schemas, separate from typed task snapshots. */
object ProductionAuxiliaryCodecs {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray(values.map(encode))
    private fun JSONObject.fields(vararg values: String) { require(keys().asSequence().toSet() == values.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid auxiliary string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.at(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.int(key: String): Int = when (val v = get(key)) { is Int -> v; is Long -> Math.toIntExact(v); else -> error("Invalid auxiliary integer") }
    private fun JSONObject.number(key: String) = if (get(key) == JSONObject.NULL) null else int(key)
    private fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("Invalid auxiliary boolean")
    private fun JSONObject.flag(key: String) = if (get(key) == JSONObject.NULL) null else bool(key)
    private fun JSONObject.nested(key: String) = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid auxiliary object")
    private fun JSONObject.list(key: String): List<JSONObject> = (get(key) as? JSONArray ?: error("Invalid auxiliary list")).let { a ->
        (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid auxiliary row") } }
    private fun JSONObject.strings(key: String): List<String> = (get(key) as? JSONArray ?: error("Invalid auxiliary strings")).let { a ->
        (0 until a.length()).map { a.get(it) as? String ?: error("Invalid auxiliary code") } }
    private fun batchRequest(v: BatchRequest) = obj("statuses" to JSONArray(v.statuses.map { it.name }.sorted()), "line" to v.lineId,
        "excludeDefault" to v.excludeDefault, "offset" to v.offset, "limit" to v.limit)
    private fun batchRequest(v: JSONObject): BatchRequest = with(v) {
        fields("statuses", "line", "excludeDefault", "offset", "limit"); val statuses = strings("statuses"); require(statuses.distinct().size == statuses.size)
        BatchRequest(statuses.map(ProductionBatchStatus::valueOf).toSet(), text("line"), bool("excludeDefault"), int("offset"), int("limit"))
    }
    private fun recheckRequest(v: RecheckRequest) = obj("task" to v.taskId, "batch" to v.batchId, "unit" to v.unitId,
        "inReEstrus" to v.inReEstrus, "offset" to v.offset, "limit" to v.limit)
    private fun recheckRequest(v: JSONObject): RecheckRequest = with(v) {
        fields("task", "batch", "unit", "inReEstrus", "offset", "limit")
        RecheckRequest(text("task"), text("batch"), text("unit"), flag("inReEstrus"), int("offset"), int("limit"))
    }
    fun batchKey(value: BatchRequest) = batchRequest(value).toString()
    fun recheckKey(value: RecheckRequest) = recheckRequest(value).toString()
    private fun batch(v: ProductionBatch) = obj("id" to v.id, "farm" to v.farmId, "serial" to v.serial, "status" to v.status.name, "lineId" to v.lineId,
        "line" to v.line?.let { obj("id" to it.id, "farm" to it.farmId, "name" to it.name, "status" to it.status.name) },
        "tasks" to array(v.tasks) { obj("id" to it.id, "kind" to it.kind.name, "status" to it.status.name,
            "plan" to it.plan?.let { p -> obj("at" to p.startedAt, "days" to p.durationDays) }) })
    private fun batch(v: JSONObject): ProductionBatch = with(v) {
        fields("id", "farm", "serial", "status", "lineId", "line", "tasks")
        val line = nested("line")?.let { it.fields("id", "farm", "name", "status"); BatchLine(it.string("id"), it.string("farm"), it.string("name"), ProductionLineStatus.valueOf(it.string("status"))) }
        val tasks = list("tasks").map {
            it.fields("id", "kind", "status", "plan")
            val plan = it.nested("plan")?.let { p -> p.fields("at", "days"); TaskPlan(p.at("at"), p.number("days")) }
            BatchTaskReference(it.string("id"), ProductionTaskKind.valueOf(it.string("kind")), ProductionTaskStatus.valueOf(it.string("status")), plan)
        }
        ProductionBatch(string("id"), string("farm"), string("serial"), ProductionBatchStatus.valueOf(string("status")), string("lineId"), line, tasks)
    }
    private fun named(v: NamedProductionLocation) = obj("code" to v.code, "cn" to v.chinese, "en" to v.english)
    private fun named(v: JSONObject) = with(v) { fields("code", "cn", "en"); NamedProductionLocation(string("code"), string("cn"), string("en")) }
    private fun recheck(v: RecheckPig) = obj("task" to v.taskId, "batch" to v.batchId, "farm" to v.farmId, "pig" to v.pigId,
        "tag" to v.earTag, "notch" to v.earNotch, "location" to v.location?.let {
            obj("code" to it.basic.locationCode, "unitId" to it.basic.unitId, "unitLocation" to it.basic.unitLocation, "penLocation" to it.basic.penLocation,
                "zone" to it.zone?.let(::named), "unit" to it.unit?.let(::named), "section" to it.section?.let(::named),
                "rowCode" to it.rowCode, "rowNumber" to it.rowNumber, "penNumber" to it.penNumber)
        }, "result" to v.result.let {
            obj("status" to it.status?.name, "checked" to it.checkedAt, "inReEstrus" to it.isInReEstrus, "reEstrus" to it.reEstrusAt,
                "mating" to it.matingAt, "matingBy" to it.matingBy, "submitted" to it.submittedAt, "operator" to it.operatorId)
        })
    private fun recheck(v: JSONObject): RecheckPig = with(v) {
        fields("task", "batch", "farm", "pig", "tag", "notch", "location", "result")
        val location = nested("location")?.let {
            it.fields("code", "unitId", "unitLocation", "penLocation", "zone", "unit", "section", "rowCode", "rowNumber", "penNumber")
            RecheckLocation(TaskPigLocation(it.text("code"), it.text("unitId"), it.text("unitLocation"), it.text("penLocation")),
                it.nested("zone")?.let(::named), it.nested("unit")?.let(::named), it.nested("section")?.let(::named),
                it.text("rowCode"), it.text("rowNumber"), it.int("penNumber"))
        }
        val result = requireNotNull(nested("result")).let {
            it.fields("status", "checked", "inReEstrus", "reEstrus", "mating", "matingBy", "submitted", "operator")
            ReEstrusResult(it.text("status")?.let(ReEstrusStatus::valueOf), it.at("checked"), it.bool("inReEstrus"),
                it.at("reEstrus"), it.at("mating"), it.text("matingBy"), it.at("submitted"), it.text("operator"))
        }
        RecheckPig(string("task"), string("batch"), string("farm"), string("pig"), text("tag"), text("notch"), location, result)
    }
    private fun envelope(text: String, kind: String, vararg fields: String) = JSONObject(text).also {
        it.fields("format", "kind", *fields); require(it.int("format") == 1 && it.string("kind") == kind)
    }
    fun encodeBatch(value: ProductionBatch?) = obj("format" to 1, "kind" to "PRODUCTION_BATCH", "value" to value?.let(::batch)).toString()
    fun decodeBatch(text: String) = envelope(text, "PRODUCTION_BATCH", "value").nested("value")?.let(::batch)
    fun encodeBatches(value: BatchPage) = obj("format" to 1, "kind" to "PRODUCTION_BATCH_PAGE", "request" to batchRequest(value.request),
        "batches" to array(value.batches, ::batch), "more" to value.hasMore).toString()
    fun decodeBatches(text: String) = envelope(text, "PRODUCTION_BATCH_PAGE", "request", "batches", "more").let {
        BatchPage(batchRequest(requireNotNull(it.nested("request"))), it.list("batches").map(::batch), it.bool("more"))
    }
    fun encodeRechecks(value: RecheckPage) = obj("format" to 1, "kind" to "PRODUCTION_RECHECK_PAGE", "request" to recheckRequest(value.request),
        "pigs" to array(value.pigs, ::recheck), "more" to value.hasMore).toString()
    fun decodeRechecks(text: String) = envelope(text, "PRODUCTION_RECHECK_PAGE", "request", "pigs", "more").let {
        RecheckPage(recheckRequest(requireNotNull(it.nested("request"))), it.list("pigs").map(::recheck), it.bool("more"))
    }
}
