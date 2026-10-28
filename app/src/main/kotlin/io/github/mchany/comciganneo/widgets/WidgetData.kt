package io.github.mchany.comciganneo.widgets

import android.content.Context
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

data class WidgetData(val preferences: AppPreferences, val now: ZonedDateTime,
    val timetable: TimetableSnapshot?, val meals: MealSnapshot?) {
    val date get() = now.toLocalDate()
    val lessons get() = widgetLessons(timetable, date)
    val entries get() = widgetMeals(meals, date)
    val focus get() = widgetFocus(lessons, now.toLocalTime())
    val mealSchool get() = preferences.mealSchools[preferences.selection?.school?.code.toString()]
    companion object {
        suspend fun load(context: Context): WidgetData {
            val repository = (context.applicationContext as NeoApplication).repository
            val preferences = repository.preferences.first()
            val now = ZonedDateTime.now(SchoolZone)
            val selection = preferences.selection
            val school = preferences.mealSchools[selection?.school?.code.toString()]
            return WidgetData(preferences, now, selection?.let { repository.cached(it, 0) }, school?.let { repository.cachedMeals(it, now.toLocalDate()) })
        }
    }
}

data class WidgetRow(val kicker: String = "", val title: String = "", val body: String = "", val meta: String = "", val highlighted: Boolean = false,
    val secondary: String = "")

fun widgetRows(data: WidgetData, kind: WidgetKind, section: String = "all"): List<WidgetRow> = when (kind) {
    WidgetKind.TIMETABLE -> data.lessons.map { slot -> WidgetRow(kicker = slot.period.toString(),
        body = listOfNotNull(slot.current?.subject, slot.current?.teacher?.takeIf { data.preferences.showTeachers && it.isNotBlank() }).joinToString(" "),
        meta = slot.startTime?.let { "$it 시작" }.orEmpty(), highlighted = slot.period == data.focus.lesson?.period,
        secondary = slot.current?.teacher?.takeIf { data.preferences.showTeachers }.orEmpty()) }
    WidgetKind.MEALS -> if (section == "all") data.entries.map { meal -> WidgetRow(title = meal.typeName.ifBlank { meal.type.label }, body = meal.menu.filter { it.isNotBlank() }.joinToString("\n"), meta = meal.calories.orEmpty()) }
        else data.entries.firstOrNull { it.typeCode == section }?.menu.orEmpty().filter { it.isNotBlank() }.map { WidgetRow(body = it) }
}
