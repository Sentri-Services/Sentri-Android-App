package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Independently versioned complete profiles; old basic snapshots retain their original meaning. */
object PigProfileCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray(values.map(encode))
    private fun JSONObject.fields(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
    private fun JSONObject.fieldsWithOptional(optional: String, vararg fields: String) {
        val actual = keys().asSequence().toSet()
        require(actual == fields.toSet() || actual == fields.toSet() + optional)
    }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid profile string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.at(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.decimal(key: String) = text(key)?.let(::BigDecimal)
    private fun JSONObject.json(key: String) = text(key)?.let(JsonValueAdapter::validate)
    private fun JSONObject.integer(key: String): Int = when (val v = get(key)) { is Int -> v; is Long -> Math.toIntExact(v); else -> error("Invalid profile integer") }
    private fun JSONObject.number(key: String) = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid profile boolean")
    private fun JSONObject.nested(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid profile object")
    private fun JSONObject.items(key: String) = (get(key) as? JSONArray ?: error("Invalid profile list")).let { a -> (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid profile row") } }
    private fun JSONObject.strings(key: String) = (get(key) as? JSONArray ?: error("Invalid profile strings")).let { a -> (0 until a.length()).map { a.get(it) as? String ?: error("Invalid profile string item") } }
    private fun JSONObject.stringSet(key: String) = strings(key).also { require(it.size == it.distinct().size) }.toSet()
    private fun operator(value: PigOperator) = obj("id" to value.id, "name" to value.name, "avatar" to value.avatar)
    private fun operator(value: JSONObject): PigOperator = with(value) {
        fields("id", "name", "avatar")
        PigOperator(string("id"), text("name"), text("avatar"))
    }
    private fun locationName(value: PigLocationName) = obj("code" to value.code, "nameCn" to value.nameCn, "nameEn" to value.nameEn)
    private fun locationName(value: JSONObject): PigLocationName = with(value) {
        fields("code", "nameCn", "nameEn")
        PigLocationName(string("code"), string("nameCn"), string("nameEn"))
    }
    private fun location(value: PigProfileLocation) = obj("zoneId" to value.zoneId, "unitId" to value.unitId, "zoneLocation" to value.zoneLocation,
        "unitLocation" to value.unitLocation, "sectionLocation" to value.sectionLocation, "rowLocation" to value.rowLocation,
        "penLocation" to value.penLocation, "penNumber" to value.penNumber, "zone" to value.zone?.let(::locationName),
        "unit" to value.unit?.let(::locationName), "section" to value.section?.let(::locationName), "rowCode" to value.rowCode,
        "rowNumber" to value.rowNumber)
    private fun location(value: JSONObject): PigProfileLocation = with(value) {
        fields("zoneId", "unitId", "zoneLocation", "unitLocation", "sectionLocation", "rowLocation", "penLocation", "penNumber", "zone", "unit", "section", "rowCode", "rowNumber")
        PigProfileLocation(string("zoneId"), string("unitId"), text("zoneLocation"), text("unitLocation"),
            text("sectionLocation"), text("rowLocation"), text("penLocation"), integer("penNumber"),
            nested("zone")?.let(::locationName), nested("unit")?.let(::locationName), nested("section")?.let(::locationName), text("rowCode"),
            text("rowNumber"))
    }
    private fun batchTask(value: PigBatchTask) = obj("id" to value.id, "kind" to value.kind, "status" to value.status)
    private fun batchTask(value: JSONObject): PigBatchTask = with(value) {
        fields("id", "kind", "status")
        PigBatchTask(string("id"), string("kind"), string("status"))
    }
    private fun batch(value: PigProfileBatch) = obj("id" to value.id, "serial" to value.serial, "status" to value.status,
        "lineName" to value.lineName, "tasks" to array(value.tasks, ::batchTask), "farmId" to value.farmId)
    private fun batch(value: JSONObject): PigProfileBatch = with(value) {
        fieldsWithOptional("farmId", "id", "serial", "status", "lineName", "tasks")
        PigProfileBatch(string("id"), string("serial"), string("status"), text("lineName"),
            items("tasks").map(::batchTask), if (has("farmId")) text("farmId") else null)
    }
    private fun semen(value: PigSemenControl) = obj("boarId" to value.boarId, "farmId" to value.farmId, "collectionStatus" to value.collectionStatus,
        "cooldownUntil" to value.cooldownUntil, "lastCollectedAt" to value.lastCollectedAt, "manuallyMarked" to value.manuallyMarked,
        "markedSince" to value.markedSince, "modifiedAt" to value.modifiedAt, "offProduction" to value.offProduction,
        "offProductionSince" to value.offProductionSince)
    private fun semen(value: JSONObject): PigSemenControl = with(value) {
        fields("boarId", "farmId", "collectionStatus", "cooldownUntil", "lastCollectedAt", "manuallyMarked", "markedSince", "modifiedAt", "offProduction", "offProductionSince")
        PigSemenControl(string("boarId"), string("farmId"), string("collectionStatus"), at("cooldownUntil"),
            at("lastCollectedAt"), boolean("manuallyMarked"), at("markedSince"), at("modifiedAt"),
            boolean("offProduction"), at("offProductionSince"))
    }
    private fun diseaseInfo(value: DiseaseInfo) = obj("id" to value.id, "nameCn" to value.nameCn, "nameEn" to value.nameEn,
        "abbrCn" to value.abbrCn, "abbrEn" to value.abbrEn, "categoryCn" to value.categoryCn,
        "categoryEn" to value.categoryEn, "descriptionCn" to value.descriptionCn, "descriptionEn" to value.descriptionEn)
    private fun diseaseInfo(value: JSONObject): DiseaseInfo = with(value) {
        fields("id", "nameCn", "nameEn", "abbrCn", "abbrEn", "categoryCn", "categoryEn", "descriptionCn", "descriptionEn")
        DiseaseInfo(string("id"), string("nameCn"), string("nameEn"), string("abbrCn"),
            string("abbrEn"), string("categoryCn"), string("categoryEn"), string("descriptionCn"),
            string("descriptionEn"))
    }
    private fun symptomInfo(value: SymptomInfo) = obj("id" to value.id, "nameCn" to value.nameCn, "nameEn" to value.nameEn,
        "partCn" to value.partCn, "partEn" to value.partEn, "descriptionCn" to value.descriptionCn,
        "descriptionEn" to value.descriptionEn)
    private fun symptomInfo(value: JSONObject): SymptomInfo = with(value) {
        fields("id", "nameCn", "nameEn", "partCn", "partEn", "descriptionCn", "descriptionEn")
        SymptomInfo(string("id"), string("nameCn"), string("nameEn"), string("partCn"),
            string("partEn"), string("descriptionCn"), string("descriptionEn"))
    }
    private fun disease(value: PigDisease) = obj("id" to value.id, "createdAt" to value.createdAt, "operator" to value.operator?.let(::operator),
        "detail" to value.detail?.let(::diseaseInfo))
    private fun disease(value: JSONObject): PigDisease = with(value) {
        fields("id", "createdAt", "operator", "detail")
        PigDisease(string("id"), at("createdAt"), nested("operator")?.let(::operator), nested("detail")?.let(::diseaseInfo))
    }
    private fun symptom(value: PigSymptom) = obj("targetId" to value.targetId, "id" to value.id, "createdAt" to value.createdAt,
        "operator" to value.operator?.let(::operator), "detail" to value.detail?.let(::symptomInfo))
    private fun symptom(value: JSONObject): PigSymptom = with(value) {
        fields("targetId", "id", "createdAt", "operator", "detail")
        PigSymptom(string("targetId"), string("id"), at("createdAt"), nested("operator")?.let(::operator),
            nested("detail")?.let(::symptomInfo))
    }
    private fun named(value: NamedReference) = obj("id" to value.id, "nameCn" to value.nameCn, "nameEn" to value.nameEn)
    private fun named(value: JSONObject): NamedReference = with(value) {
        fields("id", "nameCn", "nameEn")
        NamedReference(string("id"), string("nameCn"), string("nameEn"))
    }
    private fun treatment(value: PigTreatmentReference) = obj("id" to value.id, "nameCn" to value.nameCn, "nameEn" to value.nameEn,
        "detailCn" to value.detailCn, "detailEn" to value.detailEn)
    private fun treatment(value: JSONObject): PigTreatmentReference = with(value) {
        fields("id", "nameCn", "nameEn", "detailCn", "detailEn")
        PigTreatmentReference(string("id"), string("nameCn"), string("nameEn"), string("detailCn"),
            string("detailEn"))
    }
    private fun therapy(value: PigTherapyReference) = obj("treatment" to value.treatment?.let(::treatment), "medication" to value.medication?.let(::named), "medicationCatalog" to value.medicationCatalog?.let(::named))
    private fun therapy(value: JSONObject): PigTherapyReference = with(value) {
        fields("treatment", "medication", "medicationCatalog")
        PigTherapyReference(nested("treatment")?.let(::treatment), nested("medication")?.let(::named), nested("medicationCatalog")?.let(::named))
    }
    private fun prescription(value: PigPrescriptionReference) = obj("id" to value.id, "therapies" to value.therapies?.let { array(it, ::therapy) })
    private fun prescription(value: JSONObject): PigPrescriptionReference = with(value) {
        fields("id", "therapies")
        PigPrescriptionReference(string("id"), if (get("therapies") == JSONObject.NULL) null else items("therapies").map(::therapy))
    }
    private fun history(value: PigHealthHistory) = obj("id" to value.id, "createdAt" to value.createdAt, "event" to value.event,
        "triage" to value.triage, "remark" to value.remark, "symptoms" to value.symptoms?.encoded,
        "diseases" to value.diseases?.encoded, "operatorId" to value.operatorId, "operator" to value.operator?.let(::operator))
    private fun history(value: JSONObject): PigHealthHistory = with(value) {
        fields("id", "createdAt", "event", "triage", "remark", "symptoms", "diseases", "operatorId", "operator")
        PigHealthHistory(integer("id"), at("createdAt"), string("event"), text("triage"),
            text("remark"), json("symptoms"), json("diseases"), text("operatorId"),
            nested("operator")?.let(::operator))
    }
    private fun health(value: PigHealthProfile) = obj("id" to value.id, "farmId" to value.farmId, "triage" to value.triage,
        "triageChangedAt" to value.triageChangedAt, "triageOperator" to value.triageOperator?.let(::operator), "temperature" to value.temperature?.toString(),
        "temperatureOperatorId" to value.temperatureOperatorId, "temperatureMeasuredAt" to value.temperatureMeasuredAt, "temperatureOperator" to value.temperatureOperator?.let(::operator),
        "modifiedAt" to value.modifiedAt, "prescriptionCount" to value.prescriptionCount, "prescriptions" to array(value.prescriptions, ::prescription),
        "diseases" to array(value.diseases, ::disease), "symptoms" to array(value.symptoms, ::symptom), "history" to array(value.history, ::history))
    private fun health(value: JSONObject): PigHealthProfile = with(value) {
        fields("id", "farmId", "triage", "triageChangedAt", "triageOperator", "temperature", "temperatureOperatorId", "temperatureMeasuredAt", "temperatureOperator", "modifiedAt", "prescriptionCount", "prescriptions", "diseases", "symptoms", "history")
        PigHealthProfile(string("id"), string("farmId"), text("triage"), at("triageChangedAt"),
            nested("triageOperator")?.let(::operator), decimal("temperature"), text("temperatureOperatorId"), at("temperatureMeasuredAt"),
            nested("temperatureOperator")?.let(::operator), at("modifiedAt"), number("prescriptionCount"), items("prescriptions").map(::prescription),
            items("diseases").map(::disease), items("symptoms").map(::symptom), items("history").map(::history))
    }
    private fun profile(value: PigProfile) = obj("id" to value.id, "farmId" to value.farmId, "earTag" to value.earTag,
        "earNotch" to value.earNotch, "extendedEarTag" to value.extendedEarTag, "role" to value.role,
        "sex" to value.sex, "lifecycle" to value.lifecycle, "origin" to value.origin,
        "locationCode" to value.locationCode, "birthday" to value.birthday, "entryDate" to value.entryDate,
        "remark" to value.remark, "weight" to value.weight?.toString(), "parity" to value.parity,
        "breedType" to value.breedType, "breederCode" to value.breederCode, "teatCount" to value.teatCount,
        "firstEstrusAt" to value.firstEstrusAt, "lastEstrusAt" to value.lastEstrusAt, "lastFarrowingAt" to value.lastFarrowingAt,
        "lastLactationAt" to value.lastLactationAt, "lastMatingAt" to value.lastMatingAt, "productionStatus" to value.productionStatus,
        "currentBatchId" to value.currentBatchId, "breederProductionStatus" to value.breederProductionStatus, "batch" to value.batch?.let(::batch),
        "semen" to value.semen?.let(::semen), "location" to value.location?.let(::location), "health" to value.health?.let(::health),
        "deathImages" to value.deathImages?.encoded,
        "breederStatusUpdatedAt" to value.breederStatusUpdatedAt)
    private fun profile(value: JSONObject): PigProfile = with(value) {
        fieldsWithOptional("breederStatusUpdatedAt", "id", "farmId", "earTag", "earNotch", "extendedEarTag", "role", "sex", "lifecycle", "origin", "locationCode", "birthday", "entryDate", "remark", "weight", "parity", "breedType", "breederCode", "teatCount", "firstEstrusAt", "lastEstrusAt", "lastFarrowingAt", "lastLactationAt", "lastMatingAt", "productionStatus", "currentBatchId", "breederProductionStatus", "batch", "semen", "location", "health", "deathImages")
        PigProfile(string("id"), string("farmId"), text("earTag"), text("earNotch"),
            text("extendedEarTag"), string("role"), text("sex"), string("lifecycle"),
            string("origin"), text("locationCode"), at("birthday"), at("entryDate"),
            text("remark"), decimal("weight"), number("parity"), text("breedType"),
            text("breederCode"), number("teatCount"), at("firstEstrusAt"), at("lastEstrusAt"),
            at("lastFarrowingAt"), at("lastLactationAt"), at("lastMatingAt"), text("productionStatus"),
            text("currentBatchId"), text("breederProductionStatus"), nested("batch")?.let(::batch), nested("semen")?.let(::semen),
            nested("location")?.let(::location), nested("health")?.let(::health), json("deathImages"),
            if (has("breederStatusUpdatedAt")) at("breederStatusUpdatedAt") else null)
    }
    private fun request(value: PigProfileRequest) = obj("unit" to value.filter.unitCode, "pen" to value.filter.penLocation,
        "search" to value.filter.search, "limit" to value.filter.pageSize, "ids" to value.ids?.let { JSONArray(it.sorted()) },
        "roles" to JSONArray(value.roles.map { it.name }.sorted()), "lifecycles" to JSONArray(value.lifecycles.map { it.name }.sorted()),
        "batch" to value.batchId, "order" to value.order.name, "offset" to value.offset, "count" to value.includeCount)
        .apply { value.unitId?.let { put("unitId", it) } }
    private fun request(value: JSONObject): PigProfileRequest = with(value) {
        val expected = setOf("unit", "pen", "search", "limit", "ids", "roles", "lifecycles", "batch", "order", "offset", "count")
        require(keys().asSequence().toSet().let { it == expected || it == expected + "unitId" })
        PigProfileRequest(PigFilter(string("unit"), text("pen"), string("search"), integer("limit")),
            if (get("ids") == JSONObject.NULL) null else stringSet("ids"), stringSet("roles").map(PigRole::valueOf).toSet(),
            stringSet("lifecycles").map(PigLifecycle::valueOf).toSet(), text("batch"), PigProfileOrder.valueOf(string("order")), integer("offset"), boolean("count"),
            if (has("unitId")) string("unitId") else null)
    }
    fun key(value: PigProfileRequest) = request(value).toString()
    fun encodeDetail(value: PigProfile?) = obj("format" to 1, "kind" to "PIG_PROFILE", "value" to value?.let(::profile)).toString()
    fun decodeDetail(text: String): PigProfile? = JSONObject(text).let {
        it.fields("format", "kind", "value"); require(it.integer("format") == 1 && it.string("kind") == "PIG_PROFILE")
        it.nested("value")?.let(::profile)
    }
    fun encodePage(value: PigProfilePage) = obj("format" to 1, "kind" to "PIG_PROFILE_PAGE", "request" to request(value.request),
        "pigs" to array(value.pigs, ::profile), "total" to value.total).toString()
    fun decodePage(text: String): PigProfilePage = JSONObject(text).let {
        it.fields("format", "kind", "request", "pigs", "total"); require(it.integer("format") == 1 && it.string("kind") == "PIG_PROFILE_PAGE")
        PigProfilePage(request(requireNotNull(it.nested("request"))), it.items("pigs").map(::profile), it.number("total"))
    }
}
