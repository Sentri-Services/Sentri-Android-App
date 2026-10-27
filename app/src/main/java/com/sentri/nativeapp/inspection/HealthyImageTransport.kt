package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeUploadHealthyImageMutation
import com.sentri.nativeapp.graphql.type.BiosafetyUploadHealthyImageInput
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

/** Standalone image upload. The current backend does not link its URL to a health event. */
data class UploadHealthyImageInput(val dataUrl: String) : CommandInput {
    init { require(dataUrl.isNotBlank()) }
    override val operationName = "UploadHealthyImage"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    fun graphql() = BiosafetyUploadHealthyImageInput(data_url = dataUrl)
}

data class HealthyImageReceipt(val fileKey: String, val fileUrl: String) {
    init { require(fileKey.isNotBlank() && fileUrl.isNotBlank() && !fileUrl.startsWith("data:")) }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("key"); value(fileKey)
            name("url"); value(fileUrl); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): HealthyImageReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var key: String? = null; var url: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "key" -> { require(reader.peek() == JsonReader.Token.STRING); key = reader.nextString() }
                    "url" -> { require(reader.peek() == JsonReader.Token.STRING); url = reader.nextString() }
                    else -> error("Invalid healthy image receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "key", "url") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            HealthyImageReceipt(requireNotNull(key), requireNotNull(url))
        }
    }
}

interface HealthyImageGateway { suspend fun upload(input: UploadHealthyImageInput): CommandOutcome }

class GraphqlHealthyImageGateway(private val executor: GraphqlExecutor) : HealthyImageGateway {
    override suspend fun upload(input: UploadHealthyImageInput): CommandOutcome {
        val response = executor.mutation(NativeUploadHealthyImageMutation(input.graphql())).biosafety.upload_healthy_image
        return when (response.status.rawValue) {
            "SUCCESS" -> try { CommandOutcome.Success(HealthyImageReceipt(response.file_key, response.file_url).encode()) }
                catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class HealthyImageTransport(private val gateway: HealthyImageGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? UploadHealthyImageInput ?: error("Unsupported healthy image operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.upload(input)
    }
}
