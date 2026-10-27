@file:OptIn(ExperimentalMaterial3Api::class)
package io.github.mchany.comciganneo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DayLabel = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val ShortDay = DateTimeFormatter.ofPattern("M.d", Locale.KOREAN)

@Composable
internal fun TodayScreen(state: NeoState, now: ZonedDateTime, refresh: () -> Unit, onLesson: (LessonSlot) -> Unit) {
    val today = now.toLocalDate()
    val lessons = state.snapshot?.slots?.filter { it.date == today.toString() }?.sortedBy { it.period }.orEmpty()
    val next = lessons.firstOrNull { slot -> slot.current != null &&
        slot.startTime?.let { runCatching { LocalTime.parse(it) >= now.toLocalTime() }.getOrDefault(false) } == true }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactSummary = maxHeight < 320.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(today.format(DayLabel), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val changes = lessons.count { it.changed }
                if (changes > 0) Text("변경 ${changes}개", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            val guidance = when {
                state.snapshot == null -> if (state.loading) "시간표를 가져오고 있어요" else "시간표를 새로고침해 주세요"
                lessons.none { it.current != null } -> "오늘은 등록된 수업이 없어요"
                lessons.none { it.current != null && it.startTime != null } -> "아래 시간표에서 수업을 확인하세요"
                else -> "모든 시작 시각을 지났어요"
            }
            NextLessonCard(next, guidance, compactSummary)
            state.error?.let { TimetableError(it, refresh) }
            if (lessons.any { it.current != null }) {
                // A bounded viewport lets the last period stay above navigation, without scrolling.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("today-viewport")) {
                    val gap = if (maxHeight < 300.dp) 3.dp else 5.dp
                    val rowHeight = ((maxHeight - gap * (lessons.size - 1)) / lessons.size).coerceIn(0.dp, 56.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        lessons.forEach { LessonRow(it, state.preferences.showTeachers, rowHeight, onLesson) }
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when {
                    state.loading -> "시간표를 가져오고 있어요."
                    state.snapshot == null -> "새로고침해서 오늘 수업을 가져와 주세요."
                    else -> "오늘은 등록된 수업이 없어요."
                }, style = MaterialTheme.typography.bodyMedium)
            }
            state.snapshot?.let { CompactSyncLabel(it) }
        }
    }
}

