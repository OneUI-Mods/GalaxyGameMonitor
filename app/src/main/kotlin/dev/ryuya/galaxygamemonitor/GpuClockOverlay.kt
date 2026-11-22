package dev.ryuya.galaxygamemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.ceil

internal class GpuClockOverlay(context: Context) {
    private val rootId = resourceId(context, "sgl_performance_monitor_bg")
    private val gpuGroupId = resourceId(context, "cl_item_gpu")
    private val titleId = resourceId(context, "tv_title")
    private val valueId = resourceId(context, "tv_value")
    val supported = rootId != 0 && gpuGroupId != 0 && titleId != 0 && valueId != 0

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var binding: Binding? = null
    private var currentMhz: Int? = null

    fun submit(mhz: Int?) {
        if (!supported || closed) return
        main.post {
            if (closed) return@post
            runCatching { render(mhz) }.onFailure {
                Log.w(TAG, "GPU clock display unavailable", it)
                close()
            }
        }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) restoreSafely()
        else main.post { restoreSafely() }
    }

    private fun render(mhz: Int?) {
        val current = binding
        if (current != null && current.row.get()?.isAttachedToWindow != true) restore()
        if (mhz == null) {
            binding?.value?.get()?.let { (it.parent as? View)?.visibility = View.GONE }
            currentMhz = null
            return
        }
        if (binding == null) {
            val group = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { window ->
                window.findViewById<View>(rootId)?.findViewById<ViewGroup>(gpuGroupId)
            } ?: return
            binding = addClockColumn(group) ?: return
            Log.i(TAG, "GPU clock column attached: $mhz MHz")
        }
        if (mhz == currentMhz) return
        val value = binding?.value?.get() ?: return
        value.text = mhz.toString()
        (value.parent as? View)?.visibility = View.VISIBLE
        currentMhz = mhz
    }

    private fun addClockColumn(group: ViewGroup): Binding? {
        if (group.childCount != 1) return null
        val stockColumn = group.getChildAt(0)
        val stockTitle = stockColumn.findViewById<TextView>(titleId) ?: return null
        val stockValue = stockColumn.findViewById<TextView>(valueId) ?: return null
        val stockParams = stockColumn.layoutParams ?: return null

        val row = LinearLayout(group.context).apply { orientation = LinearLayout.HORIZONTAL }
        val title = matchingTextView(stockTitle).apply { text = "MHz" }
        val value = matchingTextView(stockValue)
        val digitWidth = ('0'..'9').maxOf { value.paint.measureText(it.toString()) }
        value.minWidth = ceil(digitWidth * 4).toInt() + value.paddingLeft + value.paddingRight + 2
        val clockColumn = LinearLayout(group.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = stockParams.width.coerceAtLeast(0)
            setPaddingRelative(stockColumn.paddingStart, 0, stockColumn.paddingEnd, 0)
            addView(title)
            addView(value)
        }

        group.removeView(stockColumn)
        try {
            row.addView(stockColumn, LinearLayout.LayoutParams(stockParams.width, stockParams.height))
            row.addView(clockColumn, LinearLayout.LayoutParams(WRAP, WRAP))
            group.addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
        } catch (error: RuntimeException) {
            group.removeView(row)
            row.removeView(stockColumn)
            group.addView(stockColumn, stockParams)
            throw error
        }
        return Binding(WeakReference(row), WeakReference(value), stockParams)
    }

    private fun matchingTextView(template: TextView) = TextView(template.context).apply {
        gravity = Gravity.CENTER
        setSingleLine(true)
        includeFontPadding = template.includeFontPadding
        typeface = template.typeface
        letterSpacing = template.letterSpacing
        setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
        setTextColor(template.textColors)
        setPaddingRelative(template.paddingStart, template.paddingTop, template.paddingEnd, template.paddingBottom)
    }

    private fun restore() {
        val previous = binding
        binding = null
        currentMhz = null
        val row = previous?.row?.get() ?: return
        val group = row.parent as? ViewGroup ?: return
        val stockColumn = row.getChildAt(0) ?: return
        group.removeView(row)
        row.removeView(stockColumn)
        group.addView(stockColumn, previous.stockParams)
    }

    private fun restoreSafely() {
        runCatching { restore() }.onFailure { Log.w(TAG, "GPU clock column cleanup failed", it) }
    }

    private data class Binding(
        val row: WeakReference<LinearLayout>,
        val value: WeakReference<TextView>,
        val stockParams: ViewGroup.LayoutParams,
    )

    private companion object {
        const val TAG = "GalaxyGameMonitor"
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        @SuppressLint("DiscouragedApi")
        fun resourceId(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "id", DisplaySettings.GAME_BOOSTER)
    }
}
