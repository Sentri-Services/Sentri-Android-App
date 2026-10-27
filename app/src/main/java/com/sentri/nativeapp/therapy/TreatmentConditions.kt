package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.pigs.PigProfile

enum class TreatmentConditionKind { SYMPTOM, DISEASE }

/** Legacy catalogue identity for this preview, never a backend finding ID. */
data class TreatmentConditionKey(val kind: TreatmentConditionKind, val catalogId: String) {
    init { require(catalogId.isNotBlank()) }
}

data class TreatmentConditionOption(val key: TreatmentConditionKey, val chinese: String, val english: String,
    val pigIds: Set<String>, val searchTerms: Set<String>) {
    fun label(chinese: Boolean): String = if (chinese) this.chinese.ifBlank { english }
        else english.ifBlank { this.chinese }
}

data class TreatmentConditionOptions(val options: List<TreatmentConditionOption>,
    val unavailableRecords: Int, val unavailablePigs: Int)

object TreatmentConditions {
    /** A treatment can cover every selected pig while its optional condition covers only carriers. */
    fun existing(pigs: List<PigProfile>): TreatmentConditionOptions {
        data class Entry(val key: TreatmentConditionKey, val pigId: String, val chinese: String,
            val english: String, val terms: List<String>)
        val entries = mutableListOf<Entry>()
        var unavailable = 0
        var unavailablePigs = 0
        pigs.distinctBy(PigProfile::id).filter { it.lifecycle == "ACTIVE" }.forEach { pig ->
            val health = pig.health
            if (health == null || health.farmId != pig.farmId) { unavailablePigs++; return@forEach }
            health.diseases.forEach { disease ->
                if (disease.id.isBlank() || disease.detail?.id?.let { it != disease.id } == true) unavailable++
                else entries += Entry(TreatmentConditionKey(TreatmentConditionKind.DISEASE, disease.id), pig.id,
                    disease.detail?.nameCn.orEmpty(), disease.detail?.nameEn.orEmpty(),
                    listOfNotNull(disease.detail?.abbrCn, disease.detail?.abbrEn))
            }
            health.symptoms.forEach { symptom ->
                if (symptom.id.isBlank() || symptom.targetId != pig.id ||
                    symptom.detail?.id?.let { it != symptom.id } == true) unavailable++
                else entries += Entry(TreatmentConditionKey(TreatmentConditionKind.SYMPTOM, symptom.id), pig.id,
                    symptom.detail?.nameCn.orEmpty(), symptom.detail?.nameEn.orEmpty(), emptyList())
            }
        }
        val options = entries.groupBy(Entry::key).mapNotNull { (key, records) ->
            val cn = records.firstNotNullOfOrNull { it.chinese.takeIf(String::isNotBlank) }.orEmpty()
            val en = records.firstNotNullOfOrNull { it.english.takeIf(String::isNotBlank) }.orEmpty()
            if (cn.isBlank() && en.isBlank()) { unavailable += records.size; null }
            else TreatmentConditionOption(key, cn, en, records.map(Entry::pigId).toSet(),
                (records.flatMap { it.terms + it.chinese + it.english }).filter(String::isNotBlank).toSet())
        }.sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.catalogId }))
        return TreatmentConditionOptions(options, unavailable, unavailablePigs)
    }
}
