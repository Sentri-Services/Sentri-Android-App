package com.sentri.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.network.FarmChoice

/** Temporary integration screen. Business previews use the separate Sentri design foundation. */
@Composable
fun ConnectionScreen(state: ConnectionUiState, config: AppConfig, onLogin: () -> Unit, onRefresh: () -> Unit,
    onChoose: (FarmChoice) -> Unit, onSwitch: () -> Unit, onLogout: () -> Unit,
    onCallback: (String) -> Unit, onCloseWeb: () -> Unit, onWebFailure: () -> Unit, onBusiness: () -> Unit = {},
    onHomePreview: () -> Unit = {}, onDevices: (() -> Unit)? = null) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.integration_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.environment, config.environment))
            if (state.webUrl != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(if (state.loggingOut) R.string.signing_out else R.string.signing_in))
                    OutlinedButton(onClick = onCloseWeb, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                }
                LoginWebView(state.webUrl, config, onCallback, onCloseWeb, onWebFailure, Modifier.weight(1f).fillMaxWidth())
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (state.page) { Page.LOGIN -> R.string.signed_out; Page.FARMS -> R.string.choose_farm; Page.ACCOUNT -> R.string.signed_in }))
                    state.failure?.let { failure ->
                        Text(stringResource(when (failure.kind) {
                            Failure.NETWORK -> R.string.network_error
                            Failure.AUTHENTICATION -> R.string.session_expired
                            Failure.LOGIN -> R.string.login_error
                            Failure.BUSINESS -> R.string.business_error
                            Failure.UNKNOWN -> R.string.unknown_error
                        }), color = MaterialTheme.colorScheme.error)
                        failure.detail?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    }
                    when (state.page) {
                        Page.LOGIN -> {
                            Button(onClick = onLogin, enabled = !state.busy) { Text(stringResource(R.string.login)) }
                            OutlinedButton(onClick = onHomePreview) { Text(stringResource(R.string.home_preview_entry)) }
                        }
                        Page.FARMS -> {
                            if (state.farms.isEmpty() && !state.busy && state.failure == null) Text(stringResource(R.string.no_farms))
                            state.farms.forEach { farm ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.farm_id, farm.farmId))
                                        if (farm.isChosen) Text(stringResource(R.string.previously_chosen))
                                        if (farm.isDefault) Text(stringResource(R.string.default_farm))
                                        Button(onClick = { onChoose(farm) }, enabled = !state.busy) { Text(stringResource(R.string.select)) }
                                    }
                                }
                            }
                        }
                        Page.ACCOUNT -> state.account?.let { account ->
                            OutlinedButton(onClick = onHomePreview) { Text(stringResource(R.string.home_preview_entry)) }
                            Button(onClick = onBusiness, enabled = !state.busy) { Text(stringResource(R.string.pig_read_title)) }
                            onDevices?.let { callback -> OutlinedButton(onClick = callback, enabled = !state.busy) { Text(stringResource(R.string.device_validation)) } }
                            Text(stringResource(if (account.cachedAtMillis == null) R.string.query_success else R.string.cached_account))
                            Text(stringResource(R.string.user_name, account.identity.displayName ?: account.identity.username))
                            Text(stringResource(R.string.farm_name, account.farm.name))
                            Text(stringResource(R.string.farm_id, account.farm.id))
                            Text(stringResource(R.string.user_role, account.identity.role))
                            OutlinedButton(onClick = onSwitch, enabled = !state.busy) { Text(stringResource(R.string.switch_farm)) }
                        }
                    }
                    if (state.page != Page.LOGIN) {
                        OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.refresh)) }
                        OutlinedButton(onClick = onLogout, enabled = !state.busy) { Text(stringResource(R.string.logout)) }
                    }
                    Text(stringResource(R.string.native_endpoint, config.graphqlUrl), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.busy) CircularProgressIndicator()
        }
    }
}
