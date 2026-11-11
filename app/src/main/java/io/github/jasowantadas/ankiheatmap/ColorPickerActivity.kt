package io.github.jasowantadas.ankiheatmap

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Colour wheel: drag around for the hue, towards the centre for paler colours. */
class ColorWheelView(context: Context) : View(context) {
    var hue = 0f
    var sat = 1f
    var onChange: (() -> Unit)? = null

    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteness = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (320 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(w, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = (min(w, h) / 2f - 12 * resources.displayMetrics.density).coerceAtLeast(1f)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }
        wheel.shader = SweepGradient(cx, cy, hues, null)
        whiteness.shader = RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, wheel)
        canvas.drawCircle(cx, cy, radius, whiteness)
        val a = Math.toRadians(hue.toDouble())
        val mx = cx + (cos(a) * sat * radius).toFloat()
        val my = cy + (sin(a) * sat * radius).toFloat()
        val d = resources.displayMetrics.density
        marker.strokeWidth = 4 * d
        marker.color = Color.BLACK
        canvas.drawCircle(mx, my, 11 * d, marker)
        marker.strokeWidth = 2 * d
        marker.color = Color.WHITE
        canvas.drawCircle(mx, my, 11 * d, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val dx = event.x - cx
            val dy = event.y - cy
            var h = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (h < 0) h += 360f
            hue = h
            sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
            invalidate()
            onChange?.invoke()
        }
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Pick the middle shade; the app makes the lighter and darker ones from it. */
class ColorPickerActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var wheelView: ColorWheelView
    private lateinit var brightness: SeekBar
    private lateinit var hexEdit: EditText
    private lateinit var swatches: List<View>
    private lateinit var middle: View
    private var updatingHex = false

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val presets = listOf(
        "Green" to 0xFF639922.toInt(),
        "Teal" to 0xFF1D9E75.toInt(),
        "Blue" to 0xFF378ADD.toInt(),
        "Purple" to 0xFF7F77DD.toInt(),
        "Pink" to 0xFFD4537E.toInt(),
        "Orange" to 0xFFD85A30.toInt(),
        "Amber" to 0xFFBA7517.toInt(),
        "Red" to 0xFFE24B4A.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        setColor(prefs.baseColor)
    }

    private fun currentColor(): Int =
        Color.HSVToColor(floatArrayOf(wheelView.hue, wheelView.sat, brightness.progress / 100f))

    private fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        wheelView.hue = hsv[0]
        wheelView.sat = hsv[1]
        wheelView.invalidate()
        brightness.progress = (hsv[2] * 100).toInt()
        updatePreview()
    }

    private fun updatePreview() {
        val c = currentColor()
        val palette = Levels.palette(c)
        swatches.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setColor(if (i == 0) Levels.EMPTY else palette[i - 1])
        }
        (middle.background as GradientDrawable).setColor(c)
        if (!updatingHex) {
            updatingHex = true
            hexEdit.setText(String.format("#%06X", c and 0xFFFFFF))
            updatingHex = false
        }
    }

    private fun swatch(sizeDp: Int): View = View(this).apply {
        background = GradientDrawable().apply { cornerRadius = 3 * dp }
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { setMargins(px(2), px(2), px(2), px(2)) }
    }

    private fun label(s: String, size: Float = 14f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, px(10), 0, px(4))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(18), px(18), px(32))
        }
        root.addView(label("Heatmap colour", 22f))
        root.addView(label("Pick the middle shade (an average day). The app makes darker shades for lighter days and brighter shades for heavier days.", 13f))

        wheelView = ColorWheelView(this).apply { onChange = { updatePreview() } }
        root.addView(wheelView)

        root.addView(label("Brightness"))
        brightness = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updatePreview()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(brightness)

        val chosenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, 0)
        }
        middle = swatch(36)
        chosenRow.addView(middle)
        hexEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        chosenRow.addView(hexEdit)
        chosenRow.addView(Button(this).apply {
            text = "Use hex"
            isAllCaps = false
            setOnClickListener {
                try {
                    setColor(Color.parseColor(hexEdit.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }))
                } catch (e: IllegalArgumentException) {
                    hexEdit.error = "Use a colour like #639922"
                }
            }
        })
        root.addView(chosenRow)

        root.addView(label("Preview: no reviews, then fewer → more cards"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swatches = List(11) { swatch(24).also { row.addView(it) } }
        root.addView(row)

        root.addView(label("Quick picks"))
        var presetRow: LinearLayout? = null
        presets.forEachIndexed { i, (name, presetColor) ->
            if (i % 4 == 0) {
                presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(presetRow)
            }
            presetRow?.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(presetColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply { setMargins(px(3), px(3), px(3), px(3)) }
                setOnClickListener { setColor(presetColor) }
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(16), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Reset"
            isAllCaps = false
            setOnClickListener { setColor(Levels.DEFAULT_BASE) }
        })
        buttons.addView(Button(this).apply {
            text = "Save"
            isAllCaps = false
            setOnClickListener {
                prefs.baseColor = currentColor()
                val app = applicationContext
                thread { WidgetUpdater.redrawAll(app, null) }
                finish()
            }
        })
        root.addView(buttons)

        return ScrollView(this).apply { addView(root) }
    }
}
