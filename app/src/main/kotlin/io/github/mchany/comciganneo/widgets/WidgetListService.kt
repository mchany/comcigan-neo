package io.github.mchany.comciganneo.widgets

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.mchany.comciganneo.R
import io.github.mchany.comciganneo.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Collection adapter for Android 8–11; Android 12+ uses RemoteCollectionItems directly. */
class WidgetListService : RemoteViewsService() {
    /**
     * 역할: 위젯 목록을 만드는 뷰 팩토리를 반환한다.
     * 입력: intent: Intent.
     * 출력: RemoteViewsFactory 값.
     */
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(intent)
    private inner class Factory(intent: Intent) : RemoteViewsFactory {
        private val kind = WidgetKind.entries.firstOrNull { it.name == intent.getStringExtra("kind") } ?: WidgetKind.TIMETABLE
        private val section = intent.getStringExtra("section") ?: "all"
        private var rows = emptyList<WidgetRow>()
        private var colors: WidgetColors? = null
        /**
         * 역할: 위젯 목록 팩토리 초기화 시 별도 작업을 하지 않는다.
         * 입력: 없음.
         * 출력: 없음(Unit).
         */
        override fun onCreate() = Unit
        /**
         * 역할: 위젯 목록 팩토리 종료 시 별도 작업을 하지 않는다.
         * 입력: 없음.
         * 출력: 없음(Unit).
         */
        override fun onDestroy() = Unit
        /**
         * 역할: 최신 위젯 자료를 읽고 목록 행을 다시 구성한다.
         * 입력: 없음.
         * 출력: 없음(Unit).
         */
        override fun onDataSetChanged() = runBlocking(Dispatchers.IO) {
            val data = WidgetData.load(applicationContext)
            rows = widgetRows(data, kind, section)
            colors = WidgetColors.resolve(applicationContext, data.preferences)
        }
        /**
         * 역할: 현재 위젯 목록에 표시할 행 개수를 반환한다.
         * 입력: 없음.
         * 출력: Int 값.
         */
        override fun getCount() = rows.size
        /**
         * 역할: 목록 위치에 대응하는 행 데이터를 RemoteViews로 변환한다.
         * 입력: position: Int.
         * 출력: RemoteViews? 값.
         */
        override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.let { row -> colors?.let { WidgetCollection.rowView(applicationContext, row, it, kind, section) } }
        /**
         * 역할: 목록 자료를 불러오는 동안 사용할 로딩 화면을 반환한다.
         * 입력: 없음.
         * 출력: RemoteViews 값.
         */
        override fun getLoadingView() = RemoteViews(packageName, R.layout.widget_loading_m3)
        /**
         * 역할: 위젯 목록에서 사용하는 뷰 유형 수를 반환한다.
         * 입력: 없음.
         * 출력: Int 값.
         */
        override fun getViewTypeCount() = 1
        /**
         * 역할: 목록 위치를 항목의 안정적인 ID로 반환한다.
         * 입력: position: Int.
         * 출력: Long 값.
         */
        override fun getItemId(position: Int) = position.toLong()
        /**
         * 역할: 항목 ID가 안정적으로 유지되는지 반환한다.
         * 입력: 없음.
         * 출력: Boolean 값.
         */
        override fun hasStableIds() = true
    }
}
