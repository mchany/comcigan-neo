package io.github.mchany.comciganneo.widgets

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import io.github.mchany.comciganneo.R
import io.github.mchany.comciganneo.model.WidgetKind

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

internal object WidgetCollection {
    fun bind(context: Context, views: RemoteViews, listId: Int, widgetId: Int, kind: WidgetKind, section: String, data: WidgetData, colors: WidgetColors) {
        views.setPendingIntentTemplate(listId, WidgetRenderer.openApp(context, kind, true))
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
                tintWidgetSurface(this, R.id.widget_row, R.id.row_background, colors.cardSurface) { colors.background(200, 120, true) }
                tintWidgetSurface(this, R.id.row_title_surface, R.id.row_title_background, colors.accent) { colors.pill(42, 24, true) }
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

}
