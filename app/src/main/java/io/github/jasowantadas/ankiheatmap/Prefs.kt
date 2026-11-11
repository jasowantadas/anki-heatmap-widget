package io.github.jasowantadas.ankiheatmap

import android.content.Context
import android.os.Environment

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var collectionPath: String
        get() = sp.getString("path", null) ?: DEFAULT_PATH
        set(value) = sp.edit().putString("path", value).apply()

    var goal: Int
        get() = sp.getInt("goal", 100)
        set(value) = sp.edit().putInt("goal", value).apply()

    /** Deck name ("Parent::Child"); empty = all decks. */
    var deck: String
        get() = sp.getString("deck", "") ?: ""
        set(value) = sp.edit().putString("deck", value).apply()

    /** Day picked by tapping a square on a widget (-1 = none). */
    fun selectedDay(widgetId: Int): Long = sp.getLong("sel_$widgetId", -1L)

    fun setSelectedDay(widgetId: Int, day: Long) = sp.edit().putLong("sel_$widgetId", day).apply()

    companion object {
        @Suppress("DEPRECATION")
        val DEFAULT_PATH: String =
            Environment.getExternalStorageDirectory().path + "/AnkiDroid/collection.anki2"
    }
}
