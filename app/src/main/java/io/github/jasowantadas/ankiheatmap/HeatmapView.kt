package io.github.jasowantadas.ankiheatmap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Full-year heatmap for the app screen. Tap a square to see that day. */
class HeatmapView(context: Context) : View(context) {
    var stats: Stats? = null
        set(value) {
            field = value
            invalidate()
        }
    var goal: Int = 100
        set(value) {
            field = value
            invalidate()
        }
    var selected: Long = -1
    var onDayTap: ((Long) -> Unit)? = null

    private val dm = resources.displayMetrics
    private val cell = 13 * dm.density
    private val gap = 3 * dm.density
    private val left = 22 * dm.density
    private val top = 18 * dm.density
    private val weeks = 53

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dm.density
        color = 0xFF378ADD.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, dm)
        color = 0xFF888780.toInt()
    }
    private val rect = RectF()

    private fun today(): Long = stats?.today() ?: AnkiDay.epochDay(System.currentTimeMillis(), 4)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (left + weeks * (cell + gap) + 8 * dm.density).toInt()
        val h = (top + 7 * (cell + gap)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val today = today()
        val start = Levels.gridStart(today, weeks)
        val step = cell + gap

        var lastMonth = -1
        for (w in 0 until weeks) {
            val first = LocalDate.ofEpochDay(start + w * 7L)
            if (first.monthValue != lastMonth && (w > 0 || first.dayOfMonth <= 7) && w < weeks - 1) {
                canvas.drawText(
                    first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    left + w * step, top - 6 * dm.density, text,
                )
            }
            lastMonth = first.monthValue
        }
        val names = arrayOf("", "M", "", "W", "", "F", "")
        for (d in 0 until 7) {
            if (names[d].isNotEmpty()) canvas.drawText(names[d], 0f, top + d * step + cell * 0.85f, text)
        }

        val radius = 2.5f * dm.density
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val day = start + w * 7L + d
                if (day > today) continue
                val x = left + w * step
                val y = top + d * step
                rect.set(x, y, x + cell, y + cell)
                fill.color = Levels.COLORS[Levels.level(stats?.count(day) ?: 0, goal)]
                canvas.drawRoundRect(rect, radius, radius, fill)
                if (day == selected) canvas.drawRoundRect(rect, radius, radius, outline)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val step = cell + gap
            val col = ((event.x - left) / step).toInt()
            val row = ((event.y - top) / step).toInt()
            if (event.x >= left && event.y >= top && col in 0 until weeks && row in 0..6) {
                val day = Levels.gridStart(today(), weeks) + col * 7L + row
                if (day <= today()) {
                    selected = day
                    invalidate()
                    onDayTap?.invoke(day)
                }
            }
        }
        return true
    }
}
