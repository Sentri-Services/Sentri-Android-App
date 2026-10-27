package com.sentri.nativeapp.home

import androidx.annotation.StringRes
import com.sentri.nativeapp.R

/** Fixed section fixtures for the UI-only Workbench. */
internal data class HomeSection(
    val id: String,
    @param:StringRes val name: Int,
    val icon: String,
    val units: List<Int>,
    val population: Int,
)

internal object HomePreviewData {
    val sections = listOf(
        HomeSection("breeding", R.string.home_breeding, "heat", listOf(1, 2, 3, 4), 184),
        HomeSection("gestation", R.string.home_gestation, "pregnancy", listOf(7, 8, 9), 246),
        HomeSection("farrowing", R.string.home_farrowing, "farrow", listOf(6, 7, 8), 19),
        HomeSection("nursery", R.string.home_nursery, "barn", listOf(12, 13), 360),
    )
}
