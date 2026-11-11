package io.github.jasowantadas.ankiheatmap

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Quotes come from a plain text file online (one quote per line, lines starting with # are
 * ignored), e.g. a GitHub gist. Edit the file and the widget picks up the change, no reinstall.
 * One quote is shown per day, going through the list in order.
 * No link, or an empty file, means no quote: the widget title stays "Anki reviews".
 */
object Quotes {
    private const val FETCH_EVERY_MS = 3 * 60 * 60 * 1000L

    /** A gist page link works too: it's turned into its raw-text link. */
    fun rawUrl(url: String): String {
        val u = url.trim()
        return if (u.contains("gist.github.com") && !u.contains("/raw")) u.trimEnd('/') + "/raw" else u
    }

    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Downloads the quotes file if it's time (or [force]). Call from a background thread. */
    fun refresh(context: Context, force: Boolean) {
        val prefs = Prefs(context)
        val url = prefs.quotesUrl
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.quotesFetchedAt < FETCH_EVERY_MS) return
        try {
            val raw = rawUrl(url)
            val conn = URL(raw + (if (raw.contains("?")) "&" else "?") + "t=$now").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            try {
                if (conn.responseCode == 200) {
                    prefs.quotesText = conn.inputStream.bufferedReader().use { it.readText() }
                    prefs.quotesFetchedAt = now
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Offline or bad link: keep the last quotes we had.
        }
    }

    /** Today's quote, or null when there's no link or nothing in the file. */
    fun forDay(context: Context, day: Long): String? {
        val prefs = Prefs(context)
        if (prefs.quotesUrl.isBlank()) return null
        val list = parse(prefs.quotesText)
        if (list.isEmpty()) return null
        return list[Math.floorMod(day, list.size.toLong()).toInt()]
    }
}
