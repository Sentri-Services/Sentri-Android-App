package com.sentri.nativeapp.pigs

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.offline.CommandInput

/** Upload references are operation identities, never transient local files or guessed server URLs. */
sealed interface DeathImage {
    data class Remote(val url: String) : DeathImage { init { require(!url.startsWith("data:")) } }
    data class Uploaded(val operationId: String) : DeathImage { init { require(operationId.isNotBlank()) } }
}
data class DeathPigInput(val pigId: String? = null, val motherId: String? = null, val earTag: String? = null,
    val earNotch: String? = null, val locationCode: String? = null, val reason: String? = null,
    val images: List<DeathImage> = emptyList()) {
    // The legacy contract permits unidentified piglets and uses the mother for local ordering.
    val targetId: String? get() = pigId ?: motherId
    init { require(listOfNotNull(pigId, motherId).all(String::isNotBlank)) }
    fun graphql(urls: List<String>) = ProductionDeathPigStrawberryInput(pig_id = Optional.Present(pigId),
        mother_id = Optional.Present(motherId), ear_tag = Optional.Present(earTag), ear_notch = Optional.Present(earNotch),
        location_code = Optional.Present(locationCode), death_reason = Optional.Present(reason), death_images = Optional.Present(urls))
}
data class ReportPigDeathInput(val pigs: List<DeathPigInput>, override val taskId: String? = null) : CommandInput {
    override val operationName: String get() = "ReportPigDeath"
    override val productionType: String get() = "unknown"
    // Actual pigs and mothers' litter aggregates are both modified by the backend death workflow.
    override val targetPigIds: Set<String> get() = pigs.flatMap { listOfNotNull(it.pigId, it.motherId) }.toSet()
    init { require(pigs.isNotEmpty() && (taskId == null || taskId.isNotBlank())) }
    fun graphql(resolve: (String) -> String) = ProductionReportPigDeathCommandStrawberryInput(pigs = Optional.Present(pigs.map { pig ->
        pig.graphql(pig.images.map { image -> when (image) {
            is DeathImage.Remote -> image.url
            is DeathImage.Uploaded -> resolve(image.operationId).also { require(it.isNotBlank() && !it.startsWith("data:")) }
        } })
    }))
}
data class UploadPigDeathImageInput(val reportOperationId: String, val pigIndex: Int, val imageIndex: Int,
    val dataUrl: String, val targetPigId: String?, override val taskId: String? = null) : CommandInput {
    override val operationName: String get() = "UploadPigDeathImage"
    override val productionType: String get() = "unknown"
    override val targetPigIds: Set<String> get() = setOfNotNull(targetPigId)
    init { require(reportOperationId.isNotBlank() && pigIndex >= 0 && imageIndex >= 0 && dataUrl.startsWith("data:"))
        require((targetPigId == null || targetPigId.isNotBlank()) && (taskId == null || taskId.isNotBlank())) }
    fun graphql() = ProductionUploadPigDeathImageCommandStrawberryInput(data_url = dataUrl)
}
/** Draft image strings retain the previous application's data-URL/remote-URL convention. */
data class DeathPigDraft(val pig: DeathPigInput, val imageSources: List<String>) {
    init { require(pig.images.isEmpty()) }
}
data class PigDeathDraft(val pigs: List<DeathPigDraft>, val taskId: String? = null) {
    init { require(pigs.isNotEmpty() && (taskId == null || taskId.isNotBlank())) }
}
