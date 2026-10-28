package io.github.mchany.comciganneo.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.ui.neoColorScheme

/** Both widget kinds use the same Material roles as the app. */
data class WidgetColors(val surface: Int, val cardSurface: Int, val ink: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val container: Int, val onContainer: Int, val outline: Int,
    val bitmapScale: Float) {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun resolve(context: Context, preferences: AppPreferences, kind: WidgetKind): WidgetColors {
            val scheme = neoColorScheme(context, preferences)
            val scale = minOf(1.5f, context.resources.displayMetrics.density * 0.6f)
            if (preferences.widgetPalette == WidgetPalette.APP) {
                return WidgetColors(mix(scheme.surface.toArgb(), scheme.primaryContainer.toArgb(), 0.12f), scheme.surface.toArgb(),
                    scheme.onSurface.toArgb(), scheme.onSurfaceVariant.toArgb(), scheme.primary.toArgb(), scheme.onPrimary.toArgb(),
                    scheme.primaryContainer.toArgb(), scheme.onPrimaryContainer.toArgb(), scheme.outlineVariant.toArgb(), scale)
            }
            val tones = when (preferences.widgetPalette) {
                WidgetPalette.LAVENDER -> intArrayOf(0xff65558f.toInt(), 0xffeaddff.toInt(), 0xff21005d.toInt())
                WidgetPalette.ROSE -> intArrayOf(0xff904a64.toInt(), 0xffffd8e4.toInt(), 0xff3b071b.toInt())
                WidgetPalette.MINT -> intArrayOf(0xff006b5e.toInt(), 0xff9ef2dd.toInt(), 0xff00201b.toInt())
                WidgetPalette.PEACH -> intArrayOf(0xff8c5000.toInt(), 0xffffdcb8.toInt(), 0xff2d1600.toInt())
                WidgetPalette.BLUE -> intArrayOf(0xff355e9c.toInt(), 0xffd6e3ff.toInt(), 0xff001b3e.toInt())
                else -> error("App palette resolved above")
            }
            val dark = ColorUtils.calculateLuminance(scheme.surface.toArgb()) < 0.5
            return WidgetColors(
                mix(if (dark) 0xff211f26.toInt() else 0xfff3edf7.toInt(), tones[0], 0.035f),
                mix(if (dark) 0xff2b2930.toInt() else 0xfffffbfe.toInt(), tones[0], 0.015f),
                if (dark) 0xffe6e0e9.toInt() else 0xff1d1b20.toInt(), if (dark) 0xffcac4d0.toInt() else 0xff49454f.toInt(),
                if (dark) mix(tones[0], Color.WHITE, 0.65f) else tones[0], if (dark) tones[2] else Color.WHITE,
                if (dark) mix(tones[0], Color.BLACK, 0.5f) else tones[1], if (dark) tones[1] else tones[2],
                if (dark) 0xff49454f.toInt() else 0xffcac4d0.toInt(), scale)
        }
        private fun mix(a: Int, b: Int, fraction: Float) = ColorUtils.blendARGB(a, b, fraction)
    }

    fun background(width: Int, height: Int, card: Boolean = false): Bitmap = shape(width, height,
        if (card) cardSurface else surface, if (card) 16f else 24f, border = !card)

    fun pill(width: Int, height: Int, filled: Boolean = false): Bitmap = shape(width, height,
        if (filled) accent else container, 50f)

    fun selection(width: Int, height: Int): Bitmap = shape(width, height, container, 12f)

    private fun shape(width: Int, height: Int, fill: Int, radius: Float, border: Boolean = false): Bitmap {
        val scale = minOf(bitmapScale, 600f / width.coerceAtLeast(1), 500f / height.coerceAtLeast(1))
        val w = (width.coerceAtLeast(24) * scale).toInt().coerceAtLeast(1)
        val h = (height.coerceAtLeast(20) * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val inset = if (border) 0.5f * scale else 0f
        val bounds = RectF(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(bounds, radius * scale, radius * scale, paint)
        if (border) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = scale
            paint.color = ColorUtils.setAlphaComponent(outline, 140)
            canvas.drawRoundRect(bounds, radius * scale, radius * scale, paint)
        }
        return bitmap
    }
}
