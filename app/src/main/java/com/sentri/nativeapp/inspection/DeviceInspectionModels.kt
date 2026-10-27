package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.BiosafetyDeviceStatus
import com.sentri.nativeapp.graphql.type.BiosafetyDeviceType
import com.sentri.nativeapp.graphql.type.BiosafetyReportDeviceInspectionInput
import com.sentri.nativeapp.graphql.type.BiosafetyResolveDeviceInspectionInput
import com.sentri.nativeapp.offline.CommandInput

enum class DeviceType { Feed, Water, Other }
enum class DeviceStatus { Fault, Normal }

data class ReportDeviceInspectionInput(
    val penCode: String,
    val deviceType: DeviceType,
    val deviceStatus: DeviceStatus = DeviceStatus.Fault,
    val deviceName: String? = null,
    val remark: String? = null,
    val inspectionId: String? = null,
) : CommandInput {
    init { require(penCode.isNotBlank()); require(inspectionId == null || inspectionId.isNotBlank()) }
    override val operationName = "ReportDeviceInspection"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    override val groupKeys get() = setOf("deviceInspection:$penCode:${deviceType.name}")
    fun graphql() = BiosafetyReportDeviceInspectionInput(
        pen_code = penCode, device_type = BiosafetyDeviceType.valueOf(deviceType.name),
        device_status = Optional.Present(BiosafetyDeviceStatus.valueOf(deviceStatus.name)),
        device_name = Optional.Present(deviceName), remark = Optional.Present(remark),
        inspection_id = Optional.Present(inspectionId),
    )
}

sealed interface DeviceInspectionReference {
    data class Server(val id: String) : DeviceInspectionReference { init { require(id.isNotBlank()) } }
    data class LocalReport(val commandId: String) : DeviceInspectionReference { init { require(commandId.isNotBlank()) } }
}

data class ResolveDeviceInspectionInput(val reference: DeviceInspectionReference) : CommandInput {
    override val operationName = "ResolveDeviceInspection"
    override val productionType: String? = null
    override val taskId: String? = null
    override val targetPigIds = emptySet<String>()
    override val groupKeys get() = setOf("deviceInspection:${when (reference) {
        is DeviceInspectionReference.Server -> reference.id
        is DeviceInspectionReference.LocalReport -> reference.commandId
    }}")
    fun graphql() = BiosafetyResolveDeviceInspectionInput(inspection_id = when (reference) {
        is DeviceInspectionReference.Server -> reference.id
        is DeviceInspectionReference.LocalReport -> error("Local report ID requires a confirmed server receipt")
    })
}

data class DeviceInspection(
    val id: String, val farmId: String, val penCode: String, val deviceType: String,
    val deviceName: String?, val status: String, val remark: String?, val reportedAt: String?,
    val reporterId: String?, val resolvedAt: String?, val resolverId: String?,
)
