package com.sentri.nativeapp.network

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.*
import com.apollographql.apollo.exception.JsonDataException
import okio.Buffer
import java.math.BigDecimal
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private inline fun <T> decodeScalar(block: () -> T): T = try { block() } catch (_: IllegalArgumentException) {
    // Apollo preserves its own exceptions; plain argument failures are wrapped as network errors.
    throw JsonDataException("Invalid scalar value")
}

object DecimalAdapter : Adapter<BigDecimal> {
    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): BigDecimal =
        decodeScalar { BigDecimal(if (reader.peek() == JsonReader.Token.STRING) requireNotNull(reader.nextString()) else reader.nextNumber().value) }
    override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: BigDecimal) {
        writer.value(JsonNumber(value.toPlainString()))
    }
}

/** Production commands accept decimal strings; keep the business value exact until the server parses it. */
object DecimalStringAdapter : Adapter<BigDecimal> {
    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): BigDecimal =
        DecimalAdapter.fromJson(reader, customScalarAdapters)
    override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: BigDecimal) {
        writer.value(value.toPlainString())
    }
}

/** Keep the server offset and subsecond precision; this slice does not compute ages or durations. */
object TimestampAdapter : Adapter<String> {
    fun validate(value: String): String {
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})$").matchEntire(value)
            ?: throw IllegalArgumentException("Invalid timestamp")
        val date = "${match.groupValues[1]}T${match.groupValues[2]}"
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val position = ParsePosition(0)
        require(formatter.parse(date, position) != null && position.index == date.length)
        val zone = match.groupValues[4]
        if (zone != "Z") { val digits = zone.substring(1).replace(":", ""); require(digits.take(2).toInt() <= 23 && digits.takeLast(2).toInt() <= 59) }
        return value
    }
    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters) = decodeScalar { validate(requireNotNull(reader.nextString())) }
    override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: String) { writer.value(validate(value)) }
}

/** Canonical JSON text, preserving number lexemes rather than converting through Double. */
data class JsonValue(val encoded: String)
object JsonValueAdapter : Adapter<JsonValue> {
    fun validate(encoded: String): JsonValue = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use {
        fromJson(it, CustomScalarAdapters.Empty).also { _ -> require(it.peek() == JsonReader.Token.END_DOCUMENT) }
    }
    private fun copy(reader: JsonReader, writer: JsonWriter, depth: Int = 0) {
        require(depth <= 64)
        when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject(); writer.beginObject()
                while (reader.hasNext()) { writer.name(reader.nextName()); copy(reader, writer, depth + 1) }
                reader.endObject(); writer.endObject()
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray(); writer.beginArray()
                while (reader.hasNext()) copy(reader, writer, depth + 1)
                reader.endArray(); writer.endArray()
            }
            JsonReader.Token.STRING -> writer.value(requireNotNull(reader.nextString()))
            JsonReader.Token.NUMBER, JsonReader.Token.LONG -> writer.value(reader.nextNumber())
            JsonReader.Token.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonReader.Token.NULL -> { reader.nextNull(); writer.nullValue() }
            else -> throw IllegalArgumentException("Unsupported JSON value")
        }
    }
    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): JsonValue {
        val buffer = Buffer(); val writer = BufferedSinkJsonWriter(buffer)
        decodeScalar { copy(reader, writer) }; writer.flush()
        return JsonValue(buffer.readUtf8())
    }
    override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: JsonValue) {
        BufferedSourceJsonReader(Buffer().writeUtf8(value.encoded)).use {
            copy(it, writer); require(it.peek() == JsonReader.Token.END_DOCUMENT)
        }
    }
}
