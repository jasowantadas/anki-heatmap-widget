package io.github.jasowantadas.ankiheatmap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object WidgetUpdater {
    const val ACTION_REFRESH = "io.github.jasowantadas.ankiheatmap.REFRESH"
    const val ACTION_SELECT = "io.github.jasowantadas.ankiheatmap.SELECT"
    const val EXTRA_DAY = "day"
    private const val ANKIDROID = "com.ichi2.anki"
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun refreshAll(context: Context, force: Boolean) {
        if (force) {
            val prefs = Prefs(context)
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, HeatmapWidget::class.java))) prefs.setSelectedDay(id, -1L)
        }
        val stats = AnkiRepository.load(context, force)
        redrawAll(context, stats)
    }

    fun redrawAll(context: Context, stats: Stats?) {
        val s = stats ?: AnkiRepository.cached(context)
        val mgr = AppWidgetManager.getInstance(context)
        for (id in mgr.getAppWidgetIds(ComponentName(context, HeatmapWidget::class.java))) {
            try {
                mgr.updateAppWidget(id, buildHeatmap(context, mgr, id, s))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        for (id in mgr.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))) {
            try {
                mgr.updateAppWidget(id, buildToday(context, s))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), FLAGS)

    private fun openAnkiIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(ANKIDROID)
            ?: Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, 2, launch, FLAGS)
    }

    private fun refreshIntent(context: Context, cls: Class<*>): PendingIntent {
        val i = Intent(context, cls).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(context, 3, i, FLAGS)
    }

    private fun selectIntent(context: Context, widgetId: Int, day: Long): PendingIntent {
        val i = Intent(context, HeatmapWidget::class.java)
            .setAction(ACTION_SELECT)
            .setData(Uri.parse("ankiheatmap://select/$widgetId/$day"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(EXTRA_DAY, day)
        return PendingIntent.getBroadcast(context, 4, i, FLAGS)
    }

    /** Tapping a square shows its count; tapping it again goes back to today. */
    fun select(context: Context, widgetId: Int, day: Long) {
        if (widgetId < 0) return
        val prefs = Prefs(context)
        prefs.setSelectedDay(widgetId, if (prefs.selectedDay(widgetId) == day) -1L else day)
        val mgr = AppWidgetManager.getInstance(context)
        mgr.updateAppWidget(widgetId, buildHeatmap(context, mgr, widgetId, AnkiRepository.cached(context)))
    }

    private fun streakText(s: Stats?): String {
        val n = s?.currentStreak() ?: 0
        return "🔥 $n day" + if (n == 1) "" else "s"
    }

    /** Widget size in dp for the current orientation. */
    private fun widgetSizeDp(context: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return Pair(if (w > 0) w else 300, if (h > 0) h else 180)
    }

    private fun statsLine(s: Stats): CharSequence {
        val green = 0xFF97C459.toInt()
        val b = SpannableStringBuilder()
        fun part(label: String, value: String, last: Boolean = false) {
            b.append(label)
            val start = b.length
            b.append(value)
            b.setSpan(ForegroundColorSpan(green), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!last) b.append("    ")
        }
        part("Daily average: ", "${s.dailyAverage()} cards")
        part("Days learned: ", "${s.daysLearnedPercent()}%")
        part("Longest streak: ", "${s.bestStreak()} days")
        part("Current streak: ", "${s.currentStreak()} days", last = true)
        return b
    }

    fun buildHeatmap(context: Context, mgr: AppWidgetManager, id: Int, s: Stats?): RemoteViews {
        val pkg = context.packageName
        val prefs = Prefs(context)
        val goal = prefs.goal
        val rv = RemoteViews(pkg, R.layout.widget_heatmap)

        // Fit a full year (53 weeks) of square cells into the space the widget has.
        val (wDp, hDp) = widgetSizeDp(context, mgr, id)
        val availW = wDp - 24 - 16 - 4
        val availH = hDp - 24 - 26 - 14 - 20 - 18 - 6
        var weeks = 53
        var step = minOf(availW / weeks, availH / 7)
        if (step < GridSizes.MIN) {
            step = GridSizes.MIN
            weeks = (availW / step).coerceIn(4, 53)
        }
        step = step.coerceIn(GridSizes.MIN, GridSizes.MAX)
        val si = step - GridSizes.MIN

        val today = s?.today() ?: AnkiDay.epochDay(System.currentTimeMillis(), 4)
        val start = Levels.gridStart(today, weeks)
        val selected = prefs.selectedDay(id).takeIf { it in start..today } ?: -1L

        rv.removeAllViews(R.id.days)
        rv.addView(R.id.days, RemoteViews(pkg, GridSizes.DAYS[si]))

        rv.removeAllViews(R.id.months)
        var w = 0
        while (w < weeks) {
            val first = LocalDate.ofEpochDay(start + w * 7L)
            val prev = if (w == 0) null else LocalDate.ofEpochDay(start + (w - 1) * 7L)
            val newMonth = if (prev == null) first.dayOfMonth <= 7 else prev.month != first.month
            if (newMonth && w <= weeks - 3) {
                val label = RemoteViews(pkg, GridSizes.LABEL[si])
                label.setTextViewText(
                    R.id.label,
                    first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                )
                rv.addView(R.id.months, label)
                w += 3
            } else {
                rv.addView(R.id.months, RemoteViews(pkg, GridSizes.SPACE[si]))
                w += 1
            }
        }

        rv.removeAllViews(R.id.grid)
        for (week in 0 until weeks) {
            val col = RemoteViews(pkg, R.layout.widget_col)
            for (d in 0 until 7) {
                val day = start + week * 7L + d
                val cell = RemoteViews(pkg, GridSizes.CELL[si])
                if (day > today) {
                    cell.setImageViewResource(R.id.cell, R.drawable.cell_future)
                } else {
                    val level = Levels.level(s?.count(day) ?: 0, goal)
                    val res = when (day) {
                        selected -> Levels.SELECTED_CELLS[level]
                        today -> Levels.TODAY_CELLS[level]
                        else -> Levels.CELLS[level]
                    }
                    cell.setImageViewResource(R.id.cell, res)
                    cell.setOnClickPendingIntent(R.id.cell, selectIntent(context, id, day))
                }
                col.addView(R.id.col, cell)
            }
            rv.addView(R.id.grid, col)
        }

        rv.setTextViewText(R.id.streak, streakText(s))
        val info = when {
            s == null -> "Open the app to set up"
            s.error != null -> s.error
            selected >= 0 -> Levels.reviewedOn(selected, s.count(selected))
            else -> "Today: ${s.count(today)} cards reviewed · goal $goal"
        }
        rv.setTextViewText(R.id.info, info)
        rv.setTextViewText(R.id.stats, if (s == null) "" else statsLine(s))
        rv.setOnClickPendingIntent(R.id.title, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.stats, openAnkiIntent(context))
        rv.setOnClickPendingIntent(R.id.refresh, refreshIntent(context, HeatmapWidget::class.java))
        return rv
    }

    fun buildToday(context: Context, s: Stats?): RemoteViews {
        val goal = Prefs(context).goal
        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        val count = s?.let { it.count(it.today()) } ?: 0
        rv.setImageViewBitmap(R.id.ring, ring(count, goal))
        rv.setTextViewText(R.id.streak, streakText(s))
        rv.setTextViewText(
            R.id.sub,
            when {
                s == null -> "Open the app to set up"
                s.error != null -> "Open the app"
                else -> "Best: ${s.bestStreak()} days"
            },
        )
        rv.setOnClickPendingIntent(R.id.root, openAnkiIntent(context))
        rv.setOnClickPendingIntent(R.id.streak, refreshIntent(context, TodayWidget::class.java))
        return rv
    }

    private fun ring(count: Int, goal: Int): Bitmap {
        val size = 240
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = size * 0.11f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val r = RectF(stroke / 2 + 2, stroke / 2 + 2, size - stroke / 2 - 2, size - stroke / 2 - 2)
        p.color = Levels.COLORS[0]
        c.drawArc(r, 0f, 360f, false, p)
        val frac = if (goal <= 0) 1f else (count.toFloat() / goal).coerceAtMost(1f)
        if (frac > 0f) {
            p.color = Levels.COLORS[4]
            c.drawArc(r, -90f, 360f * frac, false, p)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF1EFE8.toInt()
            textAlign = Paint.Align.CENTER
            textSize = size * 0.24f
            isFakeBoldText = true
        }
        c.drawText(count.toString(), size / 2f, size / 2f + t.textSize * 0.15f, t)
        t.textSize = size * 0.1f
        t.isFakeBoldText = false
        t.color = 0xFFB4B2A9.toInt()
        c.drawText("of $goal", size / 2f, size / 2f + size * 0.2f, t)
        return bmp
    }
}
