package io.github.mchany.comciganneo

import io.github.mchany.comciganneo.model.*

data class MealState(
    val date: String = schoolToday().toString(),
    val snapshot: MealSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val connecting: Boolean = false,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val schools: List<NeisSchoolRef> = emptyList(),
    val connectionError: String? = null,
    val saving: Boolean = false,
)
