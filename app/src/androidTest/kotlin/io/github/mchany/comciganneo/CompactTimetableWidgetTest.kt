package io.github.mchany.comciganneo

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.widgets.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class CompactTimetableWidgetTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    /**
     * 역할: 위젯 UI 테스트를 제어할 실행 객체를 가져온다.
     * 입력: 없음.
     * 출력: 현재 테스트의 Instrumentation.
     */
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val subjects = listOf("국어", "수학", "영어", "한국사", "통합과학", "체육", "자율")
    private val starts = listOf("08:10", "09:10", "10:10", "11:10", "13:00", "14:00", "15:10")

    /**
     * 역할: 승인된 시안과 같은 7교시 및 강조 상태의 위젯 검증 자료를 만든다.
     * 입력: 없음.
     * 출력: 교사 이름 표시 설정이 켜진 라벤더 테마의 WidgetData.
     */
    private fun fixture(): WidgetData {
        val now = ZonedDateTime.parse("2026-11-09T09:40:00+09:00[Asia/Seoul]")
        val selection = ClassSelection(School(424242, "위젯검증고등학교", "대전"), 2, 1)
        val snapshot = TimetableSnapshot(selection, weekStart(now.toLocalDate()).toString(),
            fetchedAt = now.withHour(7).withMinute(25).toInstant().toEpochMilli(), classCounts = listOf(1, 1),
            slots = subjects.mapIndexed { index, subject ->
                LessonSlot(now.toLocalDate().toString(), index + 1, starts[index], Lesson(subject, "김선생"))
            })
        return WidgetData(AppPreferences(selection = selection, themeMode = ThemeMode.LIGHT,
            dynamicColor = false, widgetPalette = WidgetPalette.LAVENDER, showTeachers = true), now, snapshot, null)
    }

    /**
     * 역할: 실제 Android 위젯 호스트에 지정 크기와 글꼴 배율의 시간표를 표시한다.
     * 입력: 가로·세로 dp, 위젯 자료, 글꼴 배율.
     * 출력: 앱 화면에 부착된 AppWidgetHostView.
     */
    private fun show(width: Int, height: Int, data: WidgetData, fontScale: Float = 1f): AppWidgetHostView {
        check(instrumentation.targetContext.packageName.endsWith(".smoke"))
        lateinit var host: AppWidgetHostView
        activity.scenario.onActivity { screen ->
            val config = Configuration(screen.resources.configuration).apply { this.fontScale = fontScale }
            val context = screen.createConfigurationContext(config)
            val density = context.resources.displayMetrics.density
            val info = AppWidgetManager.getInstance(context).installedProviders.first {
                it.provider == ComponentName(context, TimetableWidgetProvider::class.java)
            }
            host = AppWidgetHostView(context).apply { setAppWidget(0, info); setPadding(0, 0, 0, 0) }
            val stage = FrameLayout(screen).apply { setBackgroundColor(0xffdce4f7.toInt()) }
            stage.addView(host, FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt()))
            screen.setContentView(stage)
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            }
            host.updateAppWidget(WidgetRenderer.build(context, 0, WidgetKind.TIMETABLE, options, data))
        }
        return host
    }

    /**
     * 역할: 원격 위젯 목록의 비동기 적용이 끝날 때까지 기다린다.
     * 입력: 확인할 위젯 호스트와 기대하는 행 수.
     * 출력: 없음(Unit)이며 제한 시간 내에 목록이 준비되지 않으면 실패한다.
     */
    private fun awaitRows(host: AppWidgetHostView, count: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync {
                val list = host.findViewById<ListView>(R.id.widget_list)
                ready = list?.adapter?.count == count && list.childCount > 0
            }
            if (ready) return
            check(SystemClock.elapsedRealtime() < deadline) { "Compact timetable rows did not load" }
            SystemClock.sleep(100)
        }
    }

    /**
     * 역할: 위젯 안에서 현재 표시되는 텍스트를 수집한다.
     * 입력: 확인할 뷰.
     * 출력: 표시되는 텍스트 목록.
     */
    private fun texts(view: View): List<String> = if (!view.isShown) emptyList() else when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /**
     * 역할: 시안과 비교할 실제 위젯 렌더링을 PNG로 저장한다.
     * 입력: 위젯 호스트와 파일 이름.
     * 출력: 없음(Unit)이며 외부 앱 전용 폴더에 캡처를 저장한다.
     */
    private fun capture(host: AppWidgetHostView, name: String) {
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "widget-small").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    /**
     * 역할: 기본 2×2 크기에서 전체 수업·시작 시각·강조 행이 잘리지 않는지 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 모든 행과 텍스트가 완전히 보이지 않으면 실패한다.
     */
    @Test fun allSevenLessonsFitAndMatchApprovedDesign() {
        val host = show(170, 180, fixture())
        awaitRows(host, 7)
        capture(host, "compact-light-170x180")
        instrumentation.runOnMainSync {
            val list = host.findViewById<ListView>(R.id.widget_list)
            assertEquals(0, list.firstVisiblePosition)
            assertEquals(6, list.lastVisiblePosition)
            assertTrue("Last row bottom=${list.getChildAt(list.childCount - 1).bottom}, available=${list.height - list.paddingBottom}, row heights=${(0 until list.childCount).map { list.getChildAt(it).height }}",
                list.getChildAt(list.childCount - 1).bottom <= list.height - list.paddingBottom)
            for (index in subjects.indices) {
                val row = list.getChildAt(index)
                val body = row.findViewById<TextView>(R.id.row_body)
                assertEquals(subjects[index], body.text.toString())
                assertEquals(starts[index], row.findViewById<TextView>(R.id.row_meta).text.toString())
                assertEquals(0, body.layout.getEllipsisCount(0))
                assertEquals(if (index == 2) View.VISIBLE else View.GONE, row.findViewById<View>(R.id.row_background).visibility)
            }
            assertFalse(texts(host).any { it.contains("김선생") })
            assertNull(host.findViewById<View>(R.id.widget_focus_subject))
        }
        val dark = show(170, 180, fixture().let { it.copy(preferences = it.preferences.copy(themeMode = ThemeMode.DARK)) })
        awaitRows(dark, 7)
        capture(dark, "compact-dark-170x180")
    }

    /**
     * 역할: 최소 크기와 큰 글꼴에서도 스크롤로 마지막 수업에 접근할 수 있는지 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 수업이 누락되거나 빈 날짜 안내가 나오지 않으면 실패한다.
     */
    @Test fun resizingLargeFontsAndEmptyDaysRemainUsable() {
        for ((width, height, scale) in listOf(Triple(130, 136, 1f), Triple(170, 180, 1.6f))) {
            val host = show(width, height, fixture(), scale)
            awaitRows(host, 7)
            instrumentation.runOnMainSync { host.findViewById<ListView>(R.id.widget_list).setSelection(6) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(texts(host).contains("자율"))
                val list = host.findViewById<ListView>(R.id.widget_list)
                for (index in 0 until list.childCount) {
                    val meta = list.getChildAt(index).findViewById<TextView>(R.id.row_meta)
                    assertEquals("Start time remains complete at font scale $scale", meta.text.length, meta.layout.getLineEnd(0))
                    val body = list.getChildAt(index).findViewById<TextView>(R.id.row_body)
                    assertEquals("Subjects remain readable at font scale $scale", 0, body.layout.getEllipsisCount(0))
                }
                val refresh = host.findViewById<View>(R.id.widget_refresh)
                assertTrue("Refresh stays on the right when the timestamp is hidden", refresh.left > (refresh.parent as View).width / 2)
            }
            capture(host, "compact-${width}x$height-font$scale")
        }
        val empty = show(170, 180, fixture().let { it.copy(timetable = it.timetable!!.copy(slots = emptyList())) })
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(View.GONE, empty.findViewById<View>(R.id.widget_body).visibility)
            assertTrue(empty.findViewById<TextView>(R.id.widget_empty).text.contains("수업이 없어요"))
        }
        capture(empty, "compact-empty-170x180")
    }
}
