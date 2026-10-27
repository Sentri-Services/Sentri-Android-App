package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.network.JsonValueAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Strict format-1 read snapshots; no command payload or authentication material enters this codec. */
object TherapyReadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun <T> array(rows: List<T>, encode: (T) -> JSONObject) = JSONArray(rows.map(encode))
    private fun JSONObject.fields(vararg names: String) = require(keys().asSequence().toSet() == names.toSet())
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid therapy string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Int = when (val v = get(key)) { is Int -> v; is Long -> Math.toIntExact(v); else -> error("Invalid therapy integer") }
    private fun JSONObject.number(key: String) = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("Invalid therapy boolean")
    private fun JSONObject.child(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid therapy object")
    private fun JSONObject.rows(key: String) = (get(key) as? JSONArray ?: error("Invalid therapy list")).let { a ->
        (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid therapy row") }
    }
    private fun JSONObject.strings(key: String) = (get(key) as? JSONArray ?: error("Invalid therapy strings")).let { a ->
        (0 until a.length()).map { a.get(it) as? String ?: error("Invalid therapy string item") }
    }
    private fun JSONObject.decimal(key: String) = BigDecimal(string(key))
    private fun JSONObject.json(key: String) = text(key)?.let(JsonValueAdapter::validate)
    private fun request(v: TherapyPageRequest) = obj("prescription" to v.prescriptionId, "statuses" to JSONArray(v.statuses.sorted()),
        "mode" to v.mode.name, "start" to v.windowStart, "end" to v.windowEnd, "order" to v.order.name,
        "offset" to v.offset, "limit" to v.limit)
    private fun request(v: JSONObject): TherapyPageRequest = with(v) {
        fields("prescription", "statuses", "mode", "start", "end", "order", "offset", "limit")
        val statuses = strings("statuses"); require(statuses.distinct().size == statuses.size)
        TherapyPageRequest(text("prescription"), statuses.toSet(), TherapyReadMode.valueOf(string("mode")),
            text("start"), text("end"), TherapyReadOrder.valueOf(string("order")), integer("offset"), integer("limit"))
    }
    fun key(request: TherapyPageRequest) = request(request).toString()

    private fun name(v: TherapyName) = obj("cn" to v.chinese, "en" to v.english)
    private fun name(v: JSONObject): TherapyName = with(v) { fields("cn", "en"); TherapyName(string("cn"), string("en")) }
    private fun medication(v: TherapyMedication) = obj("id" to v.id, "farm" to v.farmId, "cn" to v.chinese, "en" to v.english)
    private fun medication(v: JSONObject): TherapyMedication = with(v) { fields("id", "farm", "cn", "en"); TherapyMedication(string("id"), string("farm"), string("cn"), string("en")) }
    private fun catalog(v: TherapyMedicationCatalog) = obj("id" to v.id, "farm" to v.farmId, "medication" to v.medicationId,
        "cn" to v.chinese, "en" to v.english, "formCn" to v.formChinese, "formEn" to v.formEnglish)
    private fun catalog(v: JSONObject): TherapyMedicationCatalog = with(v) {
        fields("id", "farm", "medication", "cn", "en", "formCn", "formEn")
        TherapyMedicationCatalog(string("id"), string("farm"), string("medication"), string("cn"), string("en"), text("formCn"), text("formEn"))
    }
    private fun treatment(v: TherapyTreatment) = obj("id" to v.id, "cn" to v.chinese, "en" to v.english,
        "detailCn" to v.detailChinese, "detailEn" to v.detailEnglish)
    private fun treatment(v: JSONObject): TherapyTreatment = with(v) {
        fields("id", "cn", "en", "detailCn", "detailEn")
        TherapyTreatment(string("id"), string("cn"), string("en"), string("detailCn"), string("detailEn"))
    }
    private fun prescription(v: TherapyPrescription) = obj("id" to v.id, "farm" to v.farmId, "created" to v.createdAt,
        "operatorId" to v.operatorId, "operator" to v.operator?.let { obj("name" to it.displayName, "avatar" to it.avatarUrl) },
        "symptoms" to v.symptoms?.encoded, "diseases" to v.diseases?.encoded, "remark" to v.remark,
        "status" to v.status, "count" to v.therapyCount)
    private fun prescription(v: JSONObject): TherapyPrescription = with(v) {
        fields("id", "farm", "created", "operatorId", "operator", "symptoms", "diseases", "remark", "status", "count")
        TherapyPrescription(string("id"), string("farm"), text("created"), text("operatorId"),
            child("operator")?.let { it.fields("name", "avatar"); TherapyOperator(it.text("name"), it.text("avatar")) },
            json("symptoms"), json("diseases"), text("remark"), string("status"), number("count"))
    }
    private fun location(v: TherapyPigLocation) = obj("farm" to v.farmId, "pen" to v.penLocation, "row" to v.rowLocation,
        "section" to v.sectionLocation, "unit" to v.unitLocation, "zone" to v.zoneLocation, "penNumber" to v.penNumber,
        "zoneName" to v.zone?.let(::name), "sectionName" to v.section?.let(::name), "unitName" to v.unit?.let(::name), "rowCode" to v.rowCode)
    private fun location(v: JSONObject): TherapyPigLocation = with(v) {
        fields("farm", "pen", "row", "section", "unit", "zone", "penNumber", "zoneName", "sectionName", "unitName", "rowCode")
        TherapyPigLocation(string("farm"), text("pen"), text("row"), text("section"), text("unit"), text("zone"), integer("penNumber"),
            child("zoneName")?.let(::name), child("sectionName")?.let(::name), child("unitName")?.let(::name), text("rowCode"))
    }
    private fun pig(v: TherapyPig) = obj("id" to v.id, "farm" to v.farmId, "breeder" to v.breederCode,
        "notch" to v.earNotch, "tag" to v.earTag, "lifecycle" to v.lifecycleStatus, "locationCode" to v.locationCode,
        "origin" to v.origin, "sex" to v.sex, "breed" to v.breedType, "birthday" to v.birthday,
        "teatCount" to v.teatCount, "role" to v.role, "batch" to v.currentBatchId, "location" to v.location?.let(::location))
    private fun pig(v: JSONObject): TherapyPig = with(v) {
        fields("id", "farm", "breeder", "notch", "tag", "lifecycle", "locationCode", "origin", "sex", "breed", "birthday", "teatCount", "role", "batch", "location")
        TherapyPig(string("id"), text("farm"), text("breeder"), text("notch"), text("tag"), string("lifecycle"),
            text("locationCode"), string("origin"), text("sex"), text("breed"), text("birthday"), number("teatCount"),
            text("role"), text("batch"), child("location")?.let(::location))
    }
    private fun task(v: TherapyTargetTask) = obj("farm" to v.farmId, "prescription" to v.prescriptionId,
        "therapy" to v.therapyId, "task" to v.taskId, "target" to v.targetId, "times" to v.times,
        "status" to v.status, "scheduled" to v.scheduledAt, "executed" to v.executedAt,
        "executedBy" to v.executedBy, "operator" to v.operatorId, "remark" to v.remark, "pig" to v.pig?.let(::pig))
    private fun task(v: JSONObject): TherapyTargetTask = with(v) {
        fields("farm", "prescription", "therapy", "task", "target", "times", "status", "scheduled", "executed", "executedBy", "operator", "remark", "pig")
        TherapyTargetTask(string("farm"), string("prescription"), string("therapy"), string("task"), string("target"), integer("times"),
            string("status"), text("scheduled"), text("executed"), text("executedBy"), text("operator"), text("remark"), child("pig")?.let(::pig))
    }
    private fun row(v: TherapyRow) = obj("id" to v.id, "farm" to v.farmId, "prescriptionId" to v.prescriptionId,
        "created" to v.createdAt, "dosage" to v.dosage.toString(), "interval" to v.interval,
        "medicationId" to v.medicationId, "medication" to v.medication?.let(::medication),
        "catalogId" to v.medicationCatalogId, "catalog" to v.medicationCatalog?.let(::catalog),
        "operator" to v.operatorId, "remark" to v.remark, "prescription" to v.prescription?.let(::prescription),
        "start" to v.startDate, "status" to v.status, "times" to v.times,
        "treatmentId" to v.treatmentId, "treatment" to v.treatment?.let(::treatment), "unit" to v.unit,
        "tasks" to array(v.tasks, ::task))
    private fun row(v: JSONObject): TherapyRow = with(v) {
        fields("id", "farm", "prescriptionId", "created", "dosage", "interval", "medicationId", "medication", "catalogId", "catalog",
            "operator", "remark", "prescription", "start", "status", "times", "treatmentId", "treatment", "unit", "tasks")
        TherapyRow(string("id"), string("farm"), string("prescriptionId"), text("created"), decimal("dosage"), number("interval"),
            string("medicationId"), child("medication")?.let(::medication), text("catalogId"), child("catalog")?.let(::catalog),
            text("operator"), text("remark"), child("prescription")?.let(::prescription), text("start"), string("status"),
            integer("times"), string("treatmentId"), child("treatment")?.let(::treatment), string("unit"), rows("tasks").map(::task))
    }
    private fun envelope(text: String, kind: String, vararg fields: String) = JSONObject(text).also {
        it.fields("format", "kind", *fields); require(it.integer("format") == 1 && it.string("kind") == kind)
    }
    fun encodePage(value: TherapyPage) = obj("format" to 1, "kind" to "THERAPY_PAGE", "request" to request(value.request),
        "rows" to array(value.rows, ::row), "more" to value.hasMore).toString()
    fun decodePage(text: String) = envelope(text, "THERAPY_PAGE", "request", "rows", "more").let {
        TherapyPage(request(requireNotNull(it.child("request"))), it.rows("rows").map(::row), it.bool("more"))
    }
    fun encodeDetail(value: TherapyRow?) = obj("format" to 1, "kind" to "THERAPY_DETAIL", "value" to value?.let(::row)).toString()
    fun decodeDetail(text: String) = envelope(text, "THERAPY_DETAIL", "value").child("value")?.let(::row)
    fun encodeMedications(value: List<TherapyMedication>) = obj("format" to 1, "kind" to "THERAPY_MEDICATIONS", "rows" to array(value, ::medication)).toString()
    fun decodeMedications(text: String) = envelope(text, "THERAPY_MEDICATIONS", "rows").rows("rows").map(::medication)
    fun encodeCatalogs(value: List<TherapyMedicationCatalog>) = obj("format" to 1, "kind" to "THERAPY_CATALOGS", "rows" to array(value, ::catalog)).toString()
    fun decodeCatalogs(text: String) = envelope(text, "THERAPY_CATALOGS", "rows").rows("rows").map(::catalog)
    fun encodeTreatments(value: List<TherapyTreatment>) = obj("format" to 1, "kind" to "THERAPY_TREATMENTS", "rows" to array(value, ::treatment)).toString()
    fun decodeTreatments(text: String) = envelope(text, "THERAPY_TREATMENTS", "rows").rows("rows").map(::treatment)
    fun encodeContext(value: TherapyPrescriptionContext?) = obj("format" to 1, "kind" to "THERAPY_CONTEXT", "value" to value?.let {
        obj("id" to it.id, "farm" to it.farmId, "status" to it.status, "targets" to JSONArray(it.targetIds.sorted()),
            "therapies" to array(it.therapyStatuses.entries.sortedBy { entry -> entry.key }) { entry ->
                obj("id" to entry.key, "status" to entry.value)
            })
    }).toString()
    fun decodeContext(text: String): TherapyPrescriptionContext? = envelope(text, "THERAPY_CONTEXT", "value").child("value")?.let {
        it.fields("id", "farm", "status", "targets", "therapies")
        val targets = it.strings("targets"); require(targets.distinct().size == targets.size)
        val therapies = it.rows("therapies").map { item ->
            item.fields("id", "status"); item.string("id") to item.string("status")
        }
        require(therapies.map { pair -> pair.first }.distinct().size == therapies.size)
        TherapyPrescriptionContext(it.string("id"), it.string("farm"), it.string("status"), targets.toSet(), therapies.toMap())
    }
}
