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
        /**
         * 역할: 위젯 렌더링에 필요한 저장 설정과 시간표 및 급식 자료를 읽는다.
         * 입력: context: Context.
         * 출력: WidgetData 값.
         */
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
