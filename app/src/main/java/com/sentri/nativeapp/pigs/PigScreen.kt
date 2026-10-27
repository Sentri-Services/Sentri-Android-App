package com.sentri.nativeapp.pigs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.network.AccountContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Stock components are a verification surface, not the future product design. */
@Composable
fun PigScreen(state: PigUiState, model: PigViewModel, account: AccountContext, onExit: () -> Unit, onSwitch: () -> Unit, onLogout: () -> Unit) {
    BackHandler { if (state.detailId != null) model.closeDetail() else onExit() }
    val cn = LocalLocale.current.platformLocale.language == "zh"
    var search by remember(state.filter.search) { mutableStateOf(state.filter.search) }
    var unitMenu by remember { mutableStateOf(false) }
    var penMenu by remember { mutableStateOf(false) }
    val units = state.locations?.value?.units.orEmpty()
    val selected = units.find { it.locationCode == state.filter.unitCode }
    val pens = state.locations?.value?.pens.orEmpty().filter { it.unitId == selected?.id && it.locationCode != null }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("pig-screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.pig_read_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.farm_name, account.farm.name))
                account.cachedAtMillis?.let { Text(stringResource(R.string.cached_account)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExit) { Text(stringResource(R.string.back)) }
                    OutlinedButton(onClick = onSwitch) { Text(stringResource(R.string.switch_farm)) }
                    OutlinedButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
                }
                Row {
                    Checkbox(checked = state.cacheOnly, onCheckedChange = model::cacheOnly, enabled = !state.loading)
                    Text(stringResource(R.string.cache_only), Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::refresh, enabled = !state.loading) { Text(stringResource(R.string.refresh)) }
                    OutlinedButton(onClick = model::clearCache, enabled = !state.loading) { Text(stringResource(R.string.clear_current_cache)) }
                }
                if (state.loading) CircularProgressIndicator(Modifier.testTag("pig-loading"))
                state.failure?.let {
                    Text(stringResource(when (it) {
                        PigFailure.NETWORK -> R.string.network_error
                        PigFailure.AUTHENTICATION -> R.string.session_expired
                        PigFailure.BUSINESS -> R.string.business_error
                        PigFailure.PROTOCOL -> R.string.pig_protocol_error
                        PigFailure.CACHE_EMPTY -> R.string.cache_empty
                        PigFailure.CACHE_INVALID -> R.string.cache_invalid
                        PigFailure.UNKNOWN -> R.string.unknown_error
                    }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pig-error"))
                    Button(onClick = model::retry, enabled = !state.loading) { Text(stringResource(R.string.retry)) }
                }
            }
            if (state.detailId != null) {
                item {
                    OutlinedButton(onClick = model::closeDetail) { Text(stringResource(R.string.back_to_pigs)) }
                    Text(stringResource(R.string.pig_detail), style = MaterialTheme.typography.titleLarge)
                    state.detail?.let { result ->
                        SourceLabel(result)
                        val detail = result.value
                        if (detail == null) Text(stringResource(R.string.pig_not_found)) else {
                            val pig = detail.pig
                            Text(pig.label, modifier = Modifier.testTag("pig-detail-label"))
                            Text(stringResource(R.string.pig_identity, pig.id))
                            Text(stringResource(R.string.pig_location, pig.locationCode.orEmpty()))
                            Text(stringResource(R.string.pig_status, pig.role, pig.lifecycle, pig.productionStatus.orEmpty()))
                            Text(stringResource(R.string.pig_measurements, pig.weight?.toPlainString().orEmpty(), pig.temperature?.toPlainString().orEmpty(), pig.parity?.toString().orEmpty()))
                            Text(stringResource(R.string.pig_dates, pig.birthday.orEmpty(), pig.entryDate.orEmpty()))
                            Text(stringResource(R.string.pig_batch, pig.batchSn.orEmpty()))
                            Text(stringResource(R.string.pig_remark, pig.remark.orEmpty()))
                            Text(stringResource(R.string.pig_health, pig.triage.orEmpty(), pig.symptoms.joinToString { if (cn) it.nameCn else it.nameEn }, pig.diseases.joinToString { if (cn) it.nameCn else it.nameEn }))
                            detail.healthRecords.forEach { record -> Text("${record.occurredAt.orEmpty()} ${record.event} ${record.remark.orEmpty()}") }
                        }
                    }
                }
            } else {
                item {
                    state.locations?.let { SourceLabel(it) }
                    Box {
                        OutlinedButton(onClick = { unitMenu = true }, enabled = !state.loading && units.isNotEmpty()) {
                            Text(selected?.let { if (cn) it.nameCn else it.nameEn } ?: stringResource(R.string.choose_unit))
                        }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            units.forEach { unit -> DropdownMenuItem(text = { Text("${if (cn) unit.nameCn else unit.nameEn} (${unit.locationCode})") }, onClick = { unitMenu = false; model.applyFilter(unit.locationCode, null, search) }) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { penMenu = true }, enabled = !state.loading && selected != null) { Text(state.filter.penLocation ?: stringResource(R.string.all_pens)) }
                        DropdownMenu(expanded = penMenu, onDismissRequest = { penMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.all_pens)) }, onClick = { penMenu = false; model.applyFilter(state.filter.unitCode, null, search) })
                            pens.forEach { pen -> DropdownMenuItem(text = { Text(pen.locationCode!!) }, onClick = { penMenu = false; model.applyFilter(state.filter.unitCode, pen.locationCode, search) }) }
                        }
                    }
                    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(stringResource(R.string.pig_search)) }, modifier = Modifier.fillMaxWidth().testTag("pig-search"), singleLine = true)
                    Button(onClick = { model.applyFilter(state.filter.unitCode, state.filter.penLocation, search) }, enabled = !state.loading && selected != null) { Text(stringResource(R.string.search)) }
                    state.page?.let { result ->
                        SourceLabel(result)
                        Text(stringResource(R.string.pig_page_count, result.value.total, result.value.offset, result.value.pigs.size), Modifier.testTag("pig-page-count"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::previous, enabled = !state.loading && result.value.offset > 0) { Text(stringResource(R.string.previous_page)) }
                            OutlinedButton(onClick = model::next, enabled = !state.loading && result.value.hasNext) { Text(stringResource(R.string.next_page)) }
                        }
                        if (result.value.pigs.isEmpty()) Text(stringResource(R.string.no_pigs), Modifier.testTag("pig-empty"))
                    }
                    if (units.isEmpty() && !state.loading && state.failure == null) Text(stringResource(R.string.no_units))
                }
                items(state.page?.value?.pigs.orEmpty(), key = { it.id }) { pig ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(pig.label)
                            Text(pig.locationCode.orEmpty())
                            Text("${pig.role} / ${pig.lifecycle}")
                            Button(onClick = { model.loadDetail(pig.id) }, enabled = !state.loading, modifier = Modifier.testTag("pig-detail-${pig.id}")) { Text(stringResource(R.string.pig_detail)) }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SourceLabel(result: CachedResult<*>) {
    Text(stringResource(if (result.source == DataSource.CACHE) R.string.data_cached else R.string.data_online,
        DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, LocalLocale.current.platformLocale).format(Date(result.fetchedAt))))
    if (result.refreshFailure != null) Text(stringResource(R.string.cached_after_failure))
}
