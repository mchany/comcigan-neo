package io.github.mchany.comciganneo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mchany.comciganneo.model.ClassSelection

internal enum class NeoTab { TODAY, WEEK, MEALS, SETTINGS }

@Composable
/**
 * 역할: 학교 정보와 설정 진입 버튼을 표시한다.
 * 입력: selection: ClassSelection, compact: Boolean, openSetup: () -> Unit, refresh: (() -> Unit)?, refreshing: Boolean, meals: Boolean.
 * 출력: 없음(Unit).
 */
internal fun NeoAppBar(selection: ClassSelection, compact: Boolean, openSetup: () -> Unit,
    refresh: (() -> Unit)?, refreshing: Boolean, meals: Boolean) {
    Surface {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = openSetup).padding(horizontal = 4.dp)) {
                Text(if (compact) "${selection.school.name} · ${selection.label}" else selection.school.name,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) Text("${selection.label}  ▾", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            if (refresh != null) IconButton(onClick = refresh, enabled = !refreshing) {
                Icon(Icons.Rounded.Refresh, if (meals) "급식 새로고침" else "시간표 새로고침")
            }
        }
    }
}

@Composable
/**
 * 역할: 앱의 화면 탭 이동 UI를 표시한다.
 * 입력: tab: NeoTab, rail: Boolean, select: (NeoTab) -> Unit.
 * 출력: 없음(Unit).
 */
internal fun NeoNavigation(tab: NeoTab, rail: Boolean, select: (NeoTab) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val barHeight = (28f + 16f * fontScale).coerceAtLeast(56f).dp
    val items = listOf(Triple(NeoTab.TODAY, "오늘", Icons.Rounded.Today),
        Triple(NeoTab.WEEK, "주간", Icons.Rounded.CalendarViewWeek),
        Triple(NeoTab.MEALS, "급식", Icons.Rounded.Restaurant), Triple(NeoTab.SETTINGS, "설정", Icons.Rounded.Settings))
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        /**
         * 역할: 화면 이동에 필요한 탭과 선택 항목 정보를 전달한다.
         * 입력: item: Triple<NeoTab, String, androidx.compose.ui.graphics.vector.ImageVector>, modifier: Modifier.
         * 출력: 없음(Unit).
         */
        @Composable fun Destination(item: Triple<NeoTab, String, androidx.compose.ui.graphics.vector.ImageVector>, modifier: Modifier) {
            val selected = tab == item.first
            Column(modifier.selectable(selected = selected, role = Role.Tab, onClick = { select(item.first) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (!rail || fontScale <= 1.4f) Surface(shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Icon(item.third, null, Modifier.padding(horizontal = 15.dp, vertical = 3.dp).size(22.dp))
                }
                Text(item.second, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rail) Column(Modifier.width(72.dp).fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
            items.forEach { Destination(it, Modifier.weight(1f).fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).height(barHeight)) {
            items.forEach { Destination(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}
