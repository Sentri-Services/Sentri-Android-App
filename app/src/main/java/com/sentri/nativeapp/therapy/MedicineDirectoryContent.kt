package com.sentri.nativeapp.therapy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.pigs.DataSource
import java.util.Locale

/** A truthful read-only view of the existing flat registry, not the designed category/brand picker. */
@Composable
fun MedicineDirectoryScreen(state: MedicineDirectoryState, onRefresh: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(SentriTheme.colors.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.medicine_directory_title), fontFamily = SentriType.sans,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink)
        }
        MedicineDirectoryContent(state, onRefresh, Modifier.weight(1f))
    }
}

@Composable
fun MedicineDirectoryContent(state: MedicineDirectoryState, onRefresh: () -> Unit,
    modifier: Modifier = Modifier) {
    var query by remember(state.lease) { mutableStateOf("") }
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val medications = state.medications?.value.orEmpty()
    val formulations = state.catalogs?.value.orEmpty().groupBy { it.medicationId }
    val needle = query.trim().lowercase(Locale.ROOT)
    val visible = medications.filter { medicine ->
        needle.isEmpty() || medicine.chinese.lowercase(Locale.ROOT).contains(needle) ||
            medicine.english.lowercase(Locale.ROOT).contains(needle) ||
            formulations[medicine.id].orEmpty().any { entry ->
                entry.chinese.lowercase(Locale.ROOT).contains(needle) ||
                    entry.english.lowercase(Locale.ROOT).contains(needle)
            }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.medicine_directory_read_only), fontFamily = SentriType.sans,
            fontSize = 12.sp, color = SentriTheme.colors.muted) }
        item { SentriButton(stringResource(R.string.medicine_directory_refresh), onRefresh,
            Modifier.fillMaxWidth(), primary = false) }
        if (state.loading && state.medications == null)
            item { Text(stringResource(R.string.medicine_directory_loading)) }
        if (state.medicationsFailed)
            item { Text(stringResource(R.string.medicine_directory_failed), color = SentriTheme.colors.muted) }
        if (state.catalogsFailed)
            item { Text(stringResource(R.string.medicine_directory_formulations_failed), color = SentriTheme.colors.muted) }
        if (state.medications?.source == DataSource.CACHE || state.catalogs?.source == DataSource.CACHE)
            item { Text(stringResource(R.string.medicine_directory_offline), color = SentriTheme.colors.muted) }
        if (state.medications != null) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.medicine_directory_search)) }, singleLine = true) }
            item { SentriSectionHeading(stringResource(R.string.medicine_directory_title),
                meta = stringResource(R.string.medicine_directory_count, visible.size)) }
            if (visible.isEmpty()) item { Text(stringResource(if (needle.isEmpty())
                R.string.medicine_directory_empty else R.string.medicine_directory_no_match)) }
            else items(visible, key = { it.id }) { medicine ->
                val forms = formulations[medicine.id].orEmpty()
                val detail = if (forms.isEmpty()) null else forms.joinToString(" · ") { entry ->
                    val name = if (chinese) entry.chinese.ifBlank { entry.english }
                        else entry.english.ifBlank { entry.chinese }
                    val form = if (chinese) entry.formChinese else entry.formEnglish
                    if (form.isNullOrBlank()) name else "$name ($form)"
                }
                SentriPanel(Modifier.fillMaxWidth()) {
                    SentriRow(if (chinese) medicine.chinese.ifBlank { medicine.english }
                        else medicine.english.ifBlank { medicine.chinese }, detail, "health", null)
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}
