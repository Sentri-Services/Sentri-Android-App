package com.sentri.nativeapp

import com.sentri.nativeapp.ui.ConnectionUiState
import com.sentri.nativeapp.ui.Page

internal fun shouldShowHome(
    state: ConnectionUiState,
    previewOpen: Boolean,
    integrationOpen: Boolean,
    businessOpen: Boolean,
    devicesOpen: Boolean,
): Boolean {
    if (previewOpen) return true
    if (integrationOpen || businessOpen || devicesOpen) return false
    return state.page == Page.ACCOUNT && state.account != null && state.webUrl == null
}

internal fun openIntegrationAfterHomeExit(state: ConnectionUiState): Boolean = state.account != null
