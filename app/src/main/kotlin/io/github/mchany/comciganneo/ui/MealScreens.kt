@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import androidx.compose.foundation.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MealDateLabel = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val MealSyncLabel = DateTimeFormatter.ofPattern("M.d HH:mm", Locale.KOREAN)

@Composable
fun MealScreen(state: NeoState, viewModel: NeoViewModel) {
    val date = LocalDate.parse(state.meal.date)
    var type by rememberSaveable { mutableStateOf(MealType.LUNCH) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("오늘은 무슨 메뉴?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (state.mealSchool == null) item {
            Text("설정 탭의 급식 > 연결 관리에서 급식 학교를 선택해 주세요.")
        }
        if (!state.preferences.mealKeyConfigured) item {
            Text("급식 조회 설정이 없는 빌드예요. 최신 앱을 설치해 주세요.")
        }
        if (state.mealSchool != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { viewModel.mealDate(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전 날짜") }
                    TextButton(onClick = { showDate = true }) { Text(date.format(MealDateLabel)) }
                    IconButton(onClick = { viewModel.mealDate(date.plusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음 날짜") }
                }
                AlignedMealAction(onClick = { viewModel.mealDate(schoolToday()) }) { Text("오늘로 이동") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER).forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.label) })
                    }
                }
            }
            if (state.meal.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.meal.error?.let { message -> item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message)
                        if (state.meal.snapshot != null) Text("저장된 급식 자료를 보여드려요.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::refreshMeals, enabled = !state.meal.loading) { Text("급식 다시 시도") }
                    }
                }
            } }
            val meals = state.meal.snapshot?.forDate(date)
            val selected = meals?.filter { it.type == type || it.type == MealType.UNKNOWN }.orEmpty()
            if (meals != null && selected.isEmpty()) item {
                Text("${date.format(MealDateLabel)}에 등록된 ${type.label} 정보가 없어요.", Modifier.padding(vertical = 24.dp))
            }
            items(selected, key = { it.typeCode }) { meal -> MealDetails(meal) }
            state.meal.snapshot?.let { snapshot -> item { MealFetchedAt(snapshot) } }
        }
        item {
            AlignedMealAction(onClick = { uri.openUri("https://open.neis.go.kr") }) { Text("자료 출처 · 나이스 교육정보 개방 포털") }
            Text("학교가 등록한 자료를 표시하며 실제 식단과 다를 수 있어요. 메뉴의 숫자는 알레르기 유발 식재료 번호예요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { viewModel.mealDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                showDate = false
            }) { Text("선택") }
        }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("취소") } }) { DatePicker(picker) }
    }
}

@Composable
private fun MealDetails(meal: MealEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(meal.typeName, style = MaterialTheme.typography.titleLarge)
            meal.calories?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text(meal.menu.joinToString("\n"), style = MaterialTheme.typography.bodyLarge)
            MealExpandedText("알레르기 번호 안내", MealAllergens.mapIndexed { index, label -> "${index + 1}. $label" }.joinToString(" · "))
            if (meal.nutrition.isNotBlank()) MealExpandedText("영양정보", meal.nutrition)
            if (meal.origin.isNotBlank()) MealExpandedText("원산지", meal.origin)
        }
    }
}

