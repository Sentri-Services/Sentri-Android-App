package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.network.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Versioned snapshot payloads are separate from Apollo response models. */
object SnapshotCodec {
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.integer(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.decimal(key: String): BigDecimal? = text(key)?.let(::BigDecimal)
    private fun JSONObject.timestamp(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.json(key: String) = text(key)?.let(JsonValueAdapter::validate)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun envelope(key: String, value: Any?): String = JSONObject().put("format", 1).put(key, value ?: JSONObject.NULL).toString()
    private fun parse(text: String): JSONObject = JSONObject(text).also { require(it.getInt("format") == 1) }
    private fun references(items: List<NamedReference>) = JSONArray(items.map { JSONObject().put("id", it.id).put("cn", it.nameCn).put("en", it.nameEn) })
    private fun references(items: JSONArray) = items.objects().map { NamedReference(it.getString("id"), it.getString("cn"), it.getString("en")) }
    private fun pig(value: Pig): JSONObject = JSONObject().apply {
        put("id", value.id); put("farm", value.farmId); put("tag", value.earTag); put("notch", value.earNotch); put("location", value.locationCode)
        put("role", value.role); put("sex", value.sex); put("lifecycle", value.lifecycle); put("production", value.productionStatus); put("parity", value.parity)
        put("weight", value.weight?.toPlainString()); put("birthday", value.birthday); put("entry", value.entryDate); put("remark", value.remark)
        put("batchId", value.batchId); put("batchSn", value.batchSn); put("unitId", value.unitId); put("pen", value.penLocation)
        put("triage", value.triage); put("temperature", value.temperature?.toPlainString())
        put("symptoms", references(value.symptoms)); put("diseases", references(value.diseases))
    }
    private fun pig(value: JSONObject) = with(value) {
        Pig(getString("id"), getString("farm"), text("tag"), text("notch"), text("location"), getString("role"), text("sex"), getString("lifecycle"), text("production"), integer("parity"), decimal("weight"), timestamp("birthday"), timestamp("entry"), text("remark"), text("batchId"), text("batchSn"), text("unitId"), text("pen"), text("triage"), decimal("temperature"), references(getJSONArray("symptoms")), references(getJSONArray("diseases")))
    }
    fun locations(value: Locations): String = envelope("locations", JSONObject()
        .put("units", JSONArray(value.units.map { JSONObject().put("id", it.id).put("farm", it.farmId).put("cn", it.nameCn).put("en", it.nameEn).put("location", it.locationCode).put("area", it.areaType)
            .put("sectionId", it.sectionId).put("sectionCode", it.sectionCode).put("sectionCn", it.sectionNameCn).put("sectionEn", it.sectionNameEn)
            .put("zoneCode", it.zoneCode).put("zoneCn", it.zoneNameCn).put("zoneEn", it.zoneNameEn) }))
        .put("pens", JSONArray(value.pens.map { JSONObject().put("id", it.id).put("farm", it.farmId).put("unit", it.unitId).put("location", it.locationCode).put("number", it.number) })))
    fun locations(text: String): Locations = parse(text).getJSONObject("locations").let { value ->
        Locations(value.getJSONArray("units").objects().map { FarmUnit(it.getString("id"), it.getString("farm"), it.getString("cn"), it.getString("en"), it.getString("location"), it.getString("area"),
            it.text("sectionId"), it.text("sectionCode"), it.text("sectionCn"), it.text("sectionEn"), it.text("zoneCode"), it.text("zoneCn"), it.text("zoneEn")) },
            value.getJSONArray("pens").objects().map { FarmPen(it.getInt("id"), it.getString("farm"), it.getString("unit"), it.text("location"), it.getInt("number")) })
    }
    fun page(value: PigPage) = envelope("page", JSONObject().put("pigs", JSONArray(value.pigs.map(::pig))).put("total", value.total).put("offset", value.offset).put("limit", value.limit))
    fun page(text: String): PigPage = parse(text).getJSONObject("page").let { PigPage(it.getJSONArray("pigs").objects().map(::pig), it.getInt("total"), it.getInt("offset"), it.getInt("limit")) }
    fun detail(value: PigDetail?): String = envelope("detail", value?.let { JSONObject().put("pig", pig(it.pig)).put("images", it.deathImages?.encoded).put("history", JSONArray(it.healthRecords.map { record ->
        JSONObject().put("id", record.id).put("at", record.occurredAt).put("event", record.event).put("remark", record.remark).put("symptoms", record.symptoms?.encoded).put("diseases", record.diseases?.encoded)
    })) })
    fun detail(text: String): PigDetail? = parse(text).let { if (it.isNull("detail")) null else it.getJSONObject("detail").let { value ->
        PigDetail(pig(value.getJSONObject("pig")), value.json("images"), value.getJSONArray("history").objects().map { record -> HealthRecord(record.getInt("id"), record.timestamp("at"), record.getString("event"), record.text("remark"), record.json("symptoms"), record.json("diseases")) })
    } }
    fun account(value: AccountContext) = envelope("account", JSONObject().put("user", value.identity.userId).put("username", value.identity.username).put("name", value.identity.displayName).put("role", value.identity.role).put("modules", JSONArray(value.identity.accessModules)).put("farm", value.farm.id).put("farmName", value.farm.name).put("farmTimezone", value.farm.timezone))
    fun account(text: String): AccountContext = parse(text).getJSONObject("account").let { AccountContext(Identity(it.getString("user"), it.getString("username"), it.text("name"), it.getString("role"), it.getJSONArray("modules").let { array -> (0 until array.length()).map(array::getString) }), Farm(it.getString("farm"), it.getString("farmName"), FarmTimezone.validated(it.text("farmTimezone")))) }
    fun browse(value: BrowsePosition) = envelope("browse", JSONObject().put("unit", value.filter.unitCode).put("pen", value.filter.penLocation).put("search", value.filter.search).put("size", value.filter.pageSize).put("offset", value.offset).put("detail", value.detailId).put("cacheOnly", value.cacheOnly))
    fun browse(text: String): BrowsePosition = parse(text).getJSONObject("browse").let {
        BrowsePosition(PigFilter(it.getString("unit"), it.text("pen"), it.getString("search"), it.getInt("size")), it.getInt("offset").also { require(it >= 0) }, it.text("detail"), it.getBoolean("cacheOnly"))
    }

}
