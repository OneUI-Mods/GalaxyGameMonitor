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
import kotlin.math.roundToInt

internal class MonitorOverlay(context: Context, private val capture: MonitorCapture) {
    data class Reading(
        val settings: MonitorSettings,
        val clockMhz: Int?,
        val battery: DeviceCapacity.BatterySample?,
        val gameRamKiB: Long?,
    )

    private val rootId = resourceId(context, "sgl_performance_monitor_bg")
    private val itemsId = resourceId(context, "cl_performance_monitor_items")
    private val titleId = resourceId(context, "tv_title")
    private val valueId = resourceId(context, "tv_value")
    private val groupIds = mapOf(
        MonitorItem.FPS to resourceId(context, "cl_item_fps"),
        MonitorItem.CPU to resourceId(context, "cl_item_cpu"),
        MonitorItem.GPU to resourceId(context, "cl_item_gpu"),
        MonitorItem.RAM to resourceId(context, "cl_item_ram"),
    )
    val supported = rootId != 0 && itemsId != 0 && titleId != 0 && valueId != 0 &&
        groupIds.values.all { it != 0 }

    private val main = Handler(Looper.getMainLooper())
    private val readingLock = Any()
    @Volatile private var closed = false
    @Volatile private var window: WeakReference<View>? = null
    private var pendingReading: Reading? = null
    private val draw = Runnable {
        val reading = synchronized(readingLock) {
            pendingReading.also { pendingReading = null }
        } ?: return@Runnable
        if (!closed) {
            runCatching { render(reading) }.onFailure {
                Log.w(TAG, "System monitor items unavailable", it)
                close()
            }
        }
    }
    @Volatile var monitorVisible = false
        private set
    private var binding: Binding? = null
    private var lastReading: Reading? = null
    private val temperatures = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }
    private val gigabytes = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = false
    }
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            main.post {
                if (binding?.root === view && !view.isAttachedToWindow) restoreSafely()
            }
        }
    }

    fun onWindowAdded(view: View) {
        if (!closed) window = WeakReference(view)
    }

    fun submit(reading: Reading) = synchronized(readingLock) {
        if (!supported || closed) return@synchronized
        val scheduled = pendingReading != null
        pendingReading = reading
        if (!scheduled && !main.post(draw)) pendingReading = null
    }

    fun close() {
        synchronized(readingLock) {
            closed = true
            pendingReading = null
        }
        window = null
        monitorVisible = false
        main.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) restoreSafely()
        else main.post { restoreSafely() }
    }

    private fun render(reading: Reading) {
        if (binding?.root?.isAttachedToWindow == false) restore()
        val active = binding ?: bind() ?: return
        capture.update(active.root, reading.settings.showInCapture)
        monitorVisible = active.root.isShown
        val previous = lastReading
        if (reading == previous) return
        if (previous?.settings?.visibleItems != reading.settings.visibleItems ||
            previous.settings.itemsPerRow != reading.settings.itemsPerRow ||
            previous.settings.order != reading.settings.order
        ) arrangeRows(active, reading.settings)
        if (previous == null || previous.clockMhz != reading.clockMhz) {
            setReading(active.extra.getValue(MonitorItem.GPU_CLOCK), reading.clockMhz?.toString())
        }
        if (previous == null || previous.gameRamKiB != reading.gameRamKiB) {
            val ram = reading.gameRamKiB?.let { gigabytes.format(it / KIB_PER_GIB) }
            setReading(active.extra.getValue(MonitorItem.GAME_RAM), ram)
        }
        val battery = reading.battery
        if (previous == null || previous.battery?.percent != battery?.percent) {
            setReading(active.extra.getValue(MonitorItem.BATTERY_LEVEL), battery?.percent?.toString())
        }
        if (previous == null || previous.battery?.temperatureTenthsC != battery?.temperatureTenthsC) {
            val temperature = battery?.temperatureTenthsC?.let { temperatures.format(it / 10.0) }
            setReading(active.extra.getValue(MonitorItem.BATTERY_TEMPERATURE), temperature)
        }
        lastReading = reading
    }

    private fun setReading(view: TextView, reading: String?) {
        val text = reading ?: "—"
        if (!view.text.contentEquals(text)) view.text = text
    }

    private fun arrangeRows(active: Binding, settings: MonitorSettings) {
        clearRows(active.grid)
        val rows = settings.order.filter(settings::shows).chunked(settings.itemsPerRow)
        val gap = (3 * active.grid.resources.displayMetrics.density).roundToInt()
        for ((index, items) in rows.withIndex()) {
            val row = LinearLayout(active.grid.context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in items) row.addView(active.columns.getValue(item), LinearLayout.LayoutParams(WRAP, WRAP))
            active.grid.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                if (index != 0) topMargin = gap
            })
        }
        active.root.visibility = if (rows.isEmpty()) View.GONE else active.rootVisibility
    }

    private fun bind(): Binding? {
        val root = window?.get()?.takeIf { it.isAttachedToWindow }?.findViewById<View>(rootId)
            ?: WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(rootId) }
            ?: return null
        val container = root.findViewById<ViewGroup>(itemsId) ?: return null
        if (container.childCount != groupIds.size) return null

        val stockColumns = groupIds.map { (item, id) ->
            val column = container.findViewById<ViewGroup>(id) ?: return null
            if (column.parent !== container) return null
            StockColumn(item, column, column.layoutParams, container.indexOfChild(column))
        }.sortedBy(StockColumn::index)
        val template = stockColumns.first { it.item == MonitorItem.GPU }.view.getChildAt(0) ?: return null
        val extraColumns = mapOf(
            MonitorItem.GPU_CLOCK to "MHz",
            MonitorItem.GAME_RAM to "GAME GiB",
            MonitorItem.BATTERY_LEVEL to "BAT %",
            MonitorItem.BATTERY_TEMPERATURE to "BAT °C",
        ).mapValues { (_, label) -> extraColumn(template, label) ?: return null }
        val columns = stockColumns.associate { it.item to it.view }.toMutableMap().apply {
            for ((item, column) in extraColumns) put(item, column.view)
        }
        val grid = LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val active = Binding(root, root.visibility, container, grid, stockColumns, columns, extraColumns.mapValues { it.value.value })
        binding = active
        root.addOnAttachStateChangeListener(detachListener)
        for (column in stockColumns) container.removeView(column.view)
        container.addView(grid, ViewGroup.LayoutParams(WRAP, WRAP))
        Log.i(TAG, "System monitor row layout attached")
        return active
    }

    private fun extraColumn(template: View, label: String): ExtraColumn? {
        val stockTitle = template.findViewById<TextView>(titleId) ?: return null
        val stockValue = template.findViewById<TextView>(valueId) ?: return null
        val title = matchingTextView(stockTitle).apply { text = label }
        val value = matchingTextView(stockValue)
        val digitWidth = ('0'..'9').maxOf { value.paint.measureText(it.toString()) }
        value.minWidth = ceil(digitWidth * 4).toInt() + value.paddingLeft + value.paddingRight + 2
        val column = LinearLayout(template.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = template.layoutParams.width.coerceAtLeast(0)
            setPaddingRelative(template.paddingStart, 0, template.paddingEnd, 0)
            addView(title)
            addView(value)
        }
        return ExtraColumn(column, value)
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

    private fun clearRows(grid: LinearLayout) {
        for (index in 0 until grid.childCount) (grid.getChildAt(index) as LinearLayout).removeAllViews()
        grid.removeAllViews()
    }

    private fun restore() {
        val previous = binding ?: return
        binding = null
        monitorVisible = false
        lastReading = null
        previous.root.removeOnAttachStateChangeListener(detachListener)
        clearRows(previous.grid)
        previous.container.removeView(previous.grid)
        for (column in previous.stock) {
            if (column.view.parent == null) previous.container.addView(column.view, column.index, column.params)
        }
        previous.root.visibility = previous.rootVisibility
    }

    private fun restoreSafely() {
        runCatching { restore() }.onFailure { Log.w(TAG, "System monitor cleanup failed", it) }
    }

    private data class StockColumn(
        val item: MonitorItem,
        val view: ViewGroup,
        val params: ViewGroup.LayoutParams,
        val index: Int,
    )

    private data class ExtraColumn(val view: LinearLayout, val value: TextView)

    private data class Binding(
        val root: View,
        val rootVisibility: Int,
        val container: ViewGroup,
        val grid: LinearLayout,
        val stock: List<StockColumn>,
        val columns: Map<MonitorItem, View>,
        val extra: Map<MonitorItem, TextView>,
    )

    private companion object {
        const val TAG = "GalaxyGameMonitor"
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val KIB_PER_GIB = 1_048_576.0

        @SuppressLint("DiscouragedApi")
        fun resourceId(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "id", DisplaySettings.GAME_BOOSTER)
    }
}
