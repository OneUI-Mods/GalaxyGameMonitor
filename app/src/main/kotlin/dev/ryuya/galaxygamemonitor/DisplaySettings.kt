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
    BATTERY_TEMPERATURE(1 shl 5, R.string.monitor_battery_temperature),
}

internal data class MonitorSettings(val peakRelative: Boolean, val visibleItems: Int) {
    fun shows(item: MonitorItem): Boolean = visibleItems and item.bit != 0
}

internal object DisplaySettings {
    const val GAME_BOOSTER = "com.samsung.android.game.gametools"
    const val READ = "get_display_settings"
    const val KEY_PEAK_RELATIVE = "peak_relative"
    const val KEY_VISIBLE_ITEMS = "visible_items"
    const val DEFAULT_PEAK_RELATIVE = false
    val allItems = MonitorItem.entries.fold(0) { bits, item -> bits or item.bit }
    val defaults = MonitorSettings(DEFAULT_PEAK_RELATIVE, allItems)
    // ContentService otherwise batches notifications for background processes.
    private const val NOTIFY_NO_DELAY = 1 shl 15
    val uri: Uri = Uri.parse("content://dev.ryuya.galaxygamemonitor.settings/display")

    fun read(context: Context): MonitorSettings = preferences(context).let {
        MonitorSettings(
            it.getBoolean(KEY_PEAK_RELATIVE, DEFAULT_PEAK_RELATIVE),
            it.getInt(KEY_VISIBLE_ITEMS, allItems) and allItems,
        )
    }

    fun set(context: Context, peakRelative: Boolean) {
        if (read(context).peakRelative == peakRelative) return
        preferences(context).edit().putBoolean(KEY_PEAK_RELATIVE, peakRelative).apply()
        notifyChange(context)
    }

    fun setVisible(context: Context, item: MonitorItem, visible: Boolean) {
        val current = read(context).visibleItems
        val updated = if (visible) current or item.bit else current and item.bit.inv()
        if (updated == current) return
        preferences(context).edit().putInt(KEY_VISIBLE_ITEMS, updated).apply()
        notifyChange(context)
    }

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
        require(method == DisplaySettings.READ)
        if (callingPackage != DisplaySettings.GAME_BOOSTER) {
            throw SecurityException("Only Game Booster can read the display settings")
        }
        val settings = DisplaySettings.read(requireNotNull(context))
        return Bundle().apply {
            putBoolean(DisplaySettings.KEY_PEAK_RELATIVE, settings.peakRelative)
            putInt(DisplaySettings.KEY_VISIBLE_ITEMS, settings.visibleItems)
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
            resolver.call(DisplaySettings.uri, DisplaySettings.READ, null, null)?.let {
                MonitorSettings(
                    it.getBoolean(DisplaySettings.KEY_PEAK_RELATIVE, DisplaySettings.DEFAULT_PEAK_RELATIVE),
                    it.getInt(DisplaySettings.KEY_VISIBLE_ITEMS, DisplaySettings.allItems) and DisplaySettings.allItems,
                )
            }
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
