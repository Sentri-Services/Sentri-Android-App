package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.network.JsonValueAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Strict format-1 snapshots of confirmed vaccination rows, separate from pending command payloads. */
object VaccinationReadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun <T> array(rows: List<T>, encode: (T) -> JSONObject) = JSONArray(rows.map(encode))
    private fun JSONObject.fields(vararg expected: String) = require(keys().asSequence().toSet() == expected.toSet())
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid vaccination string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) {
        is Int -> value; is Long -> Math.toIntExact(value); else -> error("Invalid vaccination integer")
    }
    private fun JSONObject.number(key: String) = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid vaccination boolean")
    private fun JSONObject.child(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null
        else get(key) as? JSONObject ?: error("Invalid vaccination object")
    private fun JSONObject.rows(key: String) = (get(key) as? JSONArray ?: error("Invalid vaccination list")).let { values ->
        (0 until values.length()).map { values.get(it) as? JSONObject ?: error("Invalid vaccination row") }
    }
    private fun request(v: VaccinationPageRequest) = obj("mode" to v.mode.name, "start" to v.windowStart,
        "end" to v.windowEnd, "order" to v.order.name, "offset" to v.offset, "limit" to v.limit)
    private fun request(v: JSONObject): VaccinationPageRequest = with(v) {
        fields("mode", "start", "end", "order", "offset", "limit")
        VaccinationPageRequest(VaccinationReadMode.valueOf(string("mode")), text("start"), text("end"),
            VaccinationReadOrder.valueOf(string("order")), integer("offset"), integer("limit"))
    }
    fun key(value: VaccinationPageRequest) = request(value).toString()
    private fun name(v: VaccinationName) = obj("code" to v.code, "cn" to v.chinese, "en" to v.english)
    private fun name(v: JSONObject): VaccinationName = with(v) {
        fields("code", "cn", "en"); VaccinationName(string("code"), string("cn"), string("en"))
    }
    private fun location(v: VaccinationLocation) = obj("farm" to v.farmId, "pen" to v.penLocation,
        "row" to v.rowLocation, "section" to v.sectionLocation, "unit" to v.unitLocation,
        "zone" to v.zoneLocation, "number" to v.penNumber, "zoneName" to v.zone?.let(::name),
        "sectionName" to v.section?.let(::name), "unitName" to v.unit?.let(::name), "rowCode" to v.rowCode)
    private fun location(v: JSONObject): VaccinationLocation = with(v) {
        fields("farm", "pen", "row", "section", "unit", "zone", "number", "zoneName", "sectionName", "unitName", "rowCode")
        VaccinationLocation(string("farm"), text("pen"), text("row"), text("section"), text("unit"),
            text("zone"), integer("number"), child("zoneName")?.let(::name),
            child("sectionName")?.let(::name), child("unitName")?.let(::name), text("rowCode"))
    }
    private fun pig(v: VaccinationPig) = obj("id" to v.id, "farm" to v.farmId, "tag" to v.earTag,
        "notch" to v.earNotch, "birthday" to v.birthday, "breed" to v.breedType, "role" to v.role,
        "lifecycle" to v.lifecycleStatus, "locationCode" to v.locationCode, "batch" to v.currentBatchId,
        "batchSerial" to v.currentBatchSerial, "location" to v.location?.let(::location))
    private fun pig(v: JSONObject): VaccinationPig = with(v) {
        fields("id", "farm", "tag", "notch", "birthday", "breed", "role", "lifecycle", "locationCode", "batch", "batchSerial", "location")
        VaccinationPig(string("id"), text("farm"), text("tag"), text("notch"), text("birthday"),
            text("breed"), text("role"), string("lifecycle"), text("locationCode"), text("batch"),
            text("batchSerial"), child("location")?.let(::location))
    }
    private fun vaccine(v: VaccinationVaccine) = obj("id" to v.id, "farm" to v.farmId,
        "cn" to v.chinese, "en" to v.english, "status" to v.status)
    private fun vaccine(v: JSONObject): VaccinationVaccine = with(v) {
        fields("id", "farm", "cn", "en", "status")
        VaccinationVaccine(string("id"), string("farm"), string("cn"), string("en"), string("status"))
    }
    private fun brand(v: VaccinationBrand) = obj("id" to v.id, "farm" to v.farmId,
        "vaccine" to v.vaccineId, "cn" to v.chinese, "en" to v.english,
        "form" to v.dosageForm, "status" to v.status)
    private fun brand(v: JSONObject): VaccinationBrand = with(v) {
        fields("id", "farm", "vaccine", "cn", "en", "form", "status")
        VaccinationBrand(string("id"), string("farm"), string("vaccine"), string("cn"),
            string("en"), string("form"), string("status"))
    }
    private fun exemption(v: VaccinationExemption) = obj("farm" to v.farmId, "pig" to v.pigId,
        "hit" to v.hit, "rules" to v.ruleCodes?.encoded, "reasons" to v.reasons?.encoded,
        "evaluated" to v.evaluatedAt, "until" to v.effectiveUntil,
        "modified" to v.modifiedAt, "operator" to v.operatorId)
    private fun exemption(v: JSONObject): VaccinationExemption = with(v) {
        fields("farm", "pig", "hit", "rules", "reasons", "evaluated", "until", "modified", "operator")
        VaccinationExemption(string("farm"), string("pig"), boolean("hit"),
            text("rules")?.let(JsonValueAdapter::validate), text("reasons")?.let(JsonValueAdapter::validate),
            text("evaluated"), text("until"), text("modified"), text("operator"))
    }
    private fun dose(v: VaccinationTargetDose) = obj("farm" to v.farmId, "task" to v.taskId,
        "target" to v.targetId, "vaccine" to v.vaccineId, "brand" to v.brandId,
        "index" to v.doseIndex, "status" to v.status, "scheduled" to v.scheduledAt,
        "executed" to v.executedAt, "executedBy" to v.executedBy,
        "operator" to v.operatorId, "pig" to v.pig?.let(::pig),
        "exemptionQueried" to v.exemptionQueried, "exemption" to v.exemption?.let(::exemption))
    private fun dose(v: JSONObject): VaccinationTargetDose = with(v) {
        if (has("exemptionQueried"))
            fields("farm", "task", "target", "vaccine", "brand", "index", "status", "scheduled", "executed", "executedBy", "operator", "pig", "exemptionQueried", "exemption")
        else fields("farm", "task", "target", "vaccine", "brand", "index", "status", "scheduled", "executed", "executedBy", "operator", "pig")
        VaccinationTargetDose(string("farm"), string("task"), string("target"), string("vaccine"),
            string("brand"), integer("index"), string("status"), text("scheduled"), text("executed"),
            text("executedBy"), text("operator"), child("pig")?.let(::pig),
            if (has("exemptionQueried")) child("exemption")?.let(::exemption) else null,
            if (has("exemptionQueried")) boolean("exemptionQueried") else false)
    }
    private fun task(v: VaccinationTask) = obj("id" to v.id, "farm" to v.farmId,
        "vaccineId" to v.vaccineId, "brandId" to v.brandId, "status" to v.status,
        "scheduled" to v.scheduledAt, "modified" to v.modifiedAt, "created" to v.createdAt,
        "completed" to v.completedAt, "executed" to v.executedAt, "executedBy" to v.executedBy,
        "operator" to v.operatorId, "route" to v.route, "intervalValue" to v.intervalValue,
        "intervalUnit" to v.intervalUnit, "doseUnit" to v.doseUnit, "doseCount" to v.doseCount,
        "dose" to v.dose.toString(), "completedDoseCount" to v.completedTargetDoseCount,
        "exemptedCount" to v.exemptedTargetCount, "settledCount" to v.settledTargetCount,
        "targetCount" to v.targetCount, "taskType" to v.taskType, "plan" to v.planId,
        "related" to v.relatedTaskId, "effectReview" to v.effectReview?.encoded,
        "isDeleted" to v.isDeleted, "deleted" to v.deletedAt,
        "vaccine" to v.vaccine?.let(::vaccine), "brand" to v.brand?.let(::brand),
        "targets" to array(v.targets, ::dose))
    private fun task(v: JSONObject): VaccinationTask = with(v) {
        fields("id", "farm", "vaccineId", "brandId", "status", "scheduled", "modified", "created",
            "completed", "executed", "executedBy", "operator", "route", "intervalValue", "intervalUnit",
            "doseUnit", "doseCount", "dose", "completedDoseCount", "exemptedCount", "settledCount",
            "targetCount", "taskType", "plan", "related", "effectReview", "isDeleted", "deleted",
            "vaccine", "brand", "targets")
        VaccinationTask(string("id"), string("farm"), string("vaccineId"), string("brandId"),
            string("status"), text("scheduled"), text("modified"), text("created"), text("completed"),
            text("executed"), text("executedBy"), text("operator"), string("route"), number("intervalValue"),
            text("intervalUnit"), string("doseUnit"), integer("doseCount"), BigDecimal(string("dose")),
            integer("completedDoseCount"), integer("exemptedCount"), integer("settledCount"),
            integer("targetCount"), string("taskType"), text("plan"), text("related"),
            text("effectReview")?.let(JsonValueAdapter::validate), boolean("isDeleted"), text("deleted"),
            child("vaccine")?.let(::vaccine), child("brand")?.let(::brand), rows("targets").map(::dose))
    }
    private fun envelope(text: String, kind: String, vararg fields: String) = JSONObject(text).also {
        it.fields("format", "kind", *fields); require(it.integer("format") == 1 && it.string("kind") == kind)
    }
    fun encodePage(value: VaccinationPage) = obj("format" to 1, "kind" to "VACCINATION_PAGE",
        "request" to request(value.request), "rows" to array(value.rows, ::task), "more" to value.hasMore).toString()
    fun decodePage(text: String) = envelope(text, "VACCINATION_PAGE", "request", "rows", "more").let {
        VaccinationPage(request(requireNotNull(it.child("request"))), it.rows("rows").map(::task), it.boolean("more"))
    }
    fun encodeDetail(value: VaccinationTask?) = obj("format" to 1, "kind" to "VACCINATION_DETAIL",
        "value" to value?.let(::task)).toString()
    fun decodeDetail(text: String) = envelope(text, "VACCINATION_DETAIL", "value").child("value")?.let(::task)
    fun encodeCatalog(rows: List<VaccinationTask>) = obj("format" to 1, "kind" to "VACCINATION_CATALOG",
        "rows" to array(rows, ::task)).toString()
    fun decodeCatalog(text: String): List<VaccinationTask> =
        envelope(text, "VACCINATION_CATALOG", "rows").rows("rows").map(::task)
}
