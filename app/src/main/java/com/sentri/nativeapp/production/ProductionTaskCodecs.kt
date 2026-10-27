package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.network.JsonValueAdapter
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** Explicit business schemas; no reflection or serialization of Apollo generated types. */
object ProductionTaskCodecs {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray(values.map(encode))
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid snapshot string")
    private fun JSONObject.text(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.at(key: String) = text(key)?.let(TimestampAdapter::validate)
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) {
        is Int -> value
        is Long -> Math.toIntExact(value)
        else -> error("Invalid snapshot integer")
    }
    private fun JSONObject.count(key: String) = integer(key).also { require(it >= 0) }
    private fun JSONObject.number(key: String): Int? = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid snapshot boolean")
    private fun JSONObject.bool(key: String): Boolean? = if (get(key) == JSONObject.NULL) null else boolean(key)
    private fun JSONObject.nested(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid snapshot object")
    private fun JSONObject.items(key: String): List<JSONObject> {
        val array = get(key) as? JSONArray ?: error("Invalid snapshot array")
        return (0 until array.length()).map { array.get(it) as? JSONObject ?: error("Invalid snapshot item") }
    }
    private fun JSONObject.fields(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
    private fun task(value: ProductionTaskSnapshot, pigs: JSONArray) = obj("id" to value.id, "farm" to value.farmId, "batch" to value.batchId, "status" to value.status, "pigs" to pigs)
    private fun location(value: TaskPigLocation?) = value?.let { obj("code" to it.locationCode, "unit" to it.unitId, "unitLocation" to it.unitLocation, "pen" to it.penLocation) }
    private fun location(value: JSONObject?): TaskPigLocation? = value?.let {
        it.fields("code", "unit", "unitLocation", "pen")
        TaskPigLocation(it.text("code"), it.text("unit"), it.text("unitLocation"), it.text("pen"))
    }
    private class Codec<T : ProductionTaskSnapshot>(override val type: String, val write: (T) -> JSONObject, val read: (JSONObject) -> T) : TaskCodec<T> {
        override fun encode(value: T?): String = obj("format" to 1, "type" to type, "task" to value?.let(write)).toString()
        override fun decode(text: String): T? = JSONObject(text).let { root ->
            root.fields("format", "type", "task")
            require(root.integer("format") == 1 && root.string("type") == type)
            root.nested("task")?.let {
                it.fields("id", "farm", "batch", "status", "pigs")
                read(it)
            }
        }
    }
    private fun JSONObject.decimal(key: String): BigDecimal? = text(key)?.let(::BigDecimal)?.also { require(it.signum() >= 0) }
    private fun identities(value: List<IdentityPiglet>) = array(value) {
        obj("sequence" to it.sequence, "weight" to it.birthWeight?.toString(), "tag" to it.earTag, "notch" to it.earNotch, "sex" to it.sex?.name)
    }
    private fun identities(value: JSONObject) = value.items("identities").map {
        it.fields("sequence", "weight", "tag", "notch", "sex")
        IdentityPiglet(it.count("sequence"), it.decimal("weight"), it.text("tag"), it.text("notch"), it.text("sex")?.let(PigletSex::valueOf))
    }.also { require(it.map { piglet -> piglet.sequence }.distinct().size == it.size) }
    private fun farrowingResult(value: FarrowingResult?) = value?.let { result ->
        obj("status" to result.status.name, "disabled" to result.disabled, "adopt" to result.adoptCount, "foster" to result.fosterCount, "noIdentity" to result.noIdentityCount,
            "first" to result.firstAt, "last" to result.lastAt, "mating" to result.matingAt, "matingBy" to result.matingBy, "recheck" to result.reCheckAt, "operator" to result.operatorId,
            "identities" to identities(result.identities), "counts" to array(result.counts) { obj("type" to it.type.name, "count" to it.count) },
            "records" to array(result.records) { obj("id" to it.id, "alive" to it.aliveCount, "dead" to it.deadCount, "at" to it.submittedAt, "operator" to it.operatorId) })
    }
    private fun farrowingResult(value: JSONObject?): FarrowingResult? = value?.let {
        it.fields("status", "disabled", "adopt", "foster", "noIdentity", "first", "last", "mating", "matingBy", "recheck", "operator", "identities", "counts", "records")
        val counts = it.items("counts").map { count -> count.fields("type", "count"); PigletCount(FarrowingPigletType.valueOf(count.string("type")), count.count("count")) }
        val records = it.items("records").map { record -> record.fields("id", "alive", "dead", "at", "operator"); FarrowingRecord(record.integer("id"), record.count("alive"), record.count("dead"), record.at("at"), record.text("operator")) }
        require(counts.map { count -> count.type }.distinct().size == counts.size && records.map { record -> record.id }.distinct().size == records.size)
        FarrowingResult(FarrowingStatus.valueOf(it.string("status")), it.boolean("disabled"), it.count("adopt"), it.count("foster"), it.count("noIdentity"),
            it.at("first"), it.at("last"), it.at("mating"), it.text("matingBy"), it.at("recheck"), it.text("operator"), identities(it), counts, records)
    }
    val farrowing: TaskCodec<FarrowingTask> = Codec("FARROWING", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to farrowingResult(it.result))
    }) }, { root ->
        FarrowingTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            FarrowingPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), requireNotNull(location(it.nested("location"))), farrowingResult(it.nested("result")))
        })
    })
    private fun pigletResult(value: PigletResult?) = value?.let { result ->
        obj("disabled" to result.disabled, "farrowed" to result.farrowed, "adopt" to result.adoptCount, "foster" to result.fosterCount, "noIdentity" to result.noIdentityCount,
            "female" to result.femaleCount, "male" to result.maleCount, "litter" to result.litterNumber, "last" to result.lastFarrowingAt, "weight" to result.totalWeight?.toString(),
            "items" to result.items?.encoded, "identities" to identities(result.identities), "todo" to array(result.todo) {
                obj("type" to it.type.name, "days" to it.planDays, "at" to it.completedAt, "operator" to it.operatorId)
            })
    }
    private fun pigletResult(value: JSONObject?): PigletResult? = value?.let {
        it.fields("disabled", "farrowed", "adopt", "foster", "noIdentity", "female", "male", "litter", "last", "weight", "items", "identities", "todo")
        val todo = it.items("todo").map { item ->
            item.fields("type", "days", "at", "operator"); PigletTodo(PigletHandlingItem.valueOf(item.string("type")), item.integer("days"), item.at("at"), item.text("operator"))
        }
        require(todo.map { item -> item.type }.distinct().size == todo.size)
        PigletResult(it.boolean("disabled"), it.boolean("farrowed"), it.count("adopt"), it.count("foster"), it.count("noIdentity"), it.count("female"), it.count("male"),
            it.count("litter"), it.at("last"), it.decimal("weight"), it.text("items")?.let(JsonValueAdapter::validate), identities(it), todo)
    }
    val piglet: TaskCodec<PigletTask> = Codec("PIGLET_PROCESSING", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to pigletResult(it.result))
    }) }, { root ->
        PigletTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            PigletPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), requireNotNull(location(it.nested("location"))), pigletResult(it.nested("result")))
        })
    })
    val estrus: TaskCodec<EstrusTask> = Codec("ESTRUS", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "status" to it.checkStatus?.name,
            "checked" to it.checkAt, "inEstrus" to it.isInEstrus, "estrusAt" to it.estrusAt, "location" to location(it.location))
    }) }, { root ->
        EstrusTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "status", "checked", "inEstrus", "estrusAt", "location")
            EstrusPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), it.text("status")?.let(EstrusStatus::valueOf), it.at("checked"), it.bool("inEstrus"), it.at("estrusAt"), location(it.nested("location")))
        })
    })
    private fun material(value: MatingMaterial?) = value?.let {
        obj("id" to it.id, "boar" to it.boarId, "semen" to it.semenId, "breeder" to it.breederType, "collected" to it.collectedAt,
            "count" to it.count, "tag" to it.boarEarTag, "notch" to it.boarEarNotch)
    }
    private fun material(value: JSONObject?): MatingMaterial? = value?.let {
        it.fields("id", "boar", "semen", "breeder", "collected", "count", "tag", "notch")
        MatingMaterial(it.integer("id"), it.text("boar"), it.text("semen"), it.text("breeder"), it.at("collected"), it.number("count")?.also { n -> require(n >= 0) }, it.text("tag"), it.text("notch"))
    }
    private fun summary(value: MatingSummary?) = value?.let { obj("count" to it.count, "estrus" to it.estrusAt, "created" to it.createdAt, "first" to it.firstAt, "firstWith" to it.firstWith, "last" to it.lastAt, "lastWith" to it.lastWith) }
    private fun summary(value: JSONObject?): MatingSummary? = value?.let {
        it.fields("count", "estrus", "created", "first", "firstWith", "last", "lastWith")
        MatingSummary(it.count("count"), it.at("estrus"), it.at("created"), it.at("first"), it.text("firstWith"), it.at("last"), it.text("lastWith"))
    }
    val mating: TaskCodec<MatingTask> = Codec("MATING", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "summary" to summary(it.summary), "location" to location(it.location),
            "records" to array(it.records) { record -> obj("at" to record.at, "times" to record.times, "with" to record.with, "operator" to record.operatorId, "boar" to material(record.boar), "semen" to material(record.semen)) })
    }) }, { root ->
        MatingTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "summary", "location", "records")
            MatingPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), summary(it.nested("summary")), it.items("records").map { record ->
                record.fields("at", "times", "with", "operator", "boar", "semen")
                MatingRecord(record.at("at"), record.count("times"), record.string("with"), record.text("operator"), material(record.nested("boar")), material(record.nested("semen")))
            }, location(it.nested("location")))
        })
    })
    private fun reResult(value: ReEstrusResult?) = value?.let { obj("status" to it.status?.name, "checked" to it.checkedAt, "returned" to it.isInReEstrus, "returnAt" to it.reEstrusAt, "mating" to it.matingAt, "matingBy" to it.matingBy, "submitted" to it.submittedAt, "operator" to it.operatorId) }
    private fun reResult(value: JSONObject?): ReEstrusResult? = value?.let {
        it.fields("status", "checked", "returned", "returnAt", "mating", "matingBy", "submitted", "operator")
        ReEstrusResult(it.text("status")?.let(ReEstrusStatus::valueOf), it.at("checked"), it.boolean("returned"), it.at("returnAt"), it.at("mating"), it.text("matingBy"), it.at("submitted"), it.text("operator"))
    }
    val reEstrus: TaskCodec<ReEstrusTask> = Codec("RE_ESTRUS", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to reResult(it.result))
    }) }, { root ->
        ReEstrusTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            ReEstrusPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), requireNotNull(location(it.nested("location"))), reResult(it.nested("result")))
        })
    })
    private fun pregResult(value: PregnancyResult?) = value?.let { obj("status" to it.status?.name, "checked" to it.checkedAt, "pregnant" to it.isPregnant, "recheck" to it.reCheckAt, "mating" to it.matingAt, "matingBy" to it.matingBy, "submitted" to it.submittedAt, "operator" to it.operatorId) }
    private fun pregResult(value: JSONObject?): PregnancyResult? = value?.let {
        it.fields("status", "checked", "pregnant", "recheck", "mating", "matingBy", "submitted", "operator")
        PregnancyResult(it.text("status")?.let(PregnancyStatus::valueOf), it.at("checked"), it.bool("pregnant"), it.at("recheck"), it.at("mating"), it.text("matingBy"), it.at("submitted"), it.text("operator"))
    }
    val pregnancy: TaskCodec<PregnancyTask> = Codec("PREGNANCY", { value -> task(value, array(value.pigs) {
        obj("id" to it.id, "tag" to it.earTag, "notch" to it.earNotch, "disabled" to it.disabled, "location" to location(it.location), "result" to pregResult(it.result))
    }) }, { root ->
        PregnancyTask(root.string("id"), root.string("farm"), root.string("batch"), root.string("status"), root.items("pigs").map {
            it.fields("id", "tag", "notch", "disabled", "location", "result")
            PregnancyPig(it.string("id"), it.text("tag"), it.text("notch"), it.boolean("disabled"), requireNotNull(location(it.nested("location"))), pregResult(it.nested("result")))
        })
    })
}
