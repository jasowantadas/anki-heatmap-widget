package io.github.jasowantadas.ankiheatmap

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Levels {
    val COLORS = intArrayOf(
        0xFF5F5E5A.toInt(),
        0xFF27500A.toInt(),
        0xFF3B6D11.toInt(),
        0xFF639922.toInt(),
        0xFF97C459.toInt(),
    )
    val CELLS = intArrayOf(
        R.drawable.cell_l0, R.drawable.cell_l1, R.drawable.cell_l2, R.drawable.cell_l3, R.drawable.cell_l4,
    )
    val TODAY_CELLS = intArrayOf(
        R.drawable.cell_today_l0, R.drawable.cell_today_l1, R.drawable.cell_today_l2,
        R.drawable.cell_today_l3, R.drawable.cell_today_l4,
    )

    /** 0 = nothing, 1 = under 25% of goal, 2 = under 50%, 3 = under 100%, 4 = goal reached. */
    fun level(count: Int, goal: Int): Int = when {
        count <= 0 -> 0
        goal <= 0 || count >= goal -> 4
        count * 2 >= goal -> 3
        count * 4 >= goal -> 2
        else -> 1
    }

    /** Sunday-first column start, like GitHub. */
    fun gridStart(today: Long, weeks: Int): Long {
        val dow = LocalDate.ofEpochDay(today).dayOfWeek.value % 7
        return today - dow - (weeks - 1) * 7L
    }

    fun dateLabel(day: Long): String =
        LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))

    fun shortDate(day: Long): String =
        LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

    fun minutes(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun details(day: Long, s: DayStat?): String {
        if (s == null || s.reviews == 0) return "${dateLabel(day)}\nNo cards reviewed"
        val acc = (s.reviews - s.again) * 100 / s.reviews
        return "${dateLabel(day)}\n" +
            "${s.reviews} cards reviewed\n" +
            "New cards: ${s.newCards}\n" +
            "Pressed Again: ${s.again}  (accuracy $acc%)\n" +
            "Time spent: ${minutes(s.timeMs)}"
    }
}
