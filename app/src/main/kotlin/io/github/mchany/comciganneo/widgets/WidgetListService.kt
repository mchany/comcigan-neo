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
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(intent)
    private inner class Factory(intent: Intent) : RemoteViewsFactory {
        private val kind = WidgetKind.entries.firstOrNull { it.name == intent.getStringExtra("kind") } ?: WidgetKind.TIMETABLE
        private val section = intent.getStringExtra("section") ?: "all"
        private var rows = emptyList<WidgetRow>()
        private var colors: WidgetColors? = null
        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun onDataSetChanged() = runBlocking(Dispatchers.IO) {
            val data = WidgetData.load(applicationContext)
            rows = widgetRows(data, kind, section)
            colors = WidgetColors.resolve(applicationContext, data.preferences, kind)
        }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.let { row -> colors?.let { WidgetRenderer.rowView(applicationContext, row, it, kind, section) } }
        override fun getLoadingView() = RemoteViews(packageName, R.layout.widget_loading_m3)
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
