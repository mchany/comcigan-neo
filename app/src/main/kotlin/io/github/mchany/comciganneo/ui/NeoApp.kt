@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.mchany.comciganneo.BuildConfig
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Korean = Locale.KOREAN
private val DateLabel = DateTimeFormatter.ofPattern("M월 d일 EEEE", Korean)

@Composable
fun NeoApp(viewModel: NeoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(NeoTab.TODAY) }
    var detail by remember { mutableStateOf<LessonSlot?>(null) }
    var now by remember { mutableStateOf(ZonedDateTime.now(SchoolZone)) }
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

@Composable
private fun SetupScreen(state: NeoState, viewModel: NeoViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var grade by remember(state.candidate) { mutableIntStateOf(1) }
    var classNumber by remember(state.candidate, grade) { mutableIntStateOf(1) }
    val canClose = state.preferences.selection != null
    BackHandler(canClose, onBack = viewModel::closeSetup)
    Scaffold(topBar = {
        TopAppBar(title = { Text("컴시간neo") }, navigationIcon = {
            if (canClose) IconButton(onClick = viewModel::closeSetup) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "돌아가기") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.CalendarMonth, null, Modifier.padding(20.dp).size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            item {
                Text("우리 반의 하루,\n한눈에.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("학교와 학급을 선택하면\n오늘 수업과 바뀐 시간표를 알려드려요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("학교 이름") },
                    placeholder = { Text("예: 중일고등학교") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp), leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.search(query) }, enabled = query.trim().length >= 2 && !state.searching,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("학교 검색") }
            }
            if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.setupError?.let { error -> item { MessageCard(error) } }
            if (state.candidate != null && state.classCounts.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(state.candidate.name, style = MaterialTheme.typography.titleLarge)
                            Text(state.candidate.region, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberPicker("학년", grade, state.classCounts.indices.map { it + 1 }.filter { state.classCounts[it - 1] > 0 }, Modifier.weight(1f)) { grade = it }
                                NumberPicker("반", classNumber, (1..(state.classCounts.getOrNull(grade - 1) ?: 0)).toList(), Modifier.weight(1f)) { classNumber = it }
                            }
                            Button(onClick = { viewModel.select(grade, classNumber) }, modifier = Modifier.fillMaxWidth()) { Text("이 학급으로 시작") }
                        }
                    }
                }
            } else {
                if (state.searched && state.searchResults.isEmpty() && !state.searching) item {
                    Text("검색된 학교가 없어요. 학교 이름을 줄여서 검색해 보세요.")
                }
                items(state.searchResults, key = { it.code }) { school ->
                    OutlinedCard(onClick = { viewModel.candidate(school) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        ListItem(headlineContent = { Text(school.name) }, supportingContent = { Text(school.region) },
                            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
                    }
                }
            }
            item { Text("컴시간을 사용하는 학교만 검색할 수 있어요.\n공식 서비스와 독립된 오픈소스 클라이언트입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun NumberPicker(label: String, selected: Int, values: List<Int>, modifier: Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$selected$label"); Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text("$value$label") }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}

@Composable
private fun SettingsScreen(state: NeoState, viewModel: NeoViewModel) {
    val uriHandler = LocalUriHandler.current
    var clearDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("내게 맞게", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            SettingsGroup("학교·학급") {
                ListItem(headlineContent = { Text(state.preferences.selection?.school?.name.orEmpty()) }, supportingContent = { Text(state.preferences.selection?.label.orEmpty()) },
                    trailingContent = { TextButton(onClick = viewModel::openSetup) { Text("변경") } })
            }
        }
        item {
            SettingsGroup("화면") {
                Text("테마", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "시스템", ThemeMode.LIGHT to "밝게", ThemeMode.DARK to "어둡게").forEach { (mode, label) ->
                        FilterChip(selected = state.preferences.themeMode == mode, onClick = { viewModel.theme(mode) }, label = { Text(label) })
                    }
                }
                ListItem(headlineContent = { Text("배경화면 색상 사용") }, supportingContent = { Text(if (Build.VERSION.SDK_INT >= 31) "시스템 색상을 앱에 적용해요" else "Android 12 이상에서 지원해요") },
                    trailingContent = { Switch(checked = state.preferences.dynamicColor, onCheckedChange = viewModel::dynamic, enabled = Build.VERSION.SDK_INT >= 31,
                        modifier = Modifier.semantics { contentDescription = "배경화면 색상 사용" }) })
                ListItem(headlineContent = { Text("교사명 표시") }, trailingContent = { Switch(state.preferences.showTeachers, viewModel::teachers,
                    modifier = Modifier.semantics { contentDescription = "교사명 표시" }) })
            }
        }
        item {
            SettingsGroup("급식") { MealSettings(state, viewModel) }
        }
        item {
            SettingsGroup("데이터") {
                ListItem(headlineContent = { Text("저장된 시간표·급식 삭제") }, supportingContent = { Text("학교·학급과 화면 설정은 유지돼요") },
                    modifier = Modifier.clickable { clearDialog = true }, trailingContent = { Icon(Icons.Rounded.DeleteOutline, null) })
                Text("앱을 열면 저장된 시간표를 먼저 보여주고, 마지막 조회 후 5분이 지나면 갱신해요. 알림과 위젯은 후속 버전에서 지원할 예정입니다.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsGroup("컴시간neo") {
                ListItem(headlineContent = { Text("소스 코드") }, supportingContent = { Text("mchany/comcigan-neo") },
                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/mchany/comcigan-neo") }, trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) })
                ListItem(headlineContent = { Text("오픈소스 고지") }, modifier = Modifier.clickable { licenses = true })
                Text("${BuildConfig.VERSION_NAME}\n컴시간 및 운영 주체와 공식 관계가 없는 비공식 클라이언트입니다.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { item { MessageCard(it) } }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("저장된 시간표와 급식을 삭제할까요?") },
        text = { Text("다음 조회에는 인터넷 연결이 필요해요.") }, confirmButton = { TextButton(onClick = { viewModel.clearCache(); clearDialog = false }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("취소") } })
    if (licenses) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val notice = remember { context.assets.open("open_source_licenses.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("오픈소스 고지") },
            text = { Text(notice, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("닫기") } })
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedCard(shape = RoundedCornerShape(24.dp)) { Column(content = content) }
    }
}

@Composable
private fun MessageCard(message: String, cached: Boolean = false, retry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cached) Text("저장된 시간표를 보여드려요", style = MaterialTheme.typography.labelLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (retry != null) TextButton(onClick = retry) { Text("다시 시도") }
        }
    }
}
