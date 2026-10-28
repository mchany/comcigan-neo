package io.github.mchany.comciganneo.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import io.github.mchany.comciganneo.MainActivity
import io.github.mchany.comciganneo.R
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetRenderer {
    private val lock = Mutex()
    suspend fun updateAll(context: Context) = lock.withLock {
        val widgets = NeoWidgets.instances(context)
        if (widgets.isEmpty()) return@withLock
        val data = WidgetData.load(context)
        val manager = AppWidgetManager.getInstance(context)
        widgets.forEach { (id, kind) -> manager.updateAppWidget(id, responsive(context, id, kind, manager.getAppWidgetOptions(id), data)) }
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            listOf(R.id.widget_list, R.id.meal_list_1, R.id.meal_list_2, R.id.meal_list_3).forEach { manager.notifyAppWidgetViewDataChanged(widgets.map { it.first }.toIntArray(), it) }
        }
        NeoWidgets.scheduleBoundary(context, data)
    }

    fun responsive(context: Context, id: Int, kind: WidgetKind, options: Bundle, data: WidgetData): RemoteViews {
        val defaultWidth = if (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className?.contains("Wide") == true) 340 else 170
        val portrait = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: defaultWidth.toFloat(),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: 180f)
        val landscape = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }?.toFloat() ?: portrait.width,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 }?.toFloat() ?: portrait.height)
        fun forSize(size: SizeF) = build(context, id, kind, Bundle(options).apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
        }, data)
        return if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
                .filter { it.width > 0 && it.height > 0 }.ifEmpty { listOf(portrait, landscape) }.distinct().take(4)
            RemoteViews(sizes.associateWith { forSize(it) })
        } else RemoteViews(forSize(landscape), forSize(portrait))
    }

    fun build(context: Context, id: Int, kind: WidgetKind, options: Bundle, data: WidgetData): RemoteViews {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170).coerceAtLeast(130)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceAtLeast(136)
        val wide = wideWidget(width, height, context.resources.configuration.fontScale)
        val layout = when (kind) {
            WidgetKind.TIMETABLE -> if (wide) R.layout.widget_timetable_wide_m3 else R.layout.widget_timetable_small_m3
            WidgetKind.MEALS -> if (wide) R.layout.widget_meals_wide_m3 else R.layout.widget_meals_small_m3
        }
        val colors = WidgetColors.resolve(context, data.preferences, kind)
        val views = RemoteViews(context.packageName, layout)
        views.setImageViewBitmap(R.id.widget_background, colors.background(width, height))
        views.setTextViewText(R.id.widget_date, data.date.format(DateTimeFormatter.ofPattern(if (wide && width < 340) "M.d (E)" else "M월 d일 (E)", Locale.KOREAN)))
        tintSurface(views, R.id.widget_today_surface, R.id.widget_today_background, colors.container, colors.pill(36, 24))
        views.setTextColor(R.id.widget_today, colors.onContainer)
        views.setTextViewText(R.id.widget_school, if (kind == WidgetKind.TIMETABLE) data.preferences.selection?.let { "${it.school.name} · ${it.label}" } ?: kind.label else data.mealSchool?.name ?: kind.label)
        views.setTextColor(R.id.widget_date, colors.accent)
        views.setTextColor(R.id.widget_school, colors.muted)
        ContextCompat.getDrawable(context, R.drawable.widget_m3_refresh)?.mutate()?.let { icon ->
            icon.setTint(colors.accent)
            val size = (24 * context.resources.displayMetrics.density).toInt()
            views.setImageViewBitmap(R.id.widget_refresh, icon.toBitmap(size, size))
        }
        views.setTextColor(R.id.widget_footer, colors.muted)
        views.setTextColor(R.id.widget_empty, colors.ink)
        val fontScale = context.resources.configuration.fontScale
        views.setViewVisibility(R.id.widget_school, if ((height < 230 && !(wide && kind == WidgetKind.TIMETABLE)) || (fontScale > 1.3f && height < 230)) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_footer, if (height < 160 || (fontScale > 1.3f && height < 250)) View.GONE else View.VISIBLE)
        val stamp = (if (kind == WidgetKind.TIMETABLE) data.timetable?.fetchedAt else data.meals?.fetchedAt)?.let {
            "조회 " + Instant.ofEpochMilli(it).atZone(SchoolZone).format(DateTimeFormatter.ofPattern("M.d HH:mm"))
        } ?: "새로고침 ↻ · 눌러서 앱 열기"
        views.setTextViewText(R.id.widget_footer, stamp)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, kind, false))
        val refresh = Intent(context, WidgetUpdateReceiver::class.java).setAction(NeoWidgets.ACTION_REFRESH)
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 500, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val empty = when {
            data.preferences.selection == null -> "앱을 열어 학교·학급을\n선택해 주세요."
            kind == WidgetKind.MEALS && data.mealSchool == null -> "설정에서 급식 학교를\n연결해 주세요."
            kind == WidgetKind.TIMETABLE && data.timetable == null -> "저장된 시간표가 없어요.\n새로고침하거나 앱에서 조회해 주세요."
            kind == WidgetKind.MEALS && data.meals == null -> "저장된 급식이 없어요.\n새로고침하거나 앱에서 조회해 주세요."
            kind == WidgetKind.TIMETABLE && data.lessons.isEmpty() -> "오늘 등록된 수업이 없어요."
            kind == WidgetKind.MEALS && data.entries.isEmpty() -> "오늘 등록된 급식이 없어요."
            else -> null
        }
        views.setViewVisibility(R.id.widget_empty, if (empty == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, empty.orEmpty())
        views.setViewVisibility(R.id.widget_body, if (empty == null) View.VISIBLE else View.GONE)
        if (kind == WidgetKind.TIMETABLE) views.setViewVisibility(R.id.widget_focus_panel, if (empty == null) View.VISIBLE else View.GONE)
        if (empty != null) return views
        if (kind == WidgetKind.TIMETABLE) {
            val focus = data.focus
            val slot = focus.lesson
            views.setTextViewText(R.id.widget_focus_label, "${slot?.period}교시 · ${focus.label}")
            views.setTextViewText(R.id.widget_focus_subject, slot?.current?.subject.orEmpty())
            views.setTextViewText(R.id.widget_focus_meta, listOfNotNull(slot?.startTime?.let { "$it 시작" }, slot?.current?.teacher?.takeIf { height >= 240 && data.preferences.showTeachers && it.isNotBlank() }).joinToString(" · "))
            views.setTextViewText(R.id.widget_following, focus.following?.let { "${it.period}교시  ${it.current?.subject.orEmpty()}" }.orEmpty())
            views.setTextViewText(R.id.widget_following_time, focus.following?.startTime?.let { "$it 시작" }.orEmpty())
            views.setViewVisibility(R.id.widget_following_section, if (focus.following == null || height < 210 || fontScale > 1.3f) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_following_label, if (height >= 250) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_following_time, if (height >= 230 && focus.following?.startTime != null) View.VISIBLE else View.GONE)
            views.setTextViewTextSize(R.id.widget_focus_subject, TypedValue.COMPLEX_UNIT_SP, if (height < 170) 20f else 24f)
            views.setTextColor(R.id.widget_focus_label, colors.accent)
            views.setTextColor(R.id.widget_focus_subject, colors.ink)
            views.setTextColor(R.id.widget_focus_meta, colors.muted)
            views.setTextColor(R.id.widget_following, colors.muted)
            views.setTextColor(R.id.widget_following_label, colors.muted)
            views.setTextColor(R.id.widget_following_time, colors.muted)
            views.setInt(R.id.widget_focus_divider, "setBackgroundColor", ColorUtils.setAlphaComponent(colors.outline, 160))
            views.setViewVisibility(R.id.widget_focus_label, if (fontScale > 1.5f && height < 210) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_focus_meta, if (fontScale > 1.5f && height < 170) View.GONE else View.VISIBLE)
            if (wide) bindRows(context, views, R.id.widget_list, id, kind, "all", data, colors)
        } else if (!wide) {
            bindRows(context, views, R.id.widget_list, id, kind, "all", data, colors)
        } else {
            val columns = listOf(R.id.meal_column_1, R.id.meal_column_2, R.id.meal_column_3)
            val backgrounds = listOf(R.id.meal_background_1, R.id.meal_background_2, R.id.meal_background_3)
            val titles = listOf(R.id.meal_title_1, R.id.meal_title_2, R.id.meal_title_3)
            val pills = listOf(R.id.meal_title_1_surface, R.id.meal_title_2_surface, R.id.meal_title_3_surface)
            val pillBackgrounds = listOf(R.id.meal_title_1_background, R.id.meal_title_2_background, R.id.meal_title_3_background)
            val captions = listOf(R.id.meal_caption_1, R.id.meal_caption_2, R.id.meal_caption_3)
            val lists = listOf(R.id.meal_list_1, R.id.meal_list_2, R.id.meal_list_3)
            // NEIS normally has three meal types. Unknown types remain accessible in the small list.
            if (data.entries.size > 3) return build(context, id, kind, Bundle(options).apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200) }, data)
            columns.indices.forEach { index ->
                val meal = data.entries.getOrNull(index)
                views.setViewVisibility(columns[index], if (meal == null) View.GONE else View.VISIBLE)
                if (meal != null) {
                    tintSurface(views, columns[index], backgrounds[index], colors.cardSurface, colors.background((width - 40) / data.entries.size, height - 95, true))
                    tintSurface(views, pills[index], pillBackgrounds[index], colors.accent, colors.pill(42, 24, true))
                    views.setTextViewText(titles[index], meal.typeName.ifBlank { meal.type.label })
                    views.setTextColor(titles[index], colors.onAccent)
                    views.setTextViewText(captions[index], meal.calories.orEmpty())
                    views.setTextColor(captions[index], colors.muted)
                    views.setViewVisibility(captions[index], if (meal.calories.isNullOrBlank()) View.GONE else View.VISIBLE)
                    bindRows(context, views, lists[index], id, kind, meal.typeCode, data, colors)
                }
            }
        }
        return views
    }

    private fun bindRows(context: Context, views: RemoteViews, listId: Int, widgetId: Int, kind: WidgetKind, section: String, data: WidgetData, colors: WidgetColors) {
        views.setPendingIntentTemplate(listId, openApp(context, kind, true))
        if (Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            widgetRows(data, kind, section).forEachIndexed { index, row -> items.addItem(index.toLong(), rowView(context, row, colors, kind, section)) }
            views.setRemoteAdapter(listId, items.build())
        } else {
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(listId, Intent(context, WidgetListService::class.java)
                .setData("comciganneo-widget://$widgetId/${kind.key}/$section".toUri())
                .putExtra("kind", kind.name).putExtra("section", section))
        }
    }

    fun rowView(context: Context, row: WidgetRow, colors: WidgetColors, kind: WidgetKind, section: String): RemoteViews {
        val layout = if (kind == WidgetKind.TIMETABLE) R.layout.widget_timetable_row_m3
            else if (section == "all") R.layout.widget_meal_card_m3 else R.layout.widget_menu_line_m3
        return RemoteViews(context.packageName, layout).apply {
            val textColor = if (row.highlighted) colors.onContainer else colors.ink
            val body = SpannableString(row.body)
            if (kind == WidgetKind.TIMETABLE) {
                setTextViewText(R.id.row_kicker, row.kicker)
                setTextColor(R.id.row_kicker, if (row.highlighted) colors.onContainer else colors.accent)
                setTextViewText(R.id.row_meta, row.meta.removeSuffix(" 시작"))
                setTextColor(R.id.row_meta, if (row.highlighted) colors.onContainer else colors.muted)
                if (row.secondary.isNotBlank() && row.body.endsWith(row.secondary)) body.setSpan(ForegroundColorSpan(
                    if (row.highlighted) colors.onContainer else colors.muted), row.body.length - row.secondary.length, row.body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setViewVisibility(R.id.row_background, if (row.highlighted) View.VISIBLE else View.GONE)
                if (row.highlighted) setImageViewBitmap(R.id.row_background, colors.selection(220, 24))
                setInt(R.id.row_divider, "setBackgroundColor", ColorUtils.setAlphaComponent(colors.outline, 100))
                setViewVisibility(R.id.row_divider, if (row.highlighted) View.INVISIBLE else View.VISIBLE)
            } else if (section == "all") {
                tintSurface(this, R.id.widget_row, R.id.row_background, colors.cardSurface, colors.background(200, 120, true))
                tintSurface(this, R.id.row_title_surface, R.id.row_title_background, colors.accent, colors.pill(42, 24, true))
                setTextViewText(R.id.row_title, row.title)
                setTextColor(R.id.row_title, colors.onAccent)
                setTextViewText(R.id.row_meta, row.meta)
                setViewVisibility(R.id.row_meta, if (row.meta.isBlank()) View.GONE else View.VISIBLE)
                setTextColor(R.id.row_meta, colors.muted)
            }
            setTextViewText(R.id.row_body, body)
            setTextColor(R.id.row_body, textColor)
            setOnClickFillInIntent(R.id.widget_row, Intent())
        }
    }

    private fun tintSurface(views: RemoteViews, container: Int, background: Int, color: Int, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(container, "setBackgroundTintList", ColorStateList.valueOf(color))
            views.setViewVisibility(background, View.GONE)
        } else {
            views.setInt(container, "setBackgroundResource", 0)
            views.setViewVisibility(background, View.VISIBLE)
            views.setImageViewBitmap(background, bitmap)
        }
    }

    fun openApp(context: Context, kind: WidgetKind, mutable: Boolean): PendingIntent = PendingIntent.getActivity(context, 410 + kind.ordinal + if (mutable) 20 else 0,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra("notification_kind", kind.key).putExtra("notification_event", "widget-${kind.key}-${System.currentTimeMillis()}")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
}
