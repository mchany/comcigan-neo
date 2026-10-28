package io.github.mchany.comciganneo.widgets

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews

internal fun tintWidgetSurface(views: RemoteViews, container: Int, background: Int, color: Int, legacyBitmap: () -> Bitmap) {
    if (Build.VERSION.SDK_INT >= 31) {
        views.setColorStateList(container, "setBackgroundTintList", ColorStateList.valueOf(color))
        views.setViewVisibility(background, View.GONE)
    } else {
        views.setInt(container, "setBackgroundResource", 0)
        views.setViewVisibility(background, View.VISIBLE)
        views.setImageViewBitmap(background, legacyBitmap())
    }
}
