package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

enum class PigTriageLevel { Emergency, Healthy, Normal, Priority }
enum class TemperatureUnit { Celsius, Fahrenheit }

sealed interface PigHealthCommandInput : CommandInput {
    val pigId: String
    override val productionType: String get() = "unknown"
    override val taskId: String? get() = null
    override val targetPigIds: Set<String> get() = setOf(pigId)
}

data class AddPigDiseaseInput(
    override val pigId: String, val diseaseIds: List<String>, val earTag: String? = null,
    val imageUrls: List<String> = emptyList(), val penLocation: String? = null,
    val reason: String? = null, val triageLevel: PigTriageLevel? = null,
) : PigHealthCommandInput {
    init { require(pigId.isNotBlank() && diseaseIds.all(String::isNotBlank) && imageUrls.all(String::isNotBlank)) }
    override val operationName = "AddPigDisease"
    fun graphql() = BiosafetyAddPigDiseaseInput(pig_id = pigId, disease_ids = Optional.Present(diseaseIds),
        image_urls = Optional.Present(imageUrls), ear_tag = Optional.Present(earTag), pen_location = Optional.Present(penLocation),
        reason = Optional.Present(reason), triage_level = Optional.Present(triageLevel?.let { BiosafetyPigTriageLevel.valueOf(it.name) }))
}
data class AddPigSymptomInput(
    override val pigId: String, val symptomIds: List<String>, val earTag: String? = null,
    val imageUrls: List<String> = emptyList(), val penLocation: String? = null,
    val reason: String? = null, val triageLevel: PigTriageLevel? = null,
) : PigHealthCommandInput {
    init { require(pigId.isNotBlank() && symptomIds.all(String::isNotBlank) && imageUrls.all(String::isNotBlank)) }
    override val operationName = "AddPigSymptom"
    fun graphql() = BiosafetyAddPigSymptomInput(pig_id = pigId, symptom_ids = Optional.Present(symptomIds),
        image_urls = Optional.Present(imageUrls), ear_tag = Optional.Present(earTag), pen_location = Optional.Present(penLocation),
        reason = Optional.Present(reason), triage_level = Optional.Present(triageLevel?.let { BiosafetyPigTriageLevel.valueOf(it.name) }))
}
data class RemovePigDiseaseInput(override val pigId: String, val diseaseIds: List<String>,
    val recovered: Boolean = false, val penLocation: String? = null) : PigHealthCommandInput {
    init { require(pigId.isNotBlank() && diseaseIds.all(String::isNotBlank)) }
    override val operationName = "RemovePigDisease"
    fun graphql() = BiosafetyRemovePigDiseaseInput(pig_id = pigId, disease_ids = Optional.Present(diseaseIds),
        is_recovered = Optional.Present(recovered), pen_location = Optional.Present(penLocation))
}
data class RemovePigSymptomInput(override val pigId: String, val symptomIds: List<String>,
    val recovered: Boolean = false, val penLocation: String? = null) : PigHealthCommandInput {
    init { require(pigId.isNotBlank() && symptomIds.all(String::isNotBlank)) }
    override val operationName = "RemovePigSymptom"
    fun graphql() = BiosafetyRemovePigSymptomInput(pig_id = pigId, symptom_ids = Optional.Present(symptomIds),
        is_recovered = Optional.Present(recovered), pen_location = Optional.Present(penLocation))
}
data class SetPigTriageLevelInput(override val pigId: String, val earTag: String,
    val level: PigTriageLevel, val penLocation: String? = null, val reason: String? = null) : PigHealthCommandInput {
    init { require(pigId.isNotBlank()) }
    override val operationName = "SetPigTriageLevel"
    fun graphql() = BiosafetySetPigTriageLevelInput(pig_id = pigId, ear_tag = earTag,
        triage_level = BiosafetyPigTriageLevel.valueOf(level.name), pen_location = Optional.Present(penLocation),
        reason = Optional.Present(reason))
}
data class SetPigBodyTemperatureInput(override val pigId: String, val earTag: String,
    val temperature: BigDecimal, val unit: TemperatureUnit = TemperatureUnit.Celsius,
    val triageLevel: PigTriageLevel? = null, val penLocation: String? = null) : PigHealthCommandInput {
    init { require(pigId.isNotBlank()) }
    override val operationName = "SetPigBodyTemperature"
    fun graphql() = BiosafetySetPigBodyTemperatureInput(pig_id = pigId, ear_tag = earTag,
        temperature = temperature, unit = Optional.Present(BiosafetyTemperatureUnit.valueOf(unit.name)),
        triage_level = Optional.Present(triageLevel?.let { BiosafetyPigTriageLevel.valueOf(it.name) }),
        pen_location = Optional.Present(penLocation))
}

data class HealthDisease(val id: String, val nameCn: String, val nameEn: String,
    val abbrCn: String, val abbrEn: String, val categoryCn: String, val categoryEn: String,
    val descriptionCn: String, val descriptionEn: String)
data class HealthSymptom(val id: String, val nameCn: String, val nameEn: String,
    val partCn: String, val partEn: String, val descriptionCn: String, val descriptionEn: String)
data class HealthReferenceCatalog(val diseases: List<HealthDisease>, val symptoms: List<HealthSymptom>)
