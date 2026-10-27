package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.*
import com.apollographql.apollo.api.json.*
import okio.Buffer

/** A successful image upload is only useful with both server fields durably retained. */
data class DeathImageReceipt(val fileKey: String, val fileUrl: String) {
    init { require(fileKey.isNotBlank() && fileUrl.isNotBlank() && !fileUrl.startsWith("data:")) }
    fun encode(): String {
        val buffer = Buffer()
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("version"); value(1); name("key"); value(fileKey); name("url"); value(fileUrl); endObject(); flush()
        }
        return buffer.readUtf8()
    }
    companion object {
        fun decode(value: String): DeathImageReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(value)).use { reader ->
            val seen = mutableSetOf<String>(); var key: String? = null; var url: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName(); require(seen.add(name))
                when (name) {
                    "version" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "key" -> { require(reader.peek() == JsonReader.Token.STRING); key = reader.nextString() }
                    "url" -> { require(reader.peek() == JsonReader.Token.STRING); url = reader.nextString() }
                    else -> error("Invalid image receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("version", "key", "url") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            DeathImageReceipt(requireNotNull(key), requireNotNull(url))
        }
    }
}
internal object DeathCommandReferences {
    /** Caller supplies only records from the report scope; every reference must belong to its exact slot. */
    fun uploads(reportOperationId: String, report: ReportPigDeathInput, scoped: List<QueuedCommand>): List<QueuedCommand> = buildList {
        report.pigs.forEachIndexed { p, pig -> pig.images.forEachIndexed { i, image ->
            if (image is DeathImage.Uploaded) {
                val command = scoped.singleOrNull { it.record.operationId == image.operationId } ?: error("Missing death image command")
                val upload = command.input as? UploadPigDeathImageInput ?: error("Invalid death image command")
                require(upload.reportOperationId == reportOperationId && upload.pigIndex == p && upload.imageIndex == i &&
                    upload.taskId == report.taskId && upload.targetPigId == pig.targetId) { "Foreign death image reference" }
                add(command)
            }
        } }
    }
    fun ready(command: QueuedCommand, scoped: List<QueuedCommand>): Boolean {
        val report = command.input as? ReportPigDeathInput ?: return true
        return uploads(command.record.operationId, report, scoped).all {
            it.record.status == CommandStatus.SYNCED && runCatching { DeathImageReceipt.decode(requireNotNull(it.receipt)) }.isSuccess
        }
    }
    fun urls(command: QueuedCommand, scoped: List<QueuedCommand>): Map<String, String> {
        require(ready(command, scoped)) { "Death images are not confirmed" }
        return uploads(command.record.operationId, command.input as ReportPigDeathInput, scoped).associate {
            it.record.operationId to DeathImageReceipt.decode(requireNotNull(it.receipt)).fileUrl
        }
    }
}
