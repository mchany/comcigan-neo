package io.github.mchany.comciganneo.widgets

import android.content.Context
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

data class WidgetData(val preferences: AppPreferences, val now: ZonedDateTime,
    val timetable: TimetableSnapshot?, val meals: MealSnapshot?) {
    val date = now.toLocalDate()
    val lessons = widgetLessons(timetable, date)
    val entries = widgetMeals(meals, date)
    val focus = widgetFocus(lessons, now.toLocalTime())
    val mealSchool get() = preferences.mealSchool
    companion object {
        suspend fun load(context: Context): WidgetData {
            val repository = (context.applicationContext as NeoApplication).repository
            val preferences = repository.preferences.first()
            val now = ZonedDateTime.now(SchoolZone)
            val selection = preferences.selection
            val school = preferences.mealSchool
            return WidgetData(preferences, now, selection?.let { repository.cached(it, 0) }, school?.let { repository.cachedMeals(it, now.toLocalDate()) })
        }
    }
}
