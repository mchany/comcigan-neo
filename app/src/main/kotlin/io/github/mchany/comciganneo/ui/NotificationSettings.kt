@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.notifications.*

@Composable
fun NotificationSettings(state: NeoState, viewModel: NeoViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as NeoApplication
    var accessVersion by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun refreshAccess() { accessVersion++; app.refreshNotificationSchedules() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshAccess() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }
    val allowed = remember(accessVersion) { DailyNotificationPublisher.allowed(context) }
    val exact = remember(accessVersion) { DailyNotificationScheduler(context).canScheduleExactly() }
    var editing by remember { mutableStateOf<NotificationKind?>(null) }
    Column {
        NotificationKind.entries.forEach { kind ->
            val settings = state.preferences.notification(kind)
            ListItem(headlineContent = { Text(kind.label) }, supportingContent = {
                Text(if (kind == NotificationKind.TIMETABLE) "오늘의 수업을 펼쳐서 확인해요" else "등록된 조식·중식·석식을 펼쳐서 확인해요")
            }, trailingContent = {
                Switch(checked = settings.enabled, onCheckedChange = { enabled ->
                    viewModel.notificationEnabled(kind, enabled)
                    if (enabled && Build.VERSION.SDK_INT >= 33 && !DailyNotificationPublisher.allowed(context)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else refreshAccess()
                }, modifier = Modifier.semantics { contentDescription = kind.label })
            })
            TextButton(onClick = { editing = kind }, modifier = Modifier.padding(horizontal = 8.dp).testTag("notification-time-${kind.key}")) {
                Text("알림 시각 · ${settings.timeLabel}")
            }
            if (settings.enabled && kind == NotificationKind.MEALS && state.mealSchool == null) {
                Text("급식 학교를 연결하면 알림을 받을 수 있어요.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            val channelAllowed = remember(accessVersion, kind) { DailyNotificationPublisher.allowed(context, kind) }
            if (settings.enabled && allowed && !channelAllowed) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, DailyNotificationPublisher.channel(kind))) }) {
                    Text("${kind.label} 차단 해제")
                }
            }
        }
        val anyEnabled = state.preferences.timetableNotification.enabled || state.preferences.mealNotification.enabled
        if (anyEnabled && !allowed) {
            Text("알림 권한이 꺼져 있어요. 시스템 설정에서 허용해 주세요.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("시스템 알림 설정") }
        }
        if (anyEnabled && !exact && Build.VERSION.SDK_INT >= 31) {
            Text("정확한 시각에 받으려면 알람 및 리마인더를 허용해 주세요. 허용하지 않으면 알림이 늦어질 수 있어요.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }) { Text("정확한 시각 허용") }
        }
        Text("매일, 한국 시간 기준으로 알려드려요. 조회와 기기의 배터리 설정에 따라 늦어질 수 있어요.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { kind ->
        val settings = state.preferences.notification(kind)
        val picker = rememberTimePickerState(settings.minutes / 60, settings.minutes % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("${kind.label} 시각") },
            text = { TimeInput(state = picker) },
            confirmButton = { TextButton(onClick = { viewModel.notificationTime(kind, picker.hour * 60 + picker.minute); editing = null }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } })
    }
}

@Composable
fun InitialNotificationSetupScreen(state: NeoState, viewModel: NeoViewModel) {
    BackHandler { if (!state.notificationSetupSaving) viewModel.backToMealSetup() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("초기 설정 · 3/3") }, navigationIcon = {
            IconButton(onClick = viewModel::backToMealSetup, enabled = !state.notificationSetupSaving) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "급식 학교 다시 선택")
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("하루를 미리 알려드릴게요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("시간표와 급식 알림을 각각 켜고 받을 시각을 정해 주세요. 설정 탭에서 언제든 바꿀 수 있어요.")
            OutlinedCard { NotificationSettings(state, viewModel) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::finishNotificationSetup, enabled = !state.notificationSetupSaving, modifier = Modifier.fillMaxWidth()) { Text("설정 완료") }
        }
    }
}
