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
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object WidgetUpdater {
    const val ACTION_REFRESH = "io.github.jasowantadas.ankiheatmap.REFRESH"
    const val ACTION_SELECT = "io.github.jasowantadas.ankiheatmap.SELECT"
    const val EXTRA_DAY = "day"
    const val ACTION_YEAR = "io.github.jasowantadas.ankiheatmap.YEAR"
    const val EXTRA_DELTA = "delta"
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

    private fun yearIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
        val i = Intent(context, HeatmapWidget::class.java)
            .setAction(ACTION_YEAR)
            .setData(Uri.parse("ankiheatmap://year/$widgetId/$delta"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(EXTRA_DELTA, delta)
        return PendingIntent.getBroadcast(context, 5, i, FLAGS)
    }

    /** Years with at least one day studied, plus the current year. */
    private fun availableYears(s: Stats?, currentYear: Int): List<Int> {
        val years = sortedSetOf(currentYear)
        s?.days?.forEach { (d, st) -> if (st.reviews > 0) years.add(LocalDate.ofEpochDay(d).year) }
        return years.toList()
    }

    fun changeYear(context: Context, widgetId: Int, delta: Int) {
        if (widgetId < 0) return
        val prefs = Prefs(context)
        val s = AnkiRepository.cached(context)
        val currentYear = LocalDate.ofEpochDay(s?.today() ?: AnkiDay.epochDay(System.currentTimeMillis(), 4)).year
        val years = availableYears(s, currentYear)
        val shown = prefs.year(widgetId).takeIf { it in years } ?: currentYear
        val idx = (years.indexOf(shown) + delta).coerceIn(0, years.size - 1)
        prefs.setYear(widgetId, if (years[idx] == currentYear) 0 else years[idx])
        prefs.setSelectedDay(widgetId, -1L)
        val mgr = AppWidgetManager.getInstance(context)
        mgr.updateAppWidget(widgetId, buildHeatmap(context, mgr, widgetId, s))
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

    private val MONTH_LAYOUTS = intArrayOf(
        R.layout.hm_month_1, R.layout.hm_month_2, R.layout.hm_month_3,
        R.layout.hm_month_4, R.layout.hm_month_5, R.layout.hm_month_6,
    )

    /** A month label as wide as [weeks] grid columns (wider spans are split into blank pieces). */
    private fun monthLabel(pkg: String, weeks: Int, text: String): RemoteViews {
        if (weeks <= 6) {
            return RemoteViews(pkg, MONTH_LAYOUTS[weeks.coerceAtLeast(1) - 1]).apply { setTextViewText(R.id.label, text) }
        }
        val box = RemoteViews(pkg, MONTH_LAYOUTS[5]).apply { setTextViewText(R.id.label, text) }
        return box
    }

    /** Accent for text: a lighter shade of the chosen colour, readable on the dark widget. */
    private fun accent(context: Context): Int = Levels.palette(Prefs(context).baseColor)[6]

    private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    private fun statsLine(s: Stats, green: Int): CharSequence {
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
        part("Longest streak: ", days(s.bestStreak()))
        part("Current streak: ", days(s.currentStreak()), last = true)
        return b
    }

    @Suppress("UNUSED_PARAMETER")
    fun buildHeatmap(context: Context, mgr: AppWidgetManager, id: Int, s: Stats?): RemoteViews {
        val pkg = context.packageName
        val prefs = Prefs(context)
        val average = s?.dailyAverage() ?: 0
        val palette = Levels.palette(prefs.baseColor)
        val rv = RemoteViews(pkg, R.layout.widget_heatmap)

        val today = s?.today() ?: AnkiDay.epochDay(System.currentTimeMillis(), 4)
        val currentYear = LocalDate.ofEpochDay(today).year
        val years = availableYears(s, currentYear)
        val year = prefs.year(id).takeIf { it in years } ?: currentYear
        val yearIdx = years.indexOf(year)

        // Calendar year: Sunday on/before 1 Jan to Saturday on/after 31 Dec.
        val jan1 = LocalDate.of(year, 1, 1).toEpochDay()
        val dec31 = LocalDate.of(year, 12, 31).toEpochDay()
        val start = jan1 - LocalDate.ofEpochDay(jan1).dayOfWeek.value % 7
        val end = dec31 + (6 - LocalDate.ofEpochDay(dec31).dayOfWeek.value % 7)
        val weeks = ((end - start + 1) / 7).toInt()
        val selected = prefs.selectedDay(id).takeIf { it in jan1..minOf(dec31, today) } ?: -1L

        // The grid fills the widget's real width; this invisible shape keeps it weeks x 7 so squares stay square.
        rv.setImageViewResource(R.id.aspect, if (weeks >= 54) R.drawable.aspect_54 else R.drawable.aspect_53)

        // Month names: each label spans the weeks from its month's first week to the next month's.
        rv.removeAllViews(R.id.months)
        val monthStarts = (0 until weeks).filter { w ->
            (0 until 7).any { d ->
                val day = start + w * 7L + d
                day in jan1..dec31 && LocalDate.ofEpochDay(day).dayOfMonth == 1
            }
        }
        if (monthStarts.isNotEmpty() && monthStarts[0] > 0) {
            rv.addView(R.id.months, monthLabel(pkg, monthStarts[0], ""))
        }
        monthStarts.forEachIndexed { i, w ->
            val span = (if (i + 1 < monthStarts.size) monthStarts[i + 1] else weeks) - w
            val firstDay = (0 until 7).map { start + w * 7L + it }.first { it >= jan1 }
            val month = LocalDate.ofEpochDay(firstDay).let { d -> if (d.dayOfMonth == 1) d.month else d.month.plus(1) }
            rv.addView(R.id.months, monthLabel(pkg, span, month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)))
        }

        rv.removeAllViews(R.id.grid)
        for (week in 0 until weeks) {
            val col = RemoteViews(pkg, R.layout.widget_col)
            for (d in 0 until 7) {
                val day = start + week * 7L + d
                val cell: RemoteViews
                if (day < jan1 || day > dec31) {
                    cell = RemoteViews(pkg, R.layout.hm_cell)
                    cell.setImageViewResource(R.id.cell, R.drawable.cell_blank)
                } else if (day > today) {
                    cell = RemoteViews(pkg, R.layout.hm_cell)
                    cell.setImageViewResource(R.id.cell, R.drawable.cell_future)
                } else {
                    val color = Levels.color(Levels.level(s?.count(day) ?: 0, average), palette)
                    if (day == selected || day == today) {
                        // Outlined square: white for the tapped day, grey for today.
                        cell = RemoteViews(pkg, R.layout.hm_mark)
                        cell.setInt(R.id.ring, "setColorFilter", if (day == selected) 0xFFFFFFFF.toInt() else 0xFFB4B2A9.toInt())
                        cell.setInt(R.id.fill, "setColorFilter", color)
                    } else {
                        cell = RemoteViews(pkg, R.layout.hm_cell)
                        cell.setInt(R.id.cell, "setColorFilter", color)
                    }
                    cell.setOnClickPendingIntent(R.id.cell, selectIntent(context, id, day))
                }
                col.addView(R.id.col, cell)
            }
            rv.addView(R.id.grid, col)
        }

        rv.setTextViewText(R.id.year, year.toString())
        rv.setViewVisibility(R.id.prev, if (yearIdx > 0) View.VISIBLE else View.INVISIBLE)
        rv.setViewVisibility(R.id.next, if (yearIdx < years.size - 1) View.VISIBLE else View.INVISIBLE)
        rv.setOnClickPendingIntent(R.id.prev, yearIntent(context, id, -1))
        rv.setOnClickPendingIntent(R.id.next, yearIntent(context, id, 1))

        val info = when {
            s == null -> "Open the app to set up"
            s.error != null -> s.error
            selected >= 0 -> Levels.reviewedOn(selected, s.count(selected))
            year != currentYear -> "$year: ${(jan1..dec31).sumOf { s.count(it) }} cards reviewed in total"
            else -> "Today: ${s.count(today)} cards reviewed"
        }
        rv.setTextViewText(R.id.info, info)
        rv.setTextViewText(R.id.stats, if (s == null) "" else statsLine(s, accent(context)))
        rv.setOnClickPendingIntent(R.id.title, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.stats, openAnkiIntent(context))
        rv.setOnClickPendingIntent(
            R.id.settings,
            PendingIntent.getActivity(context, 6, Intent(context, ColorPickerActivity::class.java), FLAGS),
        )
        rv.setOnClickPendingIntent(R.id.refresh, refreshIntent(context, HeatmapWidget::class.java))
        return rv
    }

    fun buildToday(context: Context, s: Stats?): RemoteViews {
        val average = s?.dailyAverage() ?: 0
        val base = Prefs(context).baseColor
        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        val count = s?.let { it.count(it.today()) } ?: 0
        rv.setImageViewBitmap(R.id.ring, ring(count, average, base))
        rv.setTextViewText(R.id.streak, streakText(s))
        rv.setTextColor(R.id.streak, accent(context))
        rv.setTextViewText(
            R.id.sub,
            when {
                s == null -> "Open the app to set up"
                s.error != null -> "Open the app"
                else -> "Best: ${days(s.bestStreak())}"
            },
        )
        rv.setOnClickPendingIntent(R.id.root, openAnkiIntent(context))
        rv.setOnClickPendingIntent(R.id.streak, refreshIntent(context, TodayWidget::class.java))
        return rv
    }

    private fun ring(count: Int, average: Int, base: Int): Bitmap {
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
        p.color = Levels.EMPTY
        c.drawArc(r, 0f, 360f, false, p)
        val frac = if (average <= 0) (if (count > 0) 1f else 0f) else (count.toFloat() / average).coerceAtMost(1f)
        if (frac > 0f) {
            p.color = base
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
        c.drawText(if (average > 0) "avg $average" else "today", size / 2f, size / 2f + size * 0.2f, t)
        return bmp
    }
}
