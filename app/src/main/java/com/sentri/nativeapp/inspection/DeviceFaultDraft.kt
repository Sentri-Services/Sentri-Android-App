package com.sentri.nativeapp.inspection

import android.net.Uri

/** Editable form. Only the draft store's successful receipt proves durable local photos. */
data class DeviceFaultDraft(val type: String? = null, val otherName: String = "",
    val description: String = "", val photoUris: List<Uri> = emptyList()) {
    val valid: Boolean get() = type in setOf("Feed", "Water", "Other") &&
        (type != "Other" || otherName.trim().isNotEmpty()) && description.trim().isNotEmpty() && photoUris.size <= 12
    val empty: Boolean get() = type == null && otherName.isEmpty() && description.isEmpty() && photoUris.isEmpty()
}
