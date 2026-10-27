package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Versioned schemas for sow checks and location-level growing tasks. */
object StageTaskCodecs {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray(values.map(encode))
    private fun JSONObject.fields(vararg expected: String) { require(keys().asSequence().toSet() == expected.toSet()) }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid stage snapshot string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.at(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid stage snapshot boolean")
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) { is Int -> value; is Long -> Math.toIntExact(value); else -> error("Invalid stage snapshot integer") }
    private fun JSONObject.count(key: String) = integer(key).also { require(it >= 0) }
    private fun JSONObject.number(key: String): Int? = if (get(key) == JSONObject.NULL) null else count(key)
    private fun JSONObject.decimal(key: String) = text(key)?.let(::BigDecimal)?.also { require(it.signum() >= 0) }
    private fun JSONObject.nested(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid stage snapshot object")
    private fun JSONObject.items(key: String): List<JSONObject> = (get(key) as? JSONArray ?: error("Invalid stage snapshot list")).let { a -> (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid stage snapshot item") } }
    private fun location(value: TaskPigLocation) = obj("code" to value.locationCode, "unit" to value.unitId, "unitLocation" to value.unitLocation, "pen" to value.penLocation)
    private fun location(value: JSONObject): TaskPigLocation {
        value.fields("code", "unit", "unitLocation", "pen")
        return TaskPigLocation(value.text("code"), value.text("unit"), value.text("unitLocation"), value.text("pen"))
    }
    private fun task(value: ProductionTaskSnapshot, pigs: JSONArray) = obj("id" to value.id, "farm" to value.farmId, "batch" to value.batchId, "status" to value.status, "pigs" to pigs)
    private class Codec<T : ProductionTaskSnapshot>(override val type: String, val write: (T) -> JSONObject, val read: (JSONObject) -> T) : TaskCodec<T> {
        override fun encode(value: T?): String {
            require(value == null || value.type == type)
            return obj("format" to 1, "type" to type, "task" to value?.let(write)).toString()
        }
        override fun decode(text: String): T? = JSONObject(text).let {
            it.fields("format", "type", "task"); require(it.integer("format") == 1 && it.string("type") == type)
            it.nested("task")?.let(read)
        }
    }
    private fun sow(value: SowCheckResult?) = value?.let { obj("disabled" to it.disabled, "at" to it.checkedAt, "assessment" to it.assessment?.encoded, "description" to it.description,
        "farrowing" to it.farrowingAt, "farrowed" to it.farrowed, "obsolete" to it.obsoleted, "reason" to it.obsoletedReason, "operator" to it.operatorId) }
    private fun sow(value: JSONObject?): SowCheckResult? = value?.let {
        it.fields("disabled", "at", "assessment", "description", "farrowing", "farrowed", "obsolete", "reason", "operator")
        SowCheckResult(it.boolean("disabled"), it.at("at"), it.text("assessment")?.let(JsonValueAdapter::validate), it.text("description"), it.at("farrowing"),
            it.boolean("farrowed"), it.boolean("obsolete"), it.text("reason"), it.text("operator"))
    }
    val postpartum: TaskCodec<PostpartumTask> = Codec("POSTPARTUM", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to sow(it.result))
    }) }, { root ->
        root.fields("id", "farm", "batch", "status", "pigs")
        PostpartumTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            PostpartumPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), location(requireNotNull(it.nested("location"))), sow(it.nested("result")))
        })
    })
    private fun lactation(value: LactationResult?) = value?.let { result ->
        obj("sow" to sow(result.sow), "female" to result.femaleCount, "male" to result.maleCount, "noIdentity" to result.noIdentityCount, "initial" to result.initialTotalCount,
            "weight" to result.totalWeight?.toString(), "identities" to array(result.identities) {
                obj("sequence" to it.piglet.sequence, "weight" to it.piglet.birthWeight?.toString(), "tag" to it.piglet.earTag, "notch" to it.piglet.earNotch, "sex" to it.piglet.sex?.name, "breeder" to it.breeder)
            }, "counts" to array(result.counts) { obj("type" to it.type.name, "count" to it.count) })
    }
    private fun lactation(value: JSONObject?): LactationResult? = value?.let {
        it.fields("sow", "female", "male", "noIdentity", "initial", "weight", "identities", "counts")
        val identities = it.items("identities").map { piglet ->
            piglet.fields("sequence", "weight", "tag", "notch", "sex", "breeder")
            LactationIdentity(IdentityPiglet(piglet.count("sequence"), piglet.decimal("weight"), piglet.text("tag"), piglet.text("notch"), piglet.text("sex")?.let(PigletSex::valueOf)), piglet.boolean("breeder"))
        }
        val counts = it.items("counts").map { count -> count.fields("type", "count"); PigletCount(FarrowingPigletType.valueOf(count.string("type")), count.count("count")) }
        require(identities.map { pig -> pig.piglet.sequence }.distinct().size == identities.size && counts.map { count -> count.type }.distinct().size == counts.size)
        LactationResult(requireNotNull(sow(it.nested("sow"))), it.count("female"), it.count("male"), it.count("noIdentity"), it.number("initial"), it.decimal("weight"), identities, counts)
    }
    val lactation: TaskCodec<LactationTask> = Codec("LACTATION", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to lactation(it.result))
    }) }, { root ->
        root.fields("id", "farm", "batch", "status", "pigs")
        LactationTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            LactationPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), location(requireNotNull(it.nested("location"))), lactation(it.nested("result")))
        })
    })
    private fun growing(stage: GrowingStage): TaskCodec<GrowingTask> = Codec(stage.name, { value ->
        task(value, array(value.pigs) {
            obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "sex" to it.sex?.name, "weight" to it.weight?.toString(),
                "result" to it.result?.let { result -> obj("identity" to result.hasIdentity, "breeder" to result.breeder, "location" to result.locationCode) })
        }).put("locations", array(value.locations) {
            obj("code" to it.code, "at" to it.checkedAt, "created" to it.createdAt, "operator" to it.operatorId, "count" to it.totalCount, "weight" to it.totalWeight.toString())
        })
    }, { root ->
        root.fields("id", "farm", "batch", "status", "pigs", "locations")
        val pigs = root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "sex", "weight", "result")
            val result = it.nested("result")?.let { record -> record.fields("identity", "breeder", "location"); GrowingPigResult(record.boolean("identity"), record.boolean("breeder"), record.string("location").also { code -> require(code.isNotBlank()) }) }
            GrowingPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), location(requireNotNull(it.nested("location"))), it.text("sex")?.let(PigletSex::valueOf), it.decimal("weight"), result)
        }
        val locations = root.items("locations").map {
            it.fields("code", "at", "created", "operator", "count", "weight")
            NurseryLocation(it.string("code").also { code -> require(code.isNotBlank()) }, it.at("at"), it.at("created"), it.text("operator"), it.count("count"), requireNotNull(it.decimal("weight")))
        }
        require(locations.map { it.code }.distinct().size == locations.size)
        GrowingTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), stage, pigs, locations)
    })
    val nursery = growing(GrowingStage.NURSERY)
    val fattening = growing(GrowingStage.FATTENING)
}