@Composable
private fun NextLessonCard(next: LessonSlot?, guidance: String, compact: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("next-lesson-card")) {
        if (compact) Text(
            if (next != null) "다음 ${next.period}교시 · ${next.current?.subject} · ${next.startTime} 시작" else guidance,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (next != null) "다음 수업 · ${next.period}교시" else "오늘의 수업",
                style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(next?.current?.subject ?: guidance, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                next?.startTime?.let { Text("$it 시작", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun LessonRow(slot: LessonSlot, showTeacher: Boolean, rowHeight: Dp, onClick: (LessonSlot) -> Unit) {
    val scale = LocalDensity.current.fontScale
    val size = minOf(16f, ((rowHeight.value - 6f) / (1.25f * scale))).coerceAtLeast(6f)
    val description = listOfNotNull("${slot.period}교시", slot.startTime, slot.current?.subject ?: "수업 없음",
        slot.current?.teacher?.takeIf { showTeacher && it.isNotBlank() }, "변경됨".takeIf { slot.changed }).joinToString(" · ")
    Surface(modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("today-period-${slot.period}")
        .clickable(role = Role.Button) { onClick(slot) }.semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp), color = if (slot.changed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${slot.period}", Modifier.width(18.dp), color = MaterialTheme.colorScheme.primary,
                fontSize = size.sp, lineHeight = (size * 1.2f).sp, maxLines = 1, textAlign = TextAlign.Center)
            if (rowHeight >= 32.dp) Text(slot.startTime ?: "교시", Modifier.width(40.dp),
                fontSize = (size - 3).coerceAtLeast(8f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(slot.current?.subject ?: "수업 없음") }
                if (showTeacher) slot.current?.teacher?.takeIf { it.isNotBlank() }?.let { teacher ->
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(teacher) }
                }
            }, Modifier.weight(1f), fontSize = size.sp, lineHeight = (size * 1.2f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (slot.changed) Text("•", color = MaterialTheme.colorScheme.tertiary, fontSize = size.sp)
        }
    }
}

@Composable
internal fun WeekScreen(state: NeoState, onWeek: (Int) -> Unit, refresh: () -> Unit, onLesson: (LessonSlot) -> Unit) {
    var listView by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "이번 주", 1 to "다음 주").forEach { (week, label) ->
                FilterChip(selected = state.week == week, onClick = { onWeek(week) }, label = { Text(label) })
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(checked = listView, onCheckedChange = { listView = it }) {
                Icon(if (listView) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, if (listView) "표로 보기" else "목록으로 보기")
            }
        }
        state.error?.let { TimetableError(it, refresh) }
        val snapshot = state.snapshot
        if (snapshot != null) {
            if (listView) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    snapshot.slots.groupBy { it.date }.toSortedMap().forEach { (date, slots) ->
                        item { Text(LocalDate.parse(date).format(DayLabel), style = MaterialTheme.typography.titleSmall) }
                        items(slots, key = { "${it.date}-${it.period}" }) { LessonRow(it, state.preferences.showTeachers, 48.dp, onLesson) }
                    }
                }
            } else WeekGrid(snapshot, state.preferences.showTeachers, Modifier.weight(1f), onLesson)
            CompactSyncLabel(snapshot)
        } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (state.loading) "시간표를 가져오고 있어요." else "등록된 시간표가 없어요.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WeekGrid(snapshot: TimetableSnapshot, showTeachers: Boolean, modifier: Modifier, onLesson: (LessonSlot) -> Unit) {
    val start = LocalDate.parse(snapshot.weekStart)
    val days = (0..4).map { start.plusDays(it.toLong()) }
    val index = snapshot.slots.associateBy { it.date to it.period }
    val periods = snapshot.slots.maxOfOrNull { it.period } ?: 0
    val fontScale = LocalDensity.current.fontScale
    Column(modifier.fillMaxWidth().testTag("week-table-${snapshot.weekStart}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.width(22.dp))
            days.forEach { date ->
                Text("${date.format(DateTimeFormatter.ofPattern("E", Locale.KOREAN))} ${date.format(ShortDay)}",
                    Modifier.weight(1f).padding(vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (date == schoolToday()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (periods > 0) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("week-viewport")) {
            val gap = if (maxHeight < 300.dp) 3.dp else 5.dp
            val rowHeight = ((maxHeight - gap * (periods - 1)) / periods).coerceIn(0.dp, 76.dp)
            val twoLines = rowHeight >= 32.dp * fontScale
            val size = minOf(14f, ((rowHeight.value - 4f) / (if (twoLines) 2.6f else 1.3f) / fontScale)).coerceAtLeast(6f)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (period in 1..periods) Row(Modifier.fillMaxWidth().height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$period", Modifier.width(22.dp), fontSize = size.sp, maxLines = 1, textAlign = TextAlign.Center)
                    days.forEach { date ->
                        val slot = index[date.toString() to period]
                        val subject = slot?.current?.subject ?: "—"
                        val teacher = slot?.current?.teacher?.takeIf { showTeachers && it.isNotBlank() }
                        val description = listOfNotNull(date.format(DayLabel), "${period}교시", subject, teacher,
                            "변경됨".takeIf { slot?.changed == true }).joinToString(" · ")
                        Surface(modifier = Modifier.weight(1f).fillMaxHeight().testTag("week-cell-$date-$period")
                            .clickable(enabled = slot != null, role = Role.Button) { slot?.let(onLesson) }
                            .semantics { contentDescription = description },
                            color = if (slot?.changed == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp)) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 3.dp)) {
                                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (!twoLines && teacher != null) "$subject $teacher" else subject,
                                        fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (twoLines && teacher != null) Text(teacher,
                                        fontSize = (size - 2).coerceAtLeast(8f).sp, lineHeight = (size * 1.2f).sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (slot?.changed == true) Text("•", Modifier.align(Alignment.TopEnd),
                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableError(message: String, retry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f).clickable { expanded = true }, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error)
        IconButton(onClick = retry) { Icon(Icons.Rounded.Refresh, "다시 시도") }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("조회 안내") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("닫기") } })
}

@Composable
private fun CompactSyncLabel(snapshot: TimetableSnapshot) {
    val time = Instant.ofEpochMilli(snapshot.fetchedAt).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("M.d HH:mm"))
    Text("마지막 조회 $time", style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.testTag("timetable-sync"), maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
