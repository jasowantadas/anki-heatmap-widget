package io.github.jasowantadas.ankiheatmap

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Levels {
    /** Colour for days with no reviews. */
    val EMPTY = 0xFF5F5E5A.toInt()
    val DEFAULT_BASE = 0xFF639922.toInt()

    /**
     * Same thresholds as the desktop Review Heatmap add-on: fractions of your daily average
     * (average on days studied, at least 20). Gives 10 shades.
     */
    private val FACTORS = doubleArrayOf(0.125, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 4.0)

    /** 0 = no reviews, 1..10 = fewer to more cards relative to your average. */
    fun level(count: Int, average: Int): Int {
        if (count <= 0) return 0
        val avg = maxOf(20, average)
        var l = 1
        for (f in FACTORS) if (count >= f * avg) l++
        return l
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * 10 shades from the chosen colour: it sits in the middle (level 5 = an average day),
     * darker shades below it for lighter days and lighter shades above it for heavier days.
     */
    fun palette(base: Int): IntArray {
        val dark = mix(base, 0xFF000000.toInt(), 0.62f)
        val light = mix(base, 0xFFFFFFFF.toInt(), 0.62f)
        return IntArray(10) { i ->
            val k = i + 1
            if (k <= 5) mix(dark, base, (k - 1) / 4f) else mix(base, light, (k - 5) / 5f)
        }
    }

    fun color(level: Int, palette: IntArray): Int = if (level <= 0) EMPTY else palette[level - 1]

    /** Sunday-first column start, like GitHub. */
    fun gridStart(today: Long, weeks: Int): Long {
        val dow = LocalDate.ofEpochDay(today).dayOfWeek.value % 7
        return today - dow - (weeks - 1) * 7L
    }

    fun dateLabel(day: Long): String =
        LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))

    fun shortDate(day: Long): String =
        LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

    /** "43 cards reviewed on Saturday, 5 September 2026" */
    fun reviewedOn(day: Long, count: Int): String =
        (if (count == 1) "1 card" else "$count cards") + " reviewed on " +
            LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()))

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
