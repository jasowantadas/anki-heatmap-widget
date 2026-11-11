package io.github.jasowantadas.ankiheatmap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var permStatus: TextView
    private lateinit var permButton: Button
    private lateinit var pathEdit: EditText
    private lateinit var goalEdit: EditText
    private lateinit var deckSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var heatmap: HeatmapView
    private lateinit var heatmapScroll: HorizontalScrollView
    private lateinit var dayText: TextView
    private lateinit var statsText: TextView
    private lateinit var forecastText: TextView

    private var deckChoices: List<String> = emptyList()
    private var current: Stats? = null

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        show(AnkiRepository.cached(this))
    }

    override fun onResume() {
        super.onResume()
        updatePermission()
        refresh(force = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermission()
        refresh(force = true)
    }

    // ---------- UI ----------

    private fun px(v: Int) = (v * dp).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(4), 0, px(4))
    }

    private fun heading(s: String) = text(s, 17f, bold = true).apply { setPadding(0, px(20), 0, px(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(18), px(18), px(32))
        }
        root.addView(text("Anki Heatmap", 24f, bold = true))
        statusText = text("", 13f)
        root.addView(statusText)
        root.addView(button("↻  Refresh now") { refresh(force = true) })

        heatmap = HeatmapView(this).apply {
            onDayTap = { day -> dayText.text = Levels.details(day, current?.days?.get(day)) }
        }
        heatmapScroll = HorizontalScrollView(this).apply {
            addView(heatmap)
            setPadding(0, px(12), 0, px(4))
        }
        root.addView(heatmapScroll)
        dayText = text("Tap a square to see that day.", 14f)
        root.addView(dayText)

        root.addView(heading("Stats"))
        statsText = text("", 15f).apply { setLineSpacing(0f, 1.25f) }
        root.addView(statsText)

        root.addView(heading("Due in the next 14 days"))
        forecastText = text("", 14f).apply { setLineSpacing(0f, 1.2f) }
        root.addView(forecastText)

        root.addView(heading("Settings"))
        root.addView(text("Deck to show", 13f))
        deckSpinner = Spinner(this)
        root.addView(deckSpinner)

        root.addView(text("Daily goal (cards). The square turns brightest green when you reach it.", 13f))
        goalEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.goal.toString())
        }
        root.addView(goalEdit)

        root.addView(heading("Setup"))
        permStatus = text("", 14f)
        root.addView(permStatus)
        permButton = button("Allow all files access") { requestAccess() }
        root.addView(permButton)

        root.addView(text(
            "AnkiDroid collection file. Use the F-Droid or GitHub version of AnkiDroid, with its " +
                "folder in shared storage (the AnkiDroid directory setting, usually under Settings → Advanced).",
            13f,
        ))
        pathEdit = EditText(this).apply {
            setText(prefs.collectionPath)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(pathEdit)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Use default") { pathEdit.setText(Prefs.DEFAULT_PATH) })
        row.addView(button("Save settings") { saveSettings() })
        root.addView(row)

        root.addView(heading("Widgets"))
        root.addView(text(
            "Long-press your home screen → Widgets → Anki Heatmap. Add \"Anki heatmap\" (resizable grid) " +
                "and/or \"Anki today\" (goal ring and streak). On the grid: tap a square for that day, ↻ to refresh, " +
                "the title to open this app, and the bottom line to open AnkiDroid.",
            13f,
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun saveSettings() {
        prefs.collectionPath = pathEdit.text.toString().trim()
        prefs.goal = goalEdit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: prefs.goal
        goalEdit.setText(prefs.goal.toString())
        refresh(force = true)
    }

    private fun setupDeckSpinner(decks: List<String>) {
        val choices = listOf("All decks") + decks
        if (choices == deckChoices) return
        deckChoices = choices
        deckSpinner.onItemSelectedListener = null
        deckSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        val idx = if (prefs.deck.isEmpty()) 0 else choices.indexOf(prefs.deck).coerceAtLeast(0)
        deckSpinner.setSelection(idx, false)
        deckSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = if (position == 0) "" else deckChoices[position]
                if (value != prefs.deck) {
                    prefs.deck = value
                    refresh(force = true)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------- data ----------

    private fun refresh(force: Boolean) {
        statusText.text = "Loading…"
        thread {
            val s = AnkiRepository.load(this, force)
            WidgetUpdater.redrawAll(this, s)
            runOnUiThread { show(s) }
        }
    }

    private fun show(s: Stats?) {
        current = s
        val goal = prefs.goal
        if (s == null) {
            statusText.text = "No data yet."
            return
        }
        val updated = if (s.loadedAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.loadedAt)) else "never"
        statusText.text = (s.error?.let { "⚠ $it\n" } ?: "") + "Last read from AnkiDroid: $updated"

        heatmap.goal = goal
        heatmap.stats = s
        heatmap.requestLayout()
        heatmapScroll.post { heatmapScroll.fullScroll(View.FOCUS_RIGHT) }

        val today = s.today()
        val studied = s.days.values.count { it.reviews > 0 }
        statsText.text = "Today: ${s.count(today)} cards (goal $goal)\n" +
            "Current streak: ${s.currentStreak()} days\n" +
            "Best streak: ${s.bestStreak()} days\n" +
            "Days studied: $studied\n" +
            "Total reviews: ${s.totalReviews()}"

        forecastText.text = s.forecast.withIndex().joinToString("\n") { (i, n) ->
            val label = when (i) {
                0 -> "Today (incl. overdue)"
                1 -> "Tomorrow"
                else -> Levels.shortDate(today + i)
            }
            "$label — $n"
        }

        setupDeckSpinner(s.decks)
    }

    // ---------- permission ----------

    private fun updatePermission() {
        val ok = AnkiRepository.hasStorageAccess(this)
        permStatus.text = if (ok) "✓ Storage access allowed" else "✗ Storage access needed to read AnkiDroid's data"
        permButton.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }
}
