package com.sentri.nativeapp.inspection

import com.sentri.nativeapp.graphql.NativeHealthReferenceCatalogQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.*
import org.json.JSONArray
import org.json.JSONObject

interface HealthReferenceRemote { suspend fun catalog(lease: ScopeLease): HealthReferenceCatalog }

class GraphqlHealthReferenceReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : HealthReferenceRemote {
    override suspend fun catalog(lease: ScopeLease): HealthReferenceCatalog {
        check(lease)
        val data = executor.query(NativeHealthReferenceCatalogQuery())
        check(lease)
        return HealthReferenceCatalog(data.farm_view_disease.map { HealthDisease(it.id, it.name_cn, it.name_en,
            it.abbr_cn, it.abbr_en, it.category_cn, it.category_en, it.description_cn, it.description_en) },
            data.farm_view_symptom.map { HealthSymptom(it.id, it.name_cn, it.name_en, it.part_cn, it.part_en,
                it.description_cn, it.description_en) }).also { catalog ->
            try { HealthReferenceReadCodec.validate(catalog) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        }
    }
}

internal object HealthReferenceReadCodec {
    fun validate(value: HealthReferenceCatalog) {
        require(value.diseases.all { it.id.isNotBlank() } && value.diseases.map { it.id }.distinct().size == value.diseases.size)
        require(value.symptoms.all { it.id.isNotBlank() } && value.symptoms.map { it.id }.distinct().size == value.symptoms.size)
    }
    fun encode(value: HealthReferenceCatalog): String = JSONObject().put("format", 1)
        .put("diseases", JSONArray().apply { value.diseases.forEach { item ->
            put(JSONObject().put("id", item.id).put("cn", item.nameCn).put("en", item.nameEn)
                .put("abbrCn", item.abbrCn).put("abbrEn", item.abbrEn).put("categoryCn", item.categoryCn)
                .put("categoryEn", item.categoryEn).put("descriptionCn", item.descriptionCn).put("descriptionEn", item.descriptionEn))
        } }).put("symptoms", JSONArray().apply { value.symptoms.forEach { item ->
            put(JSONObject().put("id", item.id).put("cn", item.nameCn).put("en", item.nameEn)
                .put("partCn", item.partCn).put("partEn", item.partEn)
                .put("descriptionCn", item.descriptionCn).put("descriptionEn", item.descriptionEn))
        } }).toString()
    fun decode(encoded: String): HealthReferenceCatalog {
        val root = JSONObject(encoded)
        require(root.keys().asSequence().toSet() == setOf("format", "diseases", "symptoms") && root.getInt("format") == 1)
        val diseases = root.getJSONArray("diseases").let { rows -> (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "cn", "en", "abbrCn", "abbrEn", "categoryCn", "categoryEn", "descriptionCn", "descriptionEn"))
            HealthDisease(row.getString("id"), row.getString("cn"), row.getString("en"), row.getString("abbrCn"),
                row.getString("abbrEn"), row.getString("categoryCn"), row.getString("categoryEn"),
                row.getString("descriptionCn"), row.getString("descriptionEn"))
        } }
        val symptoms = root.getJSONArray("symptoms").let { rows -> (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "cn", "en", "partCn", "partEn", "descriptionCn", "descriptionEn"))
            HealthSymptom(row.getString("id"), row.getString("cn"), row.getString("en"), row.getString("partCn"),
                row.getString("partEn"), row.getString("descriptionCn"), row.getString("descriptionEn"))
        } }
        return HealthReferenceCatalog(diseases, symptoms).also(::validate)
    }
}

class HealthReferenceReads(private val remote: HealthReferenceRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun catalog(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<HealthReferenceCatalog> =
        snapshots.read(lease, "health_references", "all", cacheOnly, { remote.catalog(lease) },
            HealthReferenceReadCodec::encode, HealthReferenceReadCodec::decode, HealthReferenceReadCodec::validate)
}
