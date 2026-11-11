package io.github.jasowantadas.ankiheatmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/** Reviews done on one Anki day. */
data class DayStat(val reviews: Int, val again: Int, val timeMs: Long, val newCards: Int)

/** Anki days start at the "next day starts at" hour (rollover), not at midnight. */
object AnkiDay {
    fun offsetSeconds(nowMs: Long, rollover: Int): Long =
        TimeZone.getDefault().getOffset(nowMs) / 1000L - rollover * 3600L

    fun epochDay(ms: Long, rollover: Int): Long =
        Math.floorDiv(ms / 1000L + offsetSeconds(ms, rollover), 86400L)
}

class Stats(
    val days: Map<Long, DayStat>,
    val rolloverHour: Int,
    /** Index = days from today (0 = due today, including overdue). */
    val forecast: IntArray,
    val decks: List<String>,
    val loadedAt: Long,
    val fingerprint: String,
    val error: String? = null,
) {
    fun today(nowMs: Long = System.currentTimeMillis()): Long = AnkiDay.epochDay(nowMs, rolloverHour)

    fun count(day: Long): Int = days[day]?.reviews ?: 0

    fun totalReviews(): Int = days.values.sumOf { it.reviews }

    fun currentStreak(): Int {
        val t = today()
        var d = if (count(t) > 0) t else t - 1
        var n = 0
        while (count(d) > 0) {
            n++
            d--
        }
        return n
    }

    fun bestStreak(): Int {
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (d in days.filterValues { it.reviews > 0 }.keys.sorted()) {
            run = if (d == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    fun withError(message: String) = Stats(days, rolloverHour, forecast, decks, loadedAt, fingerprint, message)

    fun toJson(): String {
        val o = JSONObject()
        val arr = JSONArray()
        for ((d, s) in days) {
            arr.put(JSONArray().put(d).put(s.reviews).put(s.again).put(s.timeMs).put(s.newCards))
        }
        o.put("days", arr)
        o.put("rollover", rolloverHour)
        o.put("forecast", JSONArray().apply { forecast.forEach { put(it) } })
        o.put("decks", JSONArray().apply { decks.forEach { put(it) } })
        o.put("loadedAt", loadedAt)
        o.put("fingerprint", fingerprint)
        return o.toString()
    }

    companion object {
        fun empty(error: String) = Stats(emptyMap(), 4, IntArray(14), emptyList(), 0L, "", error)

        fun fromJson(text: String): Stats {
            val o = JSONObject(text)
            val days = HashMap<Long, DayStat>()
            val arr = o.getJSONArray("days")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                days[a.getLong(0)] = DayStat(a.getInt(1), a.getInt(2), a.getLong(3), a.getInt(4))
            }
            val f = o.getJSONArray("forecast")
            val decks = o.getJSONArray("decks")
            return Stats(
                days,
                o.getInt("rollover"),
                IntArray(f.length()) { f.getInt(it) },
                List(decks.length()) { decks.getString(it) },
                o.getLong("loadedAt"),
                o.getString("fingerprint"),
            )
        }
    }
}

object AnkiRepository {
    private val lock = Any()

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun cacheFile(context: Context) = File(context.filesDir, "stats.json")

    fun cached(context: Context): Stats? = try {
        val f = cacheFile(context)
        if (f.exists()) Stats.fromJson(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Reads the AnkiDroid collection (if it changed since last time, or [force]) and returns stats.
     * On failure returns the last good stats with an error message attached.
     */
    fun load(context: Context, force: Boolean): Stats {
        synchronized(lock) {
            return loadLocked(context, force)
        }
    }

    private fun loadLocked(context: Context, force: Boolean): Stats {
        val prefs = Prefs(context)
        val cached = cached(context)
        fun fail(msg: String): Stats = cached?.withError(msg) ?: Stats.empty(msg)

        if (!hasStorageAccess(context)) {
            return fail("Allow \"All files access\" in the Anki Heatmap app")
        }
        val col = File(prefs.collectionPath)
        if (!col.exists()) {
            return fail("Collection not found. Check the path in the app")
        }
        val wal = File(col.path + "-wal")
        val fingerprint = listOf(
            col.length(), col.lastModified(), wal.length(), wal.lastModified(), prefs.deck,
        ).joinToString(":")
        val now = System.currentTimeMillis()
        if (!force && cached != null && cached.fingerprint == fingerprint &&
            AnkiDay.epochDay(cached.loadedAt, cached.rolloverHour) == cached.today(now)
        ) {
            return cached
        }
        return try {
            val stats = readCollection(context, col, wal, prefs.deck, fingerprint)
            cacheFile(context).writeText(stats.toJson())
            stats
        } catch (e: Exception) {
            fail("Couldn't read collection: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readCollection(
        context: Context,
        col: File,
        wal: File,
        deck: String,
        fingerprint: String,
    ): Stats {
        // Work on a private copy so we never touch AnkiDroid's own files.
        val dir = File(context.cacheDir, "col")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val dbFile = File(dir, "collection.anki2")
        col.copyTo(dbFile, overwrite = true)
        if (wal.exists() && wal.length() > 0) {
            wal.copyTo(File(dir, "collection.anki2-wal"), overwrite = true)
        }
        val db = SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            val now = System.currentTimeMillis()
            val rollover = readRollover(db)
            val off = AnkiDay.offsetSeconds(now, rollover)
            val today = AnkiDay.epochDay(now, rollover)
            val decks = readDecks(db)

            val ids: Collection<Long>? = if (deck.isEmpty()) {
                null
            } else {
                decks.filter { (_, n) -> n == deck || n.startsWith("$deck::") }.keys.ifEmpty { null }
            }
            val idList = ids?.joinToString(",")
            val join = if (idList == null) "" else
                " JOIN cards c ON c.id = r.cid AND (c.did IN ($idList) OR c.odid IN ($idList))"
            val where = " WHERE r.type IN (0,1,2,3) AND r.ease > 0"

            val newPerDay = HashMap<Long, Int>()
            db.rawQuery(
                "SELECT (MIN(r.id) / 1000 + $off) / 86400 FROM revlog r$join$where GROUP BY r.cid",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val d = c.getLong(0)
                    newPerDay[d] = (newPerDay[d] ?: 0) + 1
                }
            }

            val days = HashMap<Long, DayStat>()
            db.rawQuery(
                "SELECT (r.id / 1000 + $off) / 86400 AS d, COUNT(*), " +
                    "SUM(CASE WHEN r.ease = 1 THEN 1 ELSE 0 END), SUM(r.time) " +
                    "FROM revlog r$join$where GROUP BY d",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val d = c.getLong(0)
                    days[d] = DayStat(c.getInt(1), c.getInt(2), c.getLong(3), newPerDay[d] ?: 0)
                }
            }

            val forecast = IntArray(14)
            try {
                var crt = 0L
                db.rawQuery("SELECT crt FROM col", null).use { c -> if (c.moveToFirst()) crt = c.getLong(0) }
                val todayIndex = today - Math.floorDiv(crt + off, 86400L)
                val deckCond = if (idList == null) "" else " AND (c.did IN ($idList) OR c.odid IN ($idList))"
                db.rawQuery(
                    "SELECT c.due, COUNT(*) FROM cards c WHERE c.queue IN (2,3)$deckCond GROUP BY c.due",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val ahead = (c.getLong(0) - todayIndex).coerceAtLeast(0L)
                        if (ahead < forecast.size) forecast[ahead.toInt()] += c.getInt(1)
                    }
                }
            } catch (e: Exception) {
                // Forecast is optional; ignore problems here.
            }

            return Stats(days, rollover, forecast, decks.values.sorted(), now, fingerprint)
        } finally {
            db.close()
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun blobOrString(c: Cursor, i: Int): String =
        if (c.getType(i) == Cursor.FIELD_TYPE_BLOB) String(c.getBlob(i), Charsets.UTF_8) else c.getString(i)

    private fun readRollover(db: SQLiteDatabase): Int {
        try {
            db.rawQuery("SELECT val FROM config WHERE key = 'rollover'", null).use { c ->
                if (c.moveToFirst()) blobOrString(c, 0).trim().toIntOrNull()?.let { return it }
            }
        } catch (e: Exception) {
        }
        try {
            db.rawQuery("SELECT conf FROM col", null).use { c ->
                if (c.moveToFirst()) {
                    val j = JSONObject(c.getString(0))
                    if (j.has("rollover")) return j.getInt("rollover")
                }
            }
        } catch (e: Exception) {
        }
        return 4
    }

    private fun readDecks(db: SQLiteDatabase): Map<Long, String> {
        val out = HashMap<Long, String>()
        try {
            db.rawQuery("SELECT id, name FROM decks", null).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getString(1).replace("\u001f", "::")
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
        }
        try {
            db.rawQuery("SELECT decks FROM col", null).use { c ->
                if (c.moveToFirst()) {
                    val j = JSONObject(c.getString(0))
                    for (key in j.keys()) {
                        out[key.toLong()] = j.getJSONObject(key).getString("name")
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
