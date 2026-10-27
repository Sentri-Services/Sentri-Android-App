package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.network.JsonValueAdapter
import org.json.JSONArray
import org.json.JSONObject

/** Strict format-1 confirmed snapshots; command payloads remain in the separate queue database. */
object SamplingReadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun <T> array(rows: List<T>, encode: (T) -> JSONObject) = JSONArray(rows.map(encode))
    private fun JSONObject.fields(vararg expected: String) = require(keys().asSequence().toSet() == expected.toSet())
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid sampling string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) {
        is Int -> value; is Long -> Math.toIntExact(value); else -> error("Invalid sampling integer")
    }
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid sampling boolean")
    private fun JSONObject.child(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null
        else get(key) as? JSONObject ?: error("Invalid sampling object")
    private fun JSONObject.rows(key: String) = (get(key) as? JSONArray ?: error("Invalid sampling list")).let { values ->
        (0 until values.length()).map { values.get(it) as? JSONObject ?: error("Invalid sampling row") }
    }
    private fun request(v: SamplingPageRequest) = obj("mode" to v.mode.name, "start" to v.windowStart,
        "end" to v.windowEnd, "offset" to v.offset, "limit" to v.limit)
    private fun request(v: JSONObject): SamplingPageRequest = with(v) {
        fields("mode", "start", "end", "offset", "limit")
        SamplingPageRequest(SamplingReadMode.valueOf(string("mode")), text("start"), text("end"),
            integer("offset"), integer("limit"))
    }
    fun key(value: SamplingPageRequest) = request(value).toString()
    private fun name(v: SamplingName) = obj("code" to v.code, "cn" to v.chinese, "en" to v.english)
    private fun name(v: JSONObject): SamplingName = with(v) {
        fields("code", "cn", "en"); SamplingName(string("code"), string("cn"), string("en"))
    }
    private fun location(v: SamplingLocation) = obj("farm" to v.farmId, "zone" to v.zoneCode,
        "unit" to v.unitCode, "section" to v.sectionCode, "row" to v.rowCode, "pen" to v.penCode,
        "zoneId" to v.zoneId, "unitId" to v.unitId, "sectionId" to v.sectionId, "rowId" to v.rowId,
        "groupId" to v.groupId, "id" to v.id, "penNumber" to v.penNumber,
        "zoneName" to v.zone?.let(::name), "unitName" to v.unit?.let(::name),
        "sectionName" to v.section?.let(::name), "rowCodeValue" to v.rowCodeValue,
        "rowNumber" to v.rowNumber)
    private fun location(v: JSONObject): SamplingLocation = with(v) {
        fields("farm", "zone", "unit", "section", "row", "pen", "zoneId", "unitId", "sectionId",
            "rowId", "groupId", "id", "penNumber", "zoneName", "unitName", "sectionName", "rowCodeValue", "rowNumber")
        SamplingLocation(string("farm"), text("zone"), text("unit"), text("section"), text("row"),
            text("pen"), string("zoneId"), string("unitId"), string("sectionId"), string("rowId"),
            string("groupId"), integer("id"), integer("penNumber"), child("zoneName")?.let(::name),
            child("unitName")?.let(::name), child("sectionName")?.let(::name), text("rowCodeValue"), text("rowNumber"))
    }
    private fun pig(v: SamplingPig) = obj("id" to v.id, "farm" to v.farmId, "tag" to v.earTag,
        "notch" to v.earNotch, "breeder" to v.breederCode, "breed" to v.breedType,
        "lifecycle" to v.lifecycleStatus, "locationCode" to v.locationCode,
        "origin" to v.origin, "sex" to v.sex, "location" to v.location?.let(::location))
    private fun pig(v: JSONObject): SamplingPig = with(v) {
        fields("id", "farm", "tag", "notch", "breeder", "breed", "lifecycle", "locationCode", "origin", "sex", "location")
        SamplingPig(string("id"), text("farm"), text("tag"), text("notch"), text("breeder"),
            text("breed"), string("lifecycle"), text("locationCode"), text("origin"), text("sex"),
            child("location")?.let(::location))
    }
    private fun user(v: SamplingUser) = obj("id" to v.id, "farm" to v.farmId,
        "first" to v.firstName, "last" to v.lastName, "username" to v.username,
        "display" to v.displayName, "avatar" to v.avatarUrl)
    private fun user(v: JSONObject): SamplingUser = with(v) {
        fields("id", "farm", "first", "last", "username", "display", "avatar")
        SamplingUser(string("id"), string("farm"), text("first"), text("last"), text("username"),
            text("display"), text("avatar"))
    }
    private fun target(v: SamplingTarget) = obj("id" to v.id, "farm" to v.farmId,
        "test" to v.samplingTestId, "target" to v.targetId, "type" to v.type,
        "pig" to v.pig?.let(::pig), "user" to v.user?.let(::user))
    private fun target(v: JSONObject): SamplingTarget = with(v) {
        fields("id", "farm", "test", "target", "type", "pig", "user")
        SamplingTarget(integer("id"), string("farm"), string("test"), string("target"),
            string("type"), child("pig")?.let(::pig), child("user")?.let(::user))
    }
    private fun testTarget(v: SamplingTestTarget) = obj("id" to v.id, "farm" to v.farmId,
        "test" to v.samplingTestId, "remark" to v.remark, "sample" to v.sampleType,
        "type" to v.targetType, "ids" to v.targetIds?.encoded)
    private fun testTarget(v: JSONObject): SamplingTestTarget = with(v) {
        fields("id", "farm", "test", "remark", "sample", "type", "ids")
        SamplingTestTarget(string("id"), string("farm"), string("test"), text("remark"),
            string("sample"), string("type"), text("ids")?.let(JsonValueAdapter::validate))
    }
    private fun test(v: SamplingTest) = obj("id" to v.id, "farm" to v.farmId,
        "sampling" to v.samplingId, "code" to v.samplingCode, "project" to v.project,
        "container" to v.container, "created" to v.createdAt, "targets" to array(v.targets, ::testTarget))
    private fun test(v: JSONObject): SamplingTest = with(v) {
        fields("id", "farm", "sampling", "code", "project", "container", "created", "targets")
        SamplingTest(string("id"), string("farm"), string("sampling"), string("code"),
            string("project"), string("container"), text("created"), rows("targets").map(::testTarget))
    }
    private fun report(v: SamplingReport) = obj("index" to v.indexId, "farm" to v.farmId,
        "task" to v.samplingTaskId, "report" to v.reportId, "content" to v.contentType,
        "created" to v.createdAt, "name" to v.name, "operator" to v.operatorId)
    private fun report(v: JSONObject): SamplingReport = with(v) {
        fields("index", "farm", "task", "report", "content", "created", "name", "operator")
        SamplingReport(integer("index"), string("farm"), string("task"), string("report"),
            text("content"), text("created"), text("name"), text("operator"))
    }
    private fun task(v: SamplingTask) = obj("id" to v.id, "farm" to v.farmId,
        "sampling" to v.samplingId, "test" to v.samplingTestId, "created" to v.createdAt,
        "createdBy" to v.createdBy, "disabled" to v.disabled, "used" to v.used,
        "isSampled" to v.isSampled, "sampled" to v.sampledAt, "sampledBy" to v.sampledBy,
        "remark" to v.remark, "tested" to v.testedAt, "testedBy" to v.testedBy,
        "metric" to v.testMetric, "negative" to v.negativeValue,
        "positive" to v.positiveValue, "value" to v.testValue, "result" to v.testResult,
        "reportIds" to v.testReportIds?.encoded, "video" to v.videoId,
        "targets" to array(v.targets, ::target), "samplingTest" to v.samplingTest?.let(::test),
        "reports" to array(v.reports, ::report))
    private fun task(v: JSONObject): SamplingTask = with(v) {
        fields("id", "farm", "sampling", "test", "created", "createdBy", "disabled", "used",
            "isSampled", "sampled", "sampledBy", "remark", "tested", "testedBy", "metric", "negative",
            "positive", "value", "result", "reportIds", "video", "targets", "samplingTest", "reports")
        SamplingTask(string("id"), string("farm"), string("sampling"), string("test"), text("created"),
            text("createdBy"), boolean("disabled"), boolean("used"), boolean("isSampled"),
            text("sampled"), text("sampledBy"), text("remark"), text("tested"), text("testedBy"),
            text("metric"), text("negative"), text("positive"), text("value"), text("result"),
            text("reportIds")?.let(JsonValueAdapter::validate), text("video"),
            rows("targets").map(::target), child("samplingTest")?.let(::test), rows("reports").map(::report))
    }
    private fun envelope(text: String, kind: String, vararg fields: String) = JSONObject(text).also {
        it.fields("format", "kind", *fields); require(it.integer("format") == 1 && it.string("kind") == kind)
    }
    fun encodePage(value: SamplingPage) = obj("format" to 1, "kind" to "SAMPLING_PAGE",
        "request" to request(value.request), "rows" to array(value.rows, ::task), "more" to value.hasMore).toString()
    fun decodePage(text: String) = envelope(text, "SAMPLING_PAGE", "request", "rows", "more").let {
        SamplingPage(request(requireNotNull(it.child("request"))), it.rows("rows").map(::task), it.boolean("more"))
    }
    fun encodeDetail(value: SamplingTask?) = obj("format" to 1, "kind" to "SAMPLING_DETAIL",
        "value" to value?.let(::task)).toString()
    fun decodeDetail(text: String) = envelope(text, "SAMPLING_DETAIL", "value").child("value")?.let(::task)
}
