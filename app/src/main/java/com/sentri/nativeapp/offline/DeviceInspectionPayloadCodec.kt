package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.*
import org.json.JSONObject

internal object DeviceInspectionPayloadCodec {
    fun encode(input: CommandInput): JSONObject? = when (input) {
        is ReportDeviceInspectionInput -> JSONObject().put("pen", input.penCode).put("type", input.deviceType.name)
            .put("status", input.deviceStatus.name).put("name", input.deviceName ?: JSONObject.NULL)
            .put("remark", input.remark ?: JSONObject.NULL).put("inspection", input.inspectionId ?: JSONObject.NULL)
        is ResolveDeviceInspectionInput -> JSONObject().put("referenceType", when (input.reference) {
            is DeviceInspectionReference.Server -> "server"
            is DeviceInspectionReference.LocalReport -> "local"
        }).put("reference", when (input.reference) {
            is DeviceInspectionReference.Server -> input.reference.id
            is DeviceInspectionReference.LocalReport -> input.reference.commandId
        })
        else -> null
    }
    fun decode(operation: String, fields: JSONObject): CommandInput? {
        fun text(key: String) = fields.get(key) as? String ?: error("Invalid device inspection field")
        fun nullable(key: String) = fields.get(key).let { if (it == JSONObject.NULL) null else it as? String ?: error("Invalid device inspection field") }
        return when (operation) {
            "ReportDeviceInspection" -> {
                require(fields.keys().asSequence().toSet() == setOf("pen", "type", "status", "name", "remark", "inspection"))
                ReportDeviceInspectionInput(text("pen"), DeviceType.valueOf(text("type")), DeviceStatus.valueOf(text("status")),
                    nullable("name"), nullable("remark"), nullable("inspection"))
            }
            "ResolveDeviceInspection" -> {
                require(fields.keys().asSequence().toSet() == setOf("referenceType", "reference"))
                ResolveDeviceInspectionInput(when (text("referenceType")) {
                    "server" -> DeviceInspectionReference.Server(text("reference"))
                    "local" -> DeviceInspectionReference.LocalReport(text("reference"))
                    else -> error("Invalid device inspection reference")
                })
            }
            else -> null
        }
    }
}
