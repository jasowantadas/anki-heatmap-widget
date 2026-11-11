package io.github.jasowantadas.ankiheatmap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object WidgetUpdater {
    const val ACTION_REFRESH = "io.github.jasowantadas.ankiheatmap.REFRESH"
    private const val ANKIDROID = "com.ichi2.anki"
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun refreshAll(context: Context, force: Boolean) {
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

    private fun dayIntent(context: Context, day: Long): PendingIntent {
        val i = Intent(context, DayDetailActivity::class.java)
            .setData(Uri.parse("ankiheatmap://day/$day"))
            .putExtra(DayDetailActivity.EXTRA_DAY, day)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, (day % 100000).toInt() + 100, i, FLAGS)
    }

    private fun streakText(s: Stats?): String {
        val n = s?.currentStreak() ?: 0
        return "🔥 $n day" + if (n == 1) "" else "s"
    }

    fun buildHeatmap(context: Context, mgr: AppWidgetManager, id: Int, s: Stats?): RemoteViews {
        val pkg = context.packageName
        val goal = Prefs(context).goal
        val rv = RemoteViews(pkg, R.layout.widget_heatmap)

        val minWidth = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val weeks = if (minWidth > 0) ((minWidth - 20 - 16) / 14).coerceIn(4, 53) else 18

        val today = s?.today() ?: AnkiDay.epochDay(System.currentTimeMillis(), 4)
        val start = Levels.gridStart(today, weeks)

        rv.removeAllViews(R.id.months)
        var w = 0
        while (w < weeks) {
            val first = LocalDate.ofEpochDay(start + w * 7L)
            val prev = if (w == 0) null else LocalDate.ofEpochDay(start + (w - 1) * 7L)
            val newMonth = if (prev == null) first.dayOfMonth <= 7 else prev.month != first.month
            if (newMonth && w < weeks - 1) {
                val label = RemoteViews(pkg, R.layout.widget_month_label)
                label.setTextViewText(R.id.label, first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                rv.addView(R.id.months, label)
                w += 2
            } else {
                rv.addView(R.id.months, RemoteViews(pkg, R.layout.widget_month_space))
                w += 1
            }
        }

        rv.removeAllViews(R.id.grid)
        for (week in 0 until weeks) {
            val col = RemoteViews(pkg, R.layout.widget_col)
            for (d in 0 until 7) {
                val day = start + week * 7L + d
                val cell = RemoteViews(pkg, R.layout.widget_cell)
                if (day > today) {
                    cell.setImageViewResource(R.id.cell, R.drawable.cell_future)
                } else {
                    val level = Levels.level(s?.count(day) ?: 0, goal)
                    val res = if (day == today) Levels.TODAY_CELLS[level] else Levels.CELLS[level]
                    cell.setImageViewResource(R.id.cell, res)
                    cell.setOnClickPendingIntent(R.id.cell, dayIntent(context, day))
                }
                col.addView(R.id.col, cell)
            }
            rv.addView(R.id.grid, col)
        }

        rv.setTextViewText(R.id.streak, streakText(s))
        val footer = when {
            s == null -> "Open the app to set up"
            s.error != null -> s.error
            else -> "Today: ${s.count(today)} cards · goal $goal"
        }
        rv.setTextViewText(R.id.footer, footer)
        rv.setOnClickPendingIntent(R.id.title, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.footer, openAnkiIntent(context))
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
