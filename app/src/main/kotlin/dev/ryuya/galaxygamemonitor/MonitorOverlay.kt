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
import java.text.NumberFormat
import kotlin.math.ceil

internal class MonitorOverlay(context: Context) {
    data class Reading(val settings: MonitorSettings, val clockMhz: Int?, val batteryTenthsC: Int?)

    private val rootId = resourceId(context, "sgl_performance_monitor_bg")
    private val titleId = resourceId(context, "tv_title")
    private val valueId = resourceId(context, "tv_value")
    private val groups = listOf(
        StockGroup(MonitorItem.FPS, resourceId(context, "cl_item_fps")),
        StockGroup(MonitorItem.CPU, resourceId(context, "cl_item_cpu")),
        StockGroup(MonitorItem.GPU, resourceId(context, "cl_item_gpu"), MonitorItem.GPU_CLOCK, "MHz"),
        StockGroup(MonitorItem.RAM, resourceId(context, "cl_item_ram"), MonitorItem.BATTERY_TEMPERATURE, "BAT °C"),
    )
    val supported = rootId != 0 && titleId != 0 && valueId != 0 && groups.all { it.id != 0 }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var binding: Binding? = null
    private var lastReading: Reading? = null
    private val temperatures = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }

    fun submit(reading: Reading) {
        if (!supported || closed) return
        main.post {
            if (closed) return@post
            runCatching { render(reading) }.onFailure {
                Log.w(TAG, "System monitor items unavailable", it)
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

    private fun render(reading: Reading) {
        val current = binding
        if (current != null && current.root.get()?.isAttachedToWindow != true) restore()
        if (reading == lastReading) return
        val active = binding ?: bind() ?: return

        var anyVisible = false
        for (slot in active.slots) {
            val group = slot.group.get() ?: continue
            val stock = slot.stock.get() ?: continue
            val stockVisible = reading.settings.shows(slot.spec.item) && slot.stockVisibility != View.GONE
            stock.visibility = if (stockVisible) slot.stockVisibility else View.GONE

            val value = slot.extra?.value?.get()
            val showExtra = value != null && slot.spec.extraItem?.let(reading.settings::shows) == true
            if (value != null) {
                val nextText = when (slot.spec.extraItem) {
                    MonitorItem.GPU_CLOCK -> reading.clockMhz?.toString()
                    MonitorItem.BATTERY_TEMPERATURE -> reading.batteryTenthsC?.let { temperatures.format(it / 10.0) }
                    else -> null
                } ?: "—"
                if (showExtra && !value.text.contentEquals(nextText)) value.text = nextText
                (value.parent as? View)?.visibility = if (showExtra) View.VISIBLE else View.GONE
            }

            val showGroup = stockVisible || showExtra
            group.visibility = if (showGroup) slot.groupVisibility else View.GONE
            anyVisible = anyVisible || showGroup && slot.groupVisibility == View.VISIBLE
        }
        active.root.get()?.visibility = if (anyVisible) active.rootVisibility else View.GONE
        lastReading = reading
    }

    private fun bind(): Binding? {
        val root = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(rootId) }
            ?: return null
        val slots = groups.map { spec ->
            val group = root.findViewById<ViewGroup>(spec.id) ?: return null
            val stock = group.getChildAt(0) ?: return null
            Slot(spec, WeakReference(group), WeakReference(stock), group.visibility, stock.visibility)
        }
        val active = Binding(WeakReference(root), root.visibility, slots)
        binding = active
        for (slot in slots) {
            val title = slot.spec.extraTitle ?: continue
            val group = slot.group.get() ?: continue
            val stock = slot.stock.get() ?: continue
            slot.extra = addColumn(group, stock, title)
        }
        Log.i(TAG, "System monitor item controls attached")
        return active
    }

    private fun addColumn(group: ViewGroup, stock: View, label: String): ExtraColumn? {
        if (group.childCount != 1) return null
        val stockTitle = stock.findViewById<TextView>(titleId) ?: return null
        val stockValue = stock.findViewById<TextView>(valueId) ?: return null
        val stockParams = stock.layoutParams ?: return null

        val row = LinearLayout(group.context).apply { orientation = LinearLayout.HORIZONTAL }
        val title = matchingTextView(stockTitle).apply { text = label }
        val value = matchingTextView(stockValue)
        val digitWidth = ('0'..'9').maxOf { value.paint.measureText(it.toString()) }
        value.minWidth = ceil(digitWidth * 4).toInt() + value.paddingLeft + value.paddingRight + 2
        val column = LinearLayout(group.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = stockParams.width.coerceAtLeast(0)
            setPaddingRelative(stock.paddingStart, 0, stock.paddingEnd, 0)
            addView(title)
            addView(value)
        }

        group.removeView(stock)
        try {
            row.addView(stock, LinearLayout.LayoutParams(stockParams.width, stockParams.height))
            row.addView(column, LinearLayout.LayoutParams(WRAP, WRAP))
            group.addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
        } catch (error: RuntimeException) {
            group.removeView(row)
            row.removeView(stock)
            group.addView(stock, stockParams)
            throw error
        }
        return ExtraColumn(WeakReference(row), WeakReference(value), stockParams)
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
        val previous = binding ?: return
        binding = null
        lastReading = null
        for (slot in previous.slots) {
            val group = slot.group.get() ?: continue
            val stock = slot.stock.get() ?: continue
            val extra = slot.extra
            val row = extra?.row?.get()
            if (row != null && row.parent === group && stock.parent === row) {
                group.removeView(row)
                row.removeView(stock)
                group.addView(stock, extra.stockParams)
            }
            stock.visibility = slot.stockVisibility
            group.visibility = slot.groupVisibility
        }
        previous.root.get()?.visibility = previous.rootVisibility
    }

    private fun restoreSafely() {
        runCatching { restore() }.onFailure { Log.w(TAG, "System monitor cleanup failed", it) }
    }

    private data class StockGroup(
        val item: MonitorItem,
        val id: Int,
        val extraItem: MonitorItem? = null,
        val extraTitle: String? = null,
    )

    private data class Slot(
        val spec: StockGroup,
        val group: WeakReference<ViewGroup>,
        val stock: WeakReference<View>,
        val groupVisibility: Int,
        val stockVisibility: Int,
        var extra: ExtraColumn? = null,
    )

    private data class Binding(val root: WeakReference<View>, val rootVisibility: Int, val slots: List<Slot>)

    private data class ExtraColumn(
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
