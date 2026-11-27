package dev.ryuya.galaxygamemonitor

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder

internal object GpuPerformanceSettings {
    const val GOS = "com.samsung.android.game.gos"
    const val READ = "get_gpu_performance"
    const val UPDATE = "update_gpu_performance"
    const val GOS_SESSION = "report_gos_session"
    const val BOOSTER_SESSION = "report_booster_session"
    const val KEY_BYPASS = "bypass_gos_gpu_max"
    const val KEY_MODE = "gmu_target_mode"
    const val KEY_GAME = "game_active"
    const val KEY_MATCHED = "gos_gpu_max_matched"
    const val KEY_STATUS = "gmu_status"
    const val KEY_EFFECTIVE = "gmu_effective_fps"
    const val KEY_GOS_CONNECTED = "gos_connected"
    const val KEY_GOS_TOKEN = "gos_token"
    const val KEY_GOS_MAX_MHZ = "gos_max_mhz"
    val uri: Uri = Uri.withAppendedPath(DisplaySettings.uri, "gpu_performance")
    private val runtimeUri: Uri = Uri.withAppendedPath(uri, "runtime")

    enum class Mode(val fps: Int?, val label: Int) {
        SYSTEM(null, R.string.gmu_system),
        FPS_60(60, R.string.gmu_60),
        FPS_120(120, R.string.gmu_120),
    }

    enum class Status { IDLE, WAITING, ACTIVE, ROOT_REQUIRED, UNSUPPORTED, FAILED }

    data class Config(val bypass: Boolean, val mode: Mode)

    private data class GosState(val game: Boolean, val matched: Boolean, val ceiling: Int?)

    @Volatile private var gosState: GosState? = null
    private var gosToken: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Synchronized
    fun reportGos(context: Context, report: Bundle) {
        val token = requireNotNull(report.getBinder(KEY_GOS_TOKEN))
        if (token != gosToken) {
            val recipient = IBinder.DeathRecipient { clearGos(context, token) }
            if (runCatching { token.linkToDeath(recipient, 0) }.isFailure) return
            deathRecipient?.let { old -> runCatching { gosToken?.unlinkToDeath(old, 0) } }
            gosToken = token
            deathRecipient = recipient
        }
        val next = GosState(
            report.getBoolean(KEY_GAME),
            report.getBoolean(KEY_MATCHED),
            report.getInt(KEY_GOS_MAX_MHZ).takeIf { it > 0 },
        )
        if (gosState == next) return
        gosState = next
        notifyRuntimeChange(context)
    }

    @Synchronized
    private fun clearGos(context: Context, token: IBinder) {
        if (gosToken != token) return
        gosToken = null
        deathRecipient = null
        gosState = null
        notifyRuntimeChange(context)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("gpu_performance", Context.MODE_PRIVATE)

    fun read(context: Context): Config = preferences(context).let {
        Config(it.getBoolean(KEY_BYPASS, false), Mode.entries[it.getInt(KEY_MODE, 0).coerceIn(0, Mode.entries.lastIndex)])
    }

    @Synchronized
    fun update(context: Context, changes: Bundle) {
        val prefs = preferences(context)
        val edit = prefs.edit()
        if (changes.containsKey(KEY_BYPASS)) edit.putBoolean(KEY_BYPASS, changes.getBoolean(KEY_BYPASS))
        if (changes.containsKey(KEY_MODE)) {
            val index = changes.getInt(KEY_MODE, -1)
            require(index in Mode.entries.indices)
            edit.putInt(KEY_MODE, index)
        }
        edit.apply()
        notifyChange(context, uri)
    }

    fun snapshot(context: Context): Bundle {
        val config = read(context)
        val state = GmuPerformanceController.state
        val gos = gosState
        return Bundle().apply {
            putBoolean(KEY_BYPASS, config.bypass)
            putInt(KEY_MODE, config.mode.ordinal)
            putBoolean(KEY_GAME, state.game || gos?.game == true)
            putBoolean(KEY_MATCHED, config.bypass && gos?.matched == true)
            putBoolean(KEY_GOS_CONNECTED, gos != null)
            gos?.ceiling?.let { putInt(KEY_GOS_MAX_MHZ, it) }
            putInt(KEY_STATUS, state.status.ordinal)
            state.effective?.let { putInt(KEY_EFFECTIVE, it) }
        }
    }

    fun readRemote(context: Context): Bundle =
        checkNotNull(context.contentResolver.call(uri, READ, null, null))

    fun updateRemote(context: Context, changes: Bundle): Bundle =
        checkNotNull(context.contentResolver.call(uri, UPDATE, null, changes))

    fun notifyRuntimeChange(context: Context) = notifyChange(context, runtimeUri)

    @SuppressLint("WrongConstant")
    private fun notifyChange(context: Context, changed: Uri) {
        context.contentResolver.notifyChange(changed, null, ContentResolver.NOTIFY_UPDATE or (1 shl 15))
    }
}
