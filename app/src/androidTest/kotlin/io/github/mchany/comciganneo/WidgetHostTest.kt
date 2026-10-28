package io.github.mchany.comciganneo

import android.appwidget.*
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.data.*
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.widgets.*
import io.github.mchany.comciganneo.ui.neoColorScheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

suspend fun seedWidgetData(context: Context): WidgetData {
    check(context.packageName.endsWith(".smoke"))
    val date = schoolToday()
    val now = System.currentTimeMillis()
    val selection = ClassSelection(School(424242, "위젯검증고등학교", "대전"), 2, 1)
    val school = NeisSchoolRef("G10", "fixture", "급식위젯검증학교", "검증교육청", "검증 주소")
    val timetable = TimetableSnapshot(selection, weekStart(date).toString(), fetchedAt = now, classCounts = listOf(1, 1), slots = (1..7).map {
        LessonSlot(date.toString(), it, "%02d:10".format(it + 7), Lesson(listOf("국어", "영어", "수학", "정보", "과학", "미술", "체육")[it - 1], "김선생"))
    })
    val meals = MealSnapshot(school, weekStart(date).toString(), now, listOf(
        MealEntry(date.toString(), "1", "조식", "", emptyList()),
        MealEntry(date.toString(), "3", "석식", "", listOf("저녁검증밥", "국", "반찬")),
        MealEntry(date.toString(), "2", "중식", "", listOf("점심검증밥", "미역국", "반찬 (5.6)") + (1..12).map { "추가메뉴$it" }),
        MealEntry(date.plusDays(1).toString(), "1", "조식", "", listOf("내일아침"))))
    val db = Room.databaseBuilder(context, TimetableDatabase::class.java, "timetables.db").addMigrations(Migration1To2).build()
    try {
        db.snapshots().save(SnapshotEntity(selection.school.code, 2, 1, timetable.weekStart, Json.encodeToString(timetable)))
        db.meals().save(MealSnapshotEntity(school.officeCode, school.schoolCode, meals.weekStart, Json.encodeToString(meals)))
    } finally { db.close() }
    val repository = (context.applicationContext as NeoApplication).repository
    repository.select(selection); repository.setMealSchool(selection.school.code, school); repository.finishInitialSetup()
    repository.teachers(true); repository.widgetPalette(WidgetPalette.LAVENDER); repository.theme(ThemeMode.LIGHT)
    return WidgetData(repository.preferences.first(), ZonedDateTime.now(SchoolZone), timetable, meals)
}

