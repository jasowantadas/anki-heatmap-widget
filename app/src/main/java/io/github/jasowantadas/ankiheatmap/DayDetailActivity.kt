package io.github.jasowantadas.ankiheatmap

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small popup shown when a square on the widget is tapped. */
class DayDetailActivity : Activity() {
    companion object {
        const val EXTRA_DAY = "day"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val day = intent.getLongExtra(EXTRA_DAY, -1L)
        val stats = AnkiRepository.cached(this)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val lines = Levels.details(day, stats?.days?.get(day)).split("\n")
        layout.addView(TextView(this).apply {
            text = lines.first()
            textSize = 15f
        })
        layout.addView(TextView(this).apply {
            text = lines.drop(1).firstOrNull() ?: ""
            textSize = 24f
            setPadding(0, pad / 3, 0, pad / 3)
        })
        layout.addView(TextView(this).apply {
            text = lines.drop(2).joinToString("\n")
            textSize = 15f
            setLineSpacing(0f, 1.3f)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Open AnkiDroid"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage("com.ichi2.anki")?.let { startActivity(it) }
                finish()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })
        layout.addView(buttons)
        setContentView(layout, ViewGroup.LayoutParams(
            (300 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
}
