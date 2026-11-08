package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/** One translated text block, in the coordinates of the bitmap it will be drawn on. */
internal class RenderItem(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    /** Dark caption box: black background, white text. */
    val dark: Boolean,
)

/** Draws translated text over the original text so zoom, pan and webtoon scrolling keep working. */
internal object PageRenderer {

    fun render(bitmap: Bitmap, items: List<RenderItem>, rtl: Boolean) {
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        for (item in items) {
            val w = item.right - item.left
            val h = item.bottom - item.top
            if (w < 8f || h < 8f) continue
            rect.set(item.left, item.top, item.right, item.bottom)
            bg.color = if (item.dark) Color.BLACK else Color.WHITE
            val radius = min(w, h) * 0.12f
            canvas.drawRoundRect(rect, radius, radius, bg)
            drawText(canvas, item, rtl)
        }
    }

    private fun drawText(canvas: Canvas, item: RenderItem, rtl: Boolean) {
        val pad = max(2f, min(item.right - item.left, item.bottom - item.top) * 0.04f)
        val availW = (item.right - item.left - pad * 2).toInt()
        val availH = (item.bottom - item.top - pad * 2).toInt()
        if (availW < 4 || availH < 4) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (item.dark) Color.WHITE else Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = fitLayout(item.text, paint, availW, availH, rtl)
        canvas.save()
        canvas.translate(item.left + pad, item.top + pad + (availH - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(text: String, paint: TextPaint, width: Int, rtl: Boolean): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun fits(layout: StaticLayout, width: Int, height: Int): Boolean {
        if (layout.height > height) return false
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > width + 0.5f) return false
        }
        return true
    }

    /** Largest font size whose wrapped text fits the box (binary search). */
    private fun fitLayout(text: String, paint: TextPaint, width: Int, height: Int, rtl: Boolean): StaticLayout {
        var lo = 6
        var hi = max(lo, min(height, 90))
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            paint.textSize = mid.toFloat()
            if (fits(layoutFor(text, paint, width, rtl), width, height)) lo = mid else hi = mid - 1
        }
        paint.textSize = lo.toFloat()
        return layoutFor(text, paint, width, rtl)
    }
}