@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!check()) { if (SystemClock.elapsedRealtime() > deadline) error("Widget update timed out"); SystemClock.sleep(100) }
    }
    private fun texts(view: View): List<String> = if (!view.isShown) emptyList() else when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun options(width: Int, height: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
    }
    @Test fun realHostCollectionsResizeColorsAndUnlink() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        val data = seedWidgetData(context)
        val repository = (context.applicationContext as NeoApplication).repository
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 7001)
        val ids = mutableListOf<Int>()
        val views = mutableListOf<AppWidgetHostView>()
        try {
            val sizes = listOf(175 to 215, 175 to 215, 360 to 210, 360 to 220)
            val types = listOf(TimetableWidgetProvider::class.java, MealsWidgetProvider::class.java,
                WideTimetableWidgetProvider::class.java, WideMealsWidgetProvider::class.java)
            types.forEachIndexed { index, type ->
                val id = host.allocateAppWidgetId(); ids += id
                assertTrue("Grant widget bind to .smoke before running", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, type)))
                manager.updateAppWidgetOptions(id, options(sizes[index].first, sizes[index].second))
                main { views += host.createView(context, id, manager.getAppWidgetInfo(id)) }
            }
            main { host.startListening() }
            activity.scenario.onActivity { screen ->
                val density = screen.resources.displayMetrics.density
                fun px(dp: Int) = (dp * density).toInt()
                val root = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(32), px(12), px(16)); setBackgroundColor(0xffdce4f7.toInt()) }
                root.addView(TextView(screen).apply { text = "시간표 · 급식 위젯"; textSize = 18f; setTextColor(0xff263451.toInt()) }, LinearLayout.LayoutParams(-1, px(36)))
                val small = LinearLayout(screen)
                small.addView(views[0], LinearLayout.LayoutParams(px(175), px(215)))
                small.addView(views[1], LinearLayout.LayoutParams(px(175), px(215)).apply { marginStart = px(8) })
                root.addView(small)
                root.addView(views[2], LinearLayout.LayoutParams(px(360), px(210)).apply { topMargin = px(12) })
                root.addView(views[3], LinearLayout.LayoutParams(px(360), px(220)).apply { topMargin = px(12) })
                screen.setContentView(ScrollView(screen).apply { addView(root) })
            }
            WidgetRenderer.updateAll(context)
            eventually {
                var ready = false
                main { ready = texts(views[3]).any { it.contains("점심검증밥") } && texts(views[3]).any { it.contains("저녁검증밥") } }
                ready
            }
            main {
                val timetableList = views[2].findViewById<ListView>(R.id.widget_list)
                assertEquals(0, timetableList.firstVisiblePosition)
                assertEquals("All seven periods fit the wide widget", 6, timetableList.lastVisiblePosition)
                assertEquals(View.GONE, views[3].findViewById<View>(R.id.meal_column_3).visibility)
                assertEquals(15, views[3].findViewById<ListView>(R.id.meal_list_1).adapter.count)
                assertEquals(3, views[3].findViewById<ListView>(R.id.meal_list_2).adapter.count)
                assertFalse(texts(views[3]).any { it.contains("조식") || it.contains("내일아침") })
                val list = views[3].findViewById<ListView>(R.id.meal_list_1)
                list.setSelection(14)
            }
            eventually { var found = false; main { found = texts(views[3]).any { it.contains("추가메뉴12") } }; found }
            main { views[3].findViewById<ListView>(R.id.meal_list_1).setSelection(0) }
            val demo = data.copy(now = data.date.atTime(9, 40).atZone(SchoolZone))
            main {
                views.indices.forEach { index -> views[index].updateAppWidget(WidgetRenderer.responsive(context, ids[index],
                    if (index == 0 || index == 2) WidgetKind.TIMETABLE else WidgetKind.MEALS, manager.getAppWidgetOptions(ids[index]), demo)) }
            }
            eventually { var found = false; main { found = texts(views[0]).any { it.contains("4교시") } && texts(views[3]).any { it.contains("점심검증밥") } }; found }
            main {
                val following = views[0].findViewById<TextView>(R.id.widget_following)
                val rect = Rect()
                assertTrue("Following lesson remains fully visible", following.getGlobalVisibleRect(rect) && rect.height() >= following.height)
            }
            val directory = File(context.getExternalFilesDir(null), "debug-screenshots").apply { mkdirs() }
            SystemClock.sleep(300)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, "widgets-showcase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()

            manager.updateAppWidgetOptions(ids[0], options(360, 190))
            main { views[0].layoutParams = views[0].layoutParams.apply { width = (360 * context.resources.displayMetrics.density).toInt(); height = (190 * context.resources.displayMetrics.density).toInt() } }
            WidgetRenderer.updateAll(context)
            eventually { var ready = false; main { ready = views[0].findViewById<ListView>(R.id.widget_list)?.adapter?.count == 7 }; ready }
            repository.widgetPalette(WidgetPalette.ROSE); repository.theme(ThemeMode.DARK)
            WidgetRenderer.updateAll(context)
            val expected = WidgetColors.resolve(context, repository.preferences.first())
            eventually { var matches = false; main { matches = views[2].findViewById<TextView>(R.id.widget_date).currentTextColor == expected.accent }; matches }
            for (palette in WidgetPalette.entries) for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val colors = WidgetColors.resolve(context, data.preferences.copy(widgetPalette = palette, themeMode = theme))
                assertTrue("Readable palette $palette $theme", ColorUtils.calculateContrast(colors.ink, colors.surface) >= 4.5)
                assertTrue("Readable card $palette $theme", ColorUtils.calculateContrast(colors.ink, colors.cardSurface) >= 4.5)
                assertTrue("Readable meal chip $palette $theme", ColorUtils.calculateContrast(colors.onAccent, colors.accent) >= 4.5)
                assertTrue("Readable selected row $palette $theme", ColorUtils.calculateContrast(colors.onContainer, colors.container) >= 4.5)
                val flat = colors.background(300, 200)
                assertEquals("Flat surface replaces decorative waves", flat.getPixel(flat.width / 2, flat.height / 2), flat.getPixel(flat.width / 2, flat.height * 4 / 5))
                flat.recycle()
                if (palette == WidgetPalette.APP) assertEquals(neoColorScheme(context, data.preferences.copy(widgetPalette = palette, themeMode = theme)).primary.toArgb(), colors.accent)
            }
            // Extra NEIS meal types switch to a scrolling list without changing host size.
            val extraMeals = data.copy(meals = data.meals!!.copy(meals = data.meals.meals + listOf(
                MealEntry(data.date.toString(), "4", "특별식", "", listOf("특별검증밥")),
                MealEntry(data.date.toString(), "5", "추가식", "", listOf("추가검증밥")),
            )))
            main { views[3].updateAppWidget(WidgetRenderer.responsive(context, ids[3], WidgetKind.MEALS,
                manager.getAppWidgetOptions(ids[3]), extraMeals)) }
            eventually {
                var ready = false
                main { ready = views[3].findViewById<ListView>(R.id.widget_list)?.adapter?.count == 4 }
                ready
            }
            main { views[3].findViewById<ListView>(R.id.widget_list).setSelection(3) }
            eventually { var found = false; main { found = texts(views[3]).any { it.contains("추가검증밥") } }; found }

            repository.setMealSchool(data.preferences.selection!!.school.code, null)
            WidgetRenderer.updateAll(context)
            eventually { var ready = false; main { ready = texts(views[3]).any { it.contains("급식 학교를") } && texts(views[3]).none { it.contains("검증밥") } }; ready }
        } finally {
            main { host.stopListening() }
            ids.forEach { host.deleteAppWidgetId(it) }
            host.deleteHost()
            NeoWidgets.synchronize(context)
        }
    }
}
