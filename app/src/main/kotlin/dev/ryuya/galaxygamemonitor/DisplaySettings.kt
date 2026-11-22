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

internal object DisplaySettings {
    const val GAME_BOOSTER = "com.samsung.android.game.gametools"
    const val READ = "get_display_settings"
    const val KEY_PEAK_RELATIVE = "peak_relative"
    const val DEFAULT_PEAK_RELATIVE = false
    // ContentService otherwise batches notifications for background processes.
    private const val NOTIFY_NO_DELAY = 1 shl 15
    val uri: Uri = Uri.parse("content://dev.ryuya.galaxygamemonitor.settings/display")

    fun read(context: Context): Boolean = preferences(context).getBoolean(KEY_PEAK_RELATIVE, DEFAULT_PEAK_RELATIVE)

    @SuppressLint("WrongConstant")
    fun set(context: Context, peakRelative: Boolean) {
        if (read(context) == peakRelative) return
        preferences(context).edit().putBoolean(KEY_PEAK_RELATIVE, peakRelative).apply()
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
        return Bundle().apply {
            putBoolean(DisplaySettings.KEY_PEAK_RELATIVE, DisplaySettings.read(requireNotNull(context)))
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
    @Volatile private var cached: Boolean? = null
    private var lastKnown = DisplaySettings.DEFAULT_PEAK_RELATIVE
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

    fun isPeakRelative(): Boolean = !closed && (cached ?: readSetting())

    @Synchronized
    fun close() {
        closed = true
        if (observing) {
            observing = false
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    @Synchronized
    private fun readSetting(): Boolean {
        if (closed) return false
        cached?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < retryAfterMs) return lastKnown

        val current = try {
            if (!observing) {
                resolver.registerContentObserver(DisplaySettings.uri, false, observer)
                observing = true
            }
            resolver.call(DisplaySettings.uri, DisplaySettings.READ, null, null)
                ?.getBoolean(DisplaySettings.KEY_PEAK_RELATIVE, DisplaySettings.DEFAULT_PEAK_RELATIVE)
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
        if (lastReported != current) {
            lastReported = current
            Log.i("GalaxyGameMonitor", if (current) "CPU mode: peak-relative" else "CPU mode: busy time")
        }
        return current
    }
}
