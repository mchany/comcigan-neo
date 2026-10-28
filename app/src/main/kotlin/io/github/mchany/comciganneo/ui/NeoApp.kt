@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Korean = Locale.KOREAN
private val DateLabel = DateTimeFormatter.ofPattern("M월 d일 EEEE", Korean)

@Composable
fun NeoApp(viewModel: NeoViewModel, notificationTarget: String? = null, notificationEvent: Int = 0) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(NeoTab.TODAY) }
    var detail by remember { mutableStateOf<LessonSlot?>(null) }
    var now by remember { mutableStateOf(ZonedDateTime.now(SchoolZone)) }
    LaunchedEffect(notificationEvent) {
        when (notificationTarget) {
            "timetable" -> { tab = NeoTab.TODAY; viewModel.week(0) }
            "meals" -> { tab = NeoTab.MEALS; viewModel.mealDate(schoolToday()) }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = ZonedDateTime.now(SchoolZone)
                viewModel.onForeground()
                delay(60_000)
            }
        }
    }
    NeoTheme(state.preferences) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.preferences.selection == null || state.setup -> SetupScreen(state, viewModel)
                state.preferences.initialSetupPending -> InitialMealSetupScreen(state, viewModel)
                state.preferences.notificationSetupPending -> InitialNotificationSetupScreen(state, viewModel)
                else -> {
                    val selection = state.preferences.selection!!
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val rail = maxWidth > maxHeight && maxHeight < 500.dp
                        val compact = rail || LocalDensity.current.fontScale > 1.2f
                        val selectTab: (NeoTab) -> Unit = { next ->
                            if (next == NeoTab.TODAY) viewModel.week(0)
                            tab = next
                        }
                        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                            if (rail) NeoNavigation(tab, true, selectTab)
                            Scaffold(
                                modifier = Modifier.weight(1f),
                                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
                                topBar = {
                                    NeoAppBar(selection, compact, viewModel::openSetup,
                                        refresh = if (tab == NeoTab.SETTINGS) null else {
                                            { if (tab == NeoTab.MEALS) viewModel.refreshMeals() else viewModel.refresh() }
                                        },
                                        refreshing = if (tab == NeoTab.MEALS) state.meal.loading else state.loading,
                                        meals = tab == NeoTab.MEALS)
                                },
                                bottomBar = { if (!rail) NeoNavigation(tab, false, selectTab) },
                            ) { padding ->
                                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                                    if (state.loading && tab != NeoTab.MEALS && tab != NeoTab.SETTINGS) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    when (tab) {
                                        NeoTab.TODAY -> TodayScreen(state, now, viewModel::refresh) { detail = it }
                                        NeoTab.WEEK -> WeekScreen(state, viewModel::week, viewModel::refresh) { detail = it }
                                        NeoTab.MEALS -> MealScreen(state, viewModel)
                                        NeoTab.SETTINGS -> SettingsScreen(state, viewModel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.meal.connecting) MealConnectionDialog(state, viewModel)
            detail?.let { slot ->
                ModalBottomSheet(onDismissRequest = { detail = null }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${LocalDate.parse(slot.date).format(DateLabel)} · ${slot.period}교시", style = MaterialTheme.typography.titleMedium)
                        Text(slot.current?.subject ?: "수업 없음", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (state.preferences.showTeachers) slot.current?.teacher?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        slot.startTime?.let { Text("$it 시작", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (slot.changed) {
                            HorizontalDivider()
                            Text("변경 전 → 현재", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("${slot.original?.subject ?: "수업 없음"} → ${slot.current?.subject ?: "수업 없음"}")
                            if (state.preferences.showTeachers && slot.original?.teacher != slot.current?.teacher) {
                                Text("${slot.original?.teacher.orEmpty()} → ${slot.current?.teacher.orEmpty()}")
                            }
                        }
                    }
                }
            }
        }
    }
}
