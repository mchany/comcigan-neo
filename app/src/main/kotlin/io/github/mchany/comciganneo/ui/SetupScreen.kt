@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel

@Composable
internal fun SetupScreen(state: NeoState, viewModel: NeoViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var grade by remember(state.candidate) { mutableIntStateOf(1) }
    var classNumber by remember(state.candidate, grade) { mutableIntStateOf(1) }
    val canClose = state.preferences.selection != null
    BackHandler(canClose, onBack = viewModel::closeSetup)
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (state.preferences.selection == null || state.preferences.initialSetupPending) "초기 설정 · 1/3" else "컴시간neo") }, navigationIcon = {
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
