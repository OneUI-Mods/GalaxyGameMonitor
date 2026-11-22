package dev.ryuya.galaxygamemonitor

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

internal enum class MonitorItem(val bit: Int, val label: Int) {
    FPS(1 shl 0, R.string.monitor_fps),
    CPU(1 shl 1, R.string.monitor_cpu),
    GPU(1 shl 2, R.string.monitor_gpu),
    GPU_CLOCK(1 shl 3, R.string.monitor_gpu_clock),
    RAM(1 shl 4, R.string.monitor_ram),
    GAME_RAM(1 shl 7, R.string.monitor_game_ram),
    BATTERY_LEVEL(1 shl 6, R.string.monitor_battery_level),
    BATTERY_TEMPERATURE(1 shl 5, R.string.monitor_battery_temperature),
}

internal data class MonitorSettings(
    val peakRelative: Boolean,
    val visibleItems: Int,
    val itemsPerRow: Int,
    val order: List<MonitorItem>,
    val showInCapture: Boolean,
) {
    fun shows(item: MonitorItem): Boolean = visibleItems and item.bit != 0
}

internal object DisplaySettings {
    const val GAME_BOOSTER = "com.samsung.android.game.gametools"
    const val READ = "get_display_settings"
    const val UPDATE = "update_display_settings"
    const val KEY_PEAK_RELATIVE = "peak_relative"
    const val KEY_VISIBLE_ITEMS = "visible_items"
    const val KEY_ITEMS_PER_ROW = "items_per_row"
    const val KEY_ITEM_ORDER = "item_order"
    const val KEY_SHOW_IN_CAPTURE = "show_in_capture"
    const val KEY_ITEM_BIT = "item_bit"
    const val KEY_ITEM_VISIBLE = "item_visible"
    const val DEFAULT_PEAK_RELATIVE = false
    const val DEFAULT_ITEMS_PER_ROW = 4
    private const val KEY_KNOWN_ITEMS = "known_items"
    private const val PREVIOUS_ITEMS = 0b111111
    val allItems = MonitorItem.entries.fold(0) { bits, item -> bits or item.bit }
    val defaults = MonitorSettings(DEFAULT_PEAK_RELATIVE, allItems, DEFAULT_ITEMS_PER_ROW, MonitorItem.entries, true)
    // ContentService otherwise batches notifications for background processes.
    private const val NOTIFY_NO_DELAY = 1 shl 15
    val uri: Uri = Uri.parse("content://dev.ryuya.galaxygamemonitor.settings/display")

    fun read(context: Context): MonitorSettings = preferences(context).let {
        val known = it.getInt(KEY_KNOWN_ITEMS, PREVIOUS_ITEMS)
        val visible = it.getInt(KEY_VISIBLE_ITEMS, allItems) or (allItems and known.inv())
        MonitorSettings(
            it.getBoolean(KEY_PEAK_RELATIVE, DEFAULT_PEAK_RELATIVE),
            visible and allItems,
            rowCount(it.getInt(KEY_ITEMS_PER_ROW, DEFAULT_ITEMS_PER_ROW)),
            parseOrder(it.getString(KEY_ITEM_ORDER, null)),
            it.getBoolean(KEY_SHOW_IN_CAPTURE, true),
        )
    }

    fun set(context: Context, peakRelative: Boolean) {
        if (read(context).peakRelative == peakRelative) return
        preferences(context).edit().putBoolean(KEY_PEAK_RELATIVE, peakRelative).apply()
        notifyChange(context)
    }

    @Synchronized
    fun setVisible(context: Context, item: MonitorItem, visible: Boolean) {
        val current = read(context).visibleItems
        val updated = if (visible) current or item.bit else current and item.bit.inv()
        if (updated == current) return
        preferences(context).edit().putInt(KEY_VISIBLE_ITEMS, updated).putInt(KEY_KNOWN_ITEMS, allItems).apply()
        notifyChange(context)
    }

    fun setItemsPerRow(context: Context, requested: Int) {
        val count = rowCount(requested)
        if (read(context).itemsPerRow == count) return
        preferences(context).edit().putInt(KEY_ITEMS_PER_ROW, count).apply()
        notifyChange(context)
    }

    fun setOrder(context: Context, order: List<MonitorItem>) {
        require(order.size == MonitorItem.entries.size && order.distinct().size == order.size)
        if (read(context).order == order) return
        preferences(context).edit().putString(KEY_ITEM_ORDER, serializeOrder(order)).apply()
        notifyChange(context)
    }

    fun setShowInCapture(context: Context, visible: Boolean) {
        if (read(context).showInCapture == visible) return
        preferences(context).edit().putBoolean(KEY_SHOW_IN_CAPTURE, visible).apply()
        notifyChange(context)
    }

    fun serializeOrder(order: List<MonitorItem>): String = order.joinToString(",") { it.bit.toString() }

    private fun parseOrder(value: String?): List<MonitorItem> {
        val saved = value?.split(',').orEmpty().mapNotNull { token ->
            val bit = token.toIntOrNull()
            MonitorItem.entries.firstOrNull { it.bit == bit }
        }
        return (saved + MonitorItem.entries).distinct()
    }

    fun readRemote(context: Context): MonitorSettings? =
        context.contentResolver.call(uri, READ, null, null)?.let(::fromBundle)

    fun updateRemote(context: Context, changes: Bundle) {
        checkNotNull(context.contentResolver.call(uri, UPDATE, null, changes))
    }

