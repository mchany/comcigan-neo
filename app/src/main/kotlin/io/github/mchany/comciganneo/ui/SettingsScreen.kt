@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mchany.comciganneo.BuildConfig
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.ThemeMode

@Composable
internal fun SettingsScreen(state: NeoState, viewModel: NeoViewModel) {
    val uriHandler = LocalUriHandler.current
    var clearDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    LazyColumn(Modifier.testTag("settings-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
            SettingsGroup("알림") { NotificationSettings(state, viewModel) }
        }
        item {
            SettingsGroup("위젯") { WidgetSettings(state, viewModel) }
        }
        item {
            SettingsGroup("데이터") {
                ListItem(headlineContent = { Text("저장된 시간표·급식 삭제") }, supportingContent = { Text("학교·학급과 화면 설정은 유지돼요") },
                    modifier = Modifier.clickable { clearDialog = true }, trailingContent = { Icon(Icons.Rounded.DeleteOutline, null) })
                Text("앱을 열면 저장된 시간표를 먼저 보여주고, 마지막 조회 후 5분이 지나면 갱신해요. 위젯도 저장된 자료를 사용하며 주기적으로 갱신합니다.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
