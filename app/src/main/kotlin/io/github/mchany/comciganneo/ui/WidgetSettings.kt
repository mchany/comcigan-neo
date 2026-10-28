package io.github.mchany.comciganneo.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mchany.comciganneo.NeoState
import io.github.mchany.comciganneo.NeoViewModel
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.widgets.*

@Composable
fun WidgetSettings(state: NeoState, viewModel: NeoViewModel) {
    val context = LocalContext.current
    val manager = AppWidgetManager.getInstance(context)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("위젯 색상", style = MaterialTheme.typography.titleSmall)
        for (palettes in WidgetPalette.entries.chunked(3)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                palettes.forEach { palette ->
                    val colors = WidgetColors.resolve(context, state.preferences.copy(widgetPalette = palette), WidgetKind.TIMETABLE)
                    FilterChip(selected = state.preferences.widgetPalette == palette, onClick = { viewModel.widgetPalette(palette) }, label = { Text(palette.label) },
                        leadingIcon = { Box(Modifier.size(12.dp).background(Color(colors.accent), CircleShape)) })
                }
            }
        }
        Text("앱과 동일은 배경화면 색상 설정을 따라요. 위젯의 밝기와 교사명 표시도 앱 설정을 따릅니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("홈 화면에 추가", style = MaterialTheme.typography.titleSmall)
        val choices = listOf("시간표 2×2" to TimetableWidgetProvider::class.java, "시간표 4×2" to WideTimetableWidgetProvider::class.java,
            "급식 2×2" to MealsWidgetProvider::class.java, "급식 4×2" to WideMealsWidgetProvider::class.java)
        choices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, provider) ->
                    OutlinedButton(onClick = {
                        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, provider), null, null)
                        else Toast.makeText(context, "홈 화면을 길게 눌러 위젯에서 컴시간neo를 선택해 주세요.", Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
        Text("홈 화면에서 크기를 조절할 수 있어요. 긴 시간표와 메뉴는 위젯 안에서 스크롤하고, 카드를 누르면 앱을 열어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