    fun fromBundle(bundle: Bundle) = MonitorSettings(
        bundle.getBoolean(KEY_PEAK_RELATIVE, DEFAULT_PEAK_RELATIVE),
        bundle.getInt(KEY_VISIBLE_ITEMS, allItems) and allItems,
        rowCount(bundle.getInt(KEY_ITEMS_PER_ROW, DEFAULT_ITEMS_PER_ROW)),
        parseOrder(bundle.getString(KEY_ITEM_ORDER)),
        bundle.getBoolean(KEY_SHOW_IN_CAPTURE, true),
    )

    private fun rowCount(count: Int) = count.coerceIn(1, MonitorItem.entries.size)

    @SuppressLint("WrongConstant")
    private fun notifyChange(context: Context) {
        context.contentResolver.notifyChange(uri, null, ContentResolver.NOTIFY_UPDATE or NOTIFY_NO_DELAY)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("display_settings", Context.MODE_PRIVATE)
}

class DisplaySettingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (callingPackage != DisplaySettings.GAME_BOOSTER) {
            throw SecurityException("Only Game Booster can access the monitor settings")
        }
        val owner = requireNotNull(context)
        when (method) {
            DisplaySettings.READ -> Unit
            DisplaySettings.UPDATE -> update(owner, requireNotNull(extras))
            else -> throw IllegalArgumentException("Unknown monitor settings method")
        }
        val settings = DisplaySettings.read(owner)
        return Bundle().apply {
            putBoolean(DisplaySettings.KEY_PEAK_RELATIVE, settings.peakRelative)
            putInt(DisplaySettings.KEY_VISIBLE_ITEMS, settings.visibleItems)
            putInt(DisplaySettings.KEY_ITEMS_PER_ROW, settings.itemsPerRow)
            putString(DisplaySettings.KEY_ITEM_ORDER, DisplaySettings.serializeOrder(settings.order))
            putBoolean(DisplaySettings.KEY_SHOW_IN_CAPTURE, settings.showInCapture)
        }
    }

    private fun update(owner: Context, changes: Bundle) {
        when {
            changes.containsKey(DisplaySettings.KEY_ITEM_BIT) -> {
                val item = MonitorItem.entries.singleOrNull { it.bit == changes.getInt(DisplaySettings.KEY_ITEM_BIT) }
                require(item != null && changes.containsKey(DisplaySettings.KEY_ITEM_VISIBLE))
                DisplaySettings.setVisible(owner, item, changes.getBoolean(DisplaySettings.KEY_ITEM_VISIBLE))
            }
            changes.containsKey(DisplaySettings.KEY_ITEMS_PER_ROW) ->
                DisplaySettings.setItemsPerRow(owner, changes.getInt(DisplaySettings.KEY_ITEMS_PER_ROW))
            changes.containsKey(DisplaySettings.KEY_ITEM_ORDER) -> {
                val requested = requireNotNull(changes.getString(DisplaySettings.KEY_ITEM_ORDER))
                val order = DisplaySettings.fromBundle(changes).order
                require(DisplaySettings.serializeOrder(order) == requested)
                DisplaySettings.setOrder(owner, order)
            }
            changes.containsKey(DisplaySettings.KEY_PEAK_RELATIVE) ->
                DisplaySettings.set(owner, changes.getBoolean(DisplaySettings.KEY_PEAK_RELATIVE))
            changes.containsKey(DisplaySettings.KEY_SHOW_IN_CAPTURE) ->
                DisplaySettings.setShowInCapture(owner, changes.getBoolean(DisplaySettings.KEY_SHOW_IN_CAPTURE))
            else -> throw IllegalArgumentException("Missing monitor setting")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

internal class GameBoosterDisplaySettings(
    context: Context,
    private val onInvalidated: () -> Unit,
) {
    private val resolver = context.contentResolver
    private var observing = false
    @Volatile private var closed = false
    @Volatile private var cached: MonitorSettings? = null
    private var lastKnown = DisplaySettings.defaults
    private var lastReported: Boolean? = null
    private var retryAfterMs = 0L
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            invalidate()
        }
    }

    @Synchronized
    private fun invalidate() {
        if (closed) return
        onInvalidated()
        retryAfterMs = 0L
        cached = null
    }

    fun settings(): MonitorSettings = cached ?: readSetting()

    @Synchronized
    fun close() {
        closed = true
        if (observing) {
            observing = false
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    @Synchronized
    private fun readSetting(): MonitorSettings {
        if (closed) return DisplaySettings.defaults
        cached?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < retryAfterMs) return lastKnown

        val current = try {
            if (!observing) {
                resolver.registerContentObserver(DisplaySettings.uri, false, observer)
                observing = true
            }
            resolver.call(DisplaySettings.uri, DisplaySettings.READ, null, null)?.let(DisplaySettings::fromBundle)
        } catch (error: RuntimeException) {
            Log.w("GalaxyGameMonitor", "Display settings unavailable: ${error.javaClass.simpleName}")
            null
        }
        if (current == null) {
            retryAfterMs = now + 10_000
            return lastKnown
        }

        lastKnown = current
        cached = current
        if (lastReported != current.peakRelative) {
            lastReported = current.peakRelative
            Log.i("GalaxyGameMonitor", if (current.peakRelative) "CPU mode: peak-relative" else "CPU mode: busy time")
        }
        return current
    }
}