@Composable
private fun MealExpandedText(title: String, text: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        AlignedMealAction(onClick = { expanded = !expanded }) {
            Text(title)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "접기" else "펼치기")
        }
        if (expanded) Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlignedMealAction(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun MealFetchedAt(snapshot: MealSnapshot) {
    Text("마지막 급식 조회 ${Instant.ofEpochMilli(snapshot.fetchedAt).atZone(SchoolZone).format(MealSyncLabel)}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun MealSettings(state: NeoState, viewModel: NeoViewModel) {
    ListItem(headlineContent = { Text("급식 학교") }, supportingContent = { Text(state.mealSchool?.name ?: "아직 연결하지 않았어요") },
        trailingContent = { TextButton(onClick = viewModel::openMealConnection) { Text("연결 관리") } })
    state.meal.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    var confirmClear by remember { mutableStateOf(false) }
    TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("저장된 급식만 삭제") }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("저장된 급식을 삭제할까요?") },
        text = { Text("학교 연결은 유지돼요. 다음 급식 조회에는 인터넷 연결이 필요해요.") },
        confirmButton = { TextButton(onClick = { viewModel.clearMealCache(); confirmClear = false }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
}

@Composable
fun MealConnectionDialog(state: NeoState, viewModel: NeoViewModel) {
    ModalBottomSheet(onDismissRequest = viewModel::closeMealConnection) {
        MealSchoolPicker(state, viewModel)
    }
}

@Composable
fun InitialMealSetupScreen(state: NeoState, viewModel: NeoViewModel) {
    LaunchedEffect(state.preferences.selection?.school?.code) {
        if (state.preferences.mealKeyConfigured) {
            viewModel.searchMealSchools(state.preferences.selection?.school?.name.orEmpty())
        }
    }
    BackHandler { if (!state.meal.saving) viewModel.openSetup() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("초기 설정 · 2/3") }, navigationIcon = {
            IconButton(onClick = viewModel::openSetup, enabled = !state.meal.saving) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "학교·학급 다시 선택")
            }
        })
    }) { padding ->
        MealSchoolPicker(state, viewModel, Modifier.padding(padding).consumeWindowInsets(padding), initialSetup = true)
    }
}

@Composable
private fun MealSchoolPicker(state: NeoState, viewModel: NeoViewModel, modifier: Modifier = Modifier, initialSetup: Boolean = false) {
    var query by rememberSaveable(state.preferences.selection?.school?.code) { mutableStateOf(state.preferences.selection?.school?.name.orEmpty()) }
    var confirm by remember { mutableStateOf<NeisSchoolRef?>(null) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (initialSetup) "급식 학교 선택" else "급식 학교 연결", style = MaterialTheme.typography.headlineSmall)
            Text("시간표 학교와 같은 학교인지 주소까지 확인해 주세요.")
            if (initialSetup) {
                Text("학교를 연결하면 오늘의 급식도 바로 확인할 수 있어요. 나중에 설정 탭에서 변경할 수 있어요.")
                TextButton(onClick = viewModel::skipInitialMealSetup, enabled = !state.meal.saving) { Text("나중에 설정") }
            }
        }
        if (!state.preferences.mealKeyConfigured) item { Text("급식 조회 설정이 없는 빌드예요. 최신 앱을 설치해 주세요.") }
        item {
            OutlinedTextField(query, { query = it; confirm = null }, label = { Text("급식 학교 이름") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchMealSchools(query) }))
            Button(onClick = { confirm = null; viewModel.searchMealSchools(query) },
                enabled = !state.meal.searching && !state.meal.saving && state.preferences.mealKeyConfigured && query.trim().length >= 2) { Text("급식 학교 검색") }
        }
        if (state.meal.searching || state.meal.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.meal.connectionError?.let { item { Text(it) } }
        if (state.meal.searched && state.meal.schools.isEmpty()) item { Text("검색된 학교가 없어요. 이름을 줄여 검색해 보세요.") }
        items(state.meal.schools, key = { "${it.officeCode}-${it.schoolCode}" }) { school ->
            OutlinedCard(onClick = { if (!state.meal.saving) confirm = school }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(school.name, style = MaterialTheme.typography.titleMedium)
                    Text(school.officeName)
                    Text(school.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!initialSetup && state.mealSchool != null) item {
            TextButton(onClick = { viewModel.connectMealSchool(null) }, enabled = !state.meal.saving) { Text("급식 학교 연결 해제") }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    confirm?.let { school ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("이 학교의 급식을 볼까요?") },
            text = { Text("${school.name}\n${school.officeName}\n${school.address}") },
            confirmButton = { TextButton(onClick = { viewModel.connectMealSchool(school); confirm = null }, enabled = !state.meal.saving) { Text("이 학교 연결") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } })
    }
}
