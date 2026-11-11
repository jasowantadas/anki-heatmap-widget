package io.github.jasowantadas.ankiheatmap

import android.content.Context
import android.os.Environment

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var collectionPath: String
        get() = sp.getString("path", null) ?: DEFAULT_PATH
        set(value) = sp.edit().putString("path", value).apply()

    /** Middle shade of the heatmap, picked by the user. */
    var baseColor: Int
        get() = sp.getInt("base_color", Levels.DEFAULT_BASE)
        set(value) = sp.edit().putInt("base_color", value).apply()

    /** Deck name ("Parent::Child"); empty = all decks. */
    var deck: String
        get() = sp.getString("deck", "") ?: ""
        set(value) = sp.edit().putString("deck", value).apply()

    /** Link to the online quotes file (a GitHub gist works). */
    var quotesUrl: String
        get() = sp.getString("quotes_url", "") ?: ""
        set(value) = sp.edit().putString("quotes_url", value).apply()

    var quotesText: String
        get() = sp.getString("quotes_text", "") ?: ""
        set(value) = sp.edit().putString("quotes_text", value).apply()

    var quotesFetchedAt: Long
        get() = sp.getLong("quotes_fetched", 0L)
        set(value) = sp.edit().putLong("quotes_fetched", value).apply()

    var showQuote: Boolean
        get() = sp.getBoolean("show_quote", true)
        set(value) = sp.edit().putBoolean("show_quote", value).apply()

    /** Day picked by tapping a square on a widget (-1 = none). */
    fun selectedDay(widgetId: Int): Long = sp.getLong("sel_$widgetId", -1L)

    fun setSelectedDay(widgetId: Int, day: Long) = sp.edit().putLong("sel_$widgetId", day).apply()

    /** Year shown on a widget (0 = current year). */
    fun year(widgetId: Int): Int = sp.getInt("year_$widgetId", 0)

    fun setYear(widgetId: Int, year: Int) = sp.edit().putInt("year_$widgetId", year).apply()

    companion object {
        @Suppress("DEPRECATION")
        val DEFAULT_PATH: String =
            Environment.getExternalStorageDirectory().path + "/AnkiDroid/collection.anki2"
    }
}
