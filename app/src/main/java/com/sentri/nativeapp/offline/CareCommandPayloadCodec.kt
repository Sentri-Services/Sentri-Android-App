package com.sentri.nativeapp.offline

import com.sentri.nativeapp.production.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Care payloads preserve decimal strings, explicit nulls and ordered selections. */
internal object CareCommandPayloadCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun base(input: CarePigInput) = obj("task" to input.taskId, "pig" to input.pigId)
    private fun decimal(value: BigDecimal?) = value?.toString()
    private fun assessment(value: SowAssessment?) = value?.let { obj("activity" to it.activity.name, "backfat" to it.backfat.name, "body" to it.body.name,
        "feeding" to it.feeding.name, "mastitis" to it.mastitis.name, "milk" to it.milk.name, "secretion" to it.secretion.name,
        "prolapse" to it.prolapse?.name, "recurred" to it.recurred, "resolved" to it.resolved) }
    private fun assessment(value: JSONObject?): SowAssessment? = value?.let {
        it.fields("activity", "backfat", "body", "feeding", "mastitis", "milk", "secretion", "prolapse", "recurred", "resolved")
        SowAssessment(ActivityLevel.valueOf(it.string("activity")), BackfatThickness.valueOf(it.string("backfat")), BodyConditionScore.valueOf(it.string("body")),
            FeedingSituation.valueOf(it.string("feeding")), MastitisSeverity.valueOf(it.string("mastitis")), MilkProductionCapacity.valueOf(it.string("milk")),
            SecretionStatus.valueOf(it.string("secretion")), it.text("prolapse")?.let(ProlapseType::valueOf), it.bool("recurred"), it.bool("resolved"))
    }
    private fun identities(value: List<IdentityPigletInput>?) = value?.let { JSONArray(it.map { piglet ->
        obj("weight" to decimal(piglet.birthWeight), "tag" to piglet.earTag, "notch" to piglet.earNotch, "breeder" to piglet.breeder, "sex" to piglet.sex?.name)
    }) }
    private fun identities(value: JSONObject) = value.nullableObjects("identities")?.map {
        it.fields("weight", "tag", "notch", "breeder", "sex")
        IdentityPigletInput(it.number("weight"), it.text("tag"), it.text("notch"), it.boolean("breeder"), it.text("sex")?.let(PigletSex::valueOf))
    }
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is MarkTaskPigInput -> base(input).put("batch", input.batchId).put("remark", input.remark ?: JSONObject.NULL)
        is CompleteTaskInput -> obj("task" to input.taskId, "batch" to input.batchId, "kind" to input.kind.name, "at" to input.endedAt)
        is SubmitPostpartumInput -> base(input).put("assessment", assessment(input.assessment)).put("at", input.checkedAt ?: JSONObject.NULL)
            .put("description", input.description ?: JSONObject.NULL).put("obsolete", input.obsoleted).put("reason", input.obsoletedReason ?: JSONObject.NULL)
        is SubmitLactationInput -> base(input).put("assessment", assessment(input.assessment) ?: JSONObject.NULL).put("at", input.checkedAt ?: JSONObject.NULL)
            .put("description", input.description ?: JSONObject.NULL).put("obsolete", input.obsoleted).put("reason", input.obsoletedReason ?: JSONObject.NULL)
            .put("female", input.femaleCount).put("male", input.maleCount).put("weight", decimal(input.totalWeight) ?: JSONObject.NULL)
            .put("identities", identities(input.identities) ?: JSONObject.NULL)
            .put("counts", input.counts?.let { JSONArray(it.map { count -> obj("type" to count.type.name, "count" to count.count) }) } ?: JSONObject.NULL)
        is CheckGrowingInput -> obj("task" to input.taskId, "location" to input.locationCode, "at" to input.checkedAt,
            "assessment" to obj("healthy" to input.assessment.healthyCount, "malformed" to input.assessment.malformedCount, "female" to input.assessment.femaleCount,
                "male" to input.assessment.maleCount, "weight" to decimal(input.assessment.totalWeight), "weak" to input.assessment.weakCount),
            "identities" to input.identities?.let { JSONArray(it.map { pig -> obj("pig" to pig.pigId, "location" to pig.locationCode, "identity" to pig.hasIdentity,
                "breeder" to pig.breeder, "tag" to pig.earTag, "notch" to pig.earNotch, "sex" to pig.sex?.name, "weight" to decimal(pig.weight)) }) })
        is SubmitFarrowingInput -> base(input).put("completed", input.completed).put("counts", input.counts?.let { counts ->
            JSONArray(counts.map { obj("type" to it.type.name, "count" to it.count) }) } ?: JSONObject.NULL).put("at", input.submitAt ?: JSONObject.NULL)
        is SubmitPigletItemsInput -> base(input).put("items", JSONArray(input.items.map { it.name })).put("at", input.submitAt ?: JSONObject.NULL)
        is SubmitPigletWeightInput -> base(input).put("weight", decimal(input.totalWeight)).put("at", input.submitAt ?: JSONObject.NULL)
        is SubmitPigletInfoInput -> base(input).put("female", input.femaleCount).put("male", input.maleCount)
            .put("identities", input.identities?.let { identities -> JSONArray(identities.map {
                obj("weight" to decimal(it.birthWeight), "tag" to it.earTag, "notch" to it.earNotch, "breeder" to it.breeder, "sex" to it.sex?.name)
            }) } ?: JSONObject.NULL).put("weight", decimal(input.totalWeight) ?: JSONObject.NULL)
            .put("increase", input.increaseReason ?: JSONObject.NULL).put("reduce", input.reduceReason ?: JSONObject.NULL).put("at", input.submitAt ?: JSONObject.NULL)
        is FosterPigletInput -> base(input).put("adopt", input.adoptPigId).put("stage", input.stage.name)
            .put("tags", input.earTags?.let(::JSONArray) ?: JSONObject.NULL).put("notches", input.earNotches?.let(::JSONArray) ?: JSONObject.NULL)
            .put("count", input.noIdentityCount).put("at", input.transferAt ?: JSONObject.NULL)
        else -> null
    }
    fun decode(operation: String, input: JSONObject): CommandInput? = with(input) {
        when (operation) {
            "MarkTaskPig" -> {
                fields("task", "pig", "batch", "remark")
                MarkTaskPigInput(string("task"), string("pig"), string("batch"), text("remark"))
            }
            "CompleteProductionTask" -> {
                fields("task", "batch", "kind", "at")
                CompleteTaskInput(string("task"), string("batch"), ProductionTaskKind.valueOf(string("kind")), text("at"))
            }
            "SubmitPostpartumPigStatus" -> {
                fields("task", "pig", "assessment", "at", "description", "obsolete", "reason")
                SubmitPostpartumInput(string("task"), string("pig"), requireNotNull(assessment(nested("assessment"))), text("at"), text("description"), boolean("obsolete"), text("reason"))
            }
            "SubmitLactationPigStatus" -> {
                fields("task", "pig", "assessment", "at", "description", "obsolete", "reason", "female", "male", "weight", "identities", "counts")
                SubmitLactationInput(string("task"), string("pig"), assessment(nested("assessment")), text("at"), text("description"), boolean("obsolete"), text("reason"),
                    integer("female"), integer("male"), number("weight"), identities(this), nullableObjects("counts")?.map {
                        it.fields("type", "count"); PigletCount(FarrowingPigletType.valueOf(it.string("type")), it.integer("count"))
                    })
            }
            "CheckNurseryPigStatus", "CheckFatteningPigStatus" -> {
                fields("task", "location", "at", "assessment", "identities")
                val state = requireNotNull(nested("assessment")); state.fields("healthy", "malformed", "female", "male", "weight", "weak")
                CheckGrowingInput(string("task"), string("location"), if (operation == "CheckNurseryPigStatus") GrowingStage.NURSERY else GrowingStage.FATTENING,
                    GrowingAssessment(state.integer("healthy"), state.integer("malformed"), state.integer("female"), state.integer("male"), requireNotNull(state.number("weight")), state.integer("weak")),
                    nullableObjects("identities")?.map {
                        it.fields("pig", "location", "identity", "breeder", "tag", "notch", "sex", "weight")
                        GrowingIdentityInput(it.string("pig"), it.string("location"), it.boolean("identity"), it.boolean("breeder"), it.text("tag"), it.text("notch"), it.text("sex")?.let(PigletSex::valueOf), it.number("weight"))
                    }, text("at"))
            }
            "SubmitFarrowingPiglet" -> {
                fields("task", "pig", "completed", "counts", "at")
                SubmitFarrowingInput(string("task"), string("pig"), boolean("completed"), nullableObjects("counts")?.map {
                    it.fields("type", "count"); PigletCount(FarrowingPigletType.valueOf(it.string("type")), it.integer("count"))
                }, text("at"))
            }
            "SubmitPigletHandlingItem" -> {
                fields("task", "pig", "items", "at")
                SubmitPigletItemsInput(string("task"), string("pig"), strings("items").map(PigletHandlingItem::valueOf), text("at"))
            }
            "SubmitPigletTotalWeight" -> {
                fields("task", "pig", "weight", "at")
                SubmitPigletWeightInput(string("task"), string("pig"), requireNotNull(number("weight")), text("at"))
            }
            "SubmitPigletInfo" -> {
                fields("task", "pig", "female", "male", "identities", "weight", "increase", "reduce", "at")
                SubmitPigletInfoInput(string("task"), string("pig"), integer("female"), integer("male"), nullableObjects("identities")?.map {
                    it.fields("weight", "tag", "notch", "breeder", "sex")
                    IdentityPigletInput(it.number("weight"), it.text("tag"), it.text("notch"), it.boolean("breeder"), it.text("sex")?.let(PigletSex::valueOf))
                }, number("weight"), text("increase"), text("reduce"), text("at"))
            }
            "FosterPiglet" -> {
                fields("task", "pig", "adopt", "stage", "tags", "notches", "count", "at")
                FosterPigletInput(string("task"), string("pig"), string("adopt"), FosterStage.valueOf(string("stage")),
                    if (get("tags") == JSONObject.NULL) null else strings("tags"),
                    if (get("notches") == JSONObject.NULL) null else strings("notches"), integer("count"), text("at"))
            }
            else -> null
        }
    }
    private fun JSONObject.fields(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) { "Invalid care payload fields" } }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid care string")
    private fun JSONObject.text(key: String) = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.number(key: String) = text(key)?.let(::BigDecimal)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid care boolean")
    private fun JSONObject.bool(key: String): Boolean? = if (get(key) == JSONObject.NULL) null else boolean(key)
    private fun JSONObject.nested(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid care object")
    private fun JSONObject.integer(key: String): Int = when (val value = get(key)) { is Int -> value; is Long -> Math.toIntExact(value); else -> error("Invalid care integer") }
    private fun JSONObject.array(key: String) = get(key) as? JSONArray ?: error("Invalid care array")
    private fun JSONObject.strings(key: String): List<String> = array(key).let { a -> (0 until a.length()).map { a.get(it) as? String ?: error("Invalid care string item") } }
    private fun JSONObject.nullableObjects(key: String): List<JSONObject>? = if (get(key) == JSONObject.NULL) null else array(key).let { a -> (0 until a.length()).map { a.get(it) as? JSONObject ?: error("Invalid care object item") } }
}
