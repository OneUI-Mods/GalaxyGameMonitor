package dev.ryuya.galaxygamemonitor

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import dev.ryuya.galaxygamemonitor.SdhmsGpuOrphanCleanup.Status as CleanupStatus

internal object GpuPerformanceSettings {
    const val GOS = "com.samsung.android.game.gos"
    const val SDHMS = "com.sec.android.sdhms"
    const val READ = "get_gpu_performance"
    const val UPDATE = "update_gpu_performance"
    const val GOS_SESSION = "report_gos_session"
    const val SDHMS_CONFIG = "get_sdhms_config"
    const val SDHMS_SESSION = "report_sdhms_session"
    const val SDHMS_REFRESH = "refresh_sdhms_session"
    const val BOOSTER_SESSION = "report_booster_session"
    const val KEY_BYPASS = "bypass_gos_gpu_max"
    const val KEY_SDHMS = "keep_sdhms_gpu_thermal"
    const val KEY_SDHMS_TOKEN = "sdhms_token"
    const val KEY_SDHMS_CONNECTED = "sdhms_connected"
    const val KEY_SDHMS_CLEANUP = "sdhms_cleanup"
    const val KEY_SDHMS_MAX_MHZ = "sdhms_max_mhz"
    const val SDHMS_NO_REQUEST = 0
    const val SDHMS_UNKNOWN = -1
    const val KEY_MODE = "gmu_target_mode"
    const val KEY_GAME = "game_active"
    const val KEY_MATCHED = "gos_gpu_max_matched"
    const val KEY_STATUS = "gmu_status"
    const val KEY_EFFECTIVE = "gmu_effective_fps"
    const val KEY_GOS_CONNECTED = "gos_connected"
    const val KEY_GOS_TOKEN = "gos_token"
    const val KEY_PROVIDER_TOKEN = "gpu_provider_token"
    const val KEY_GOS_MAX_MHZ = "gos_max_mhz"
    val uri: Uri = Uri.withAppendedPath(DisplaySettings.uri, "gpu_performance")
    val sdhmsRefreshUri: Uri = Uri.withAppendedPath(DisplaySettings.uri, "sdhms_session")
    private val runtimeUri: Uri = Uri.withAppendedPath(uri, "runtime")

    enum class Mode(val fps: Int?, val label: Int) {
        SYSTEM(null, R.string.gmu_system),
        FPS_60(60, R.string.gmu_60),
        FPS_120(120, R.string.gmu_120),
    }

    enum class Status { IDLE, WAITING, ACTIVE, ROOT_REQUIRED, UNSUPPORTED, FAILED, BUSY }

    data class Config(val bypass: Boolean, val mode: Mode, val sdhms: Boolean)

    private data class GosState(val game: Boolean, val matched: Boolean, val ceiling: Int?)

    @Volatile private var gosState: GosState? = null
    private var gosToken: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private data class SdhmsState(val token: IBinder, val pid: Int, val ceiling: Int)
    private data class Cleanup(val pid: Int, val status: CleanupStatus)
    @Volatile private var cleanup: Cleanup? = null
    @Volatile private var sdhmsState: SdhmsState? = null
    private var sdhmsDeath: IBinder.DeathRecipient? = null
    private val providerToken by lazy { Binder() }

    @Synchronized
    fun reportSdhms(context: Context, pid: Int, report: Bundle) {
        val token = requireNotNull(report.getBinder(KEY_SDHMS_TOKEN))
        val previous = sdhmsState
        if (previous?.token != token) {
            val recipient = IBinder.DeathRecipient { clearSdhms(context, token) }
            if (runCatching { token.linkToDeath(recipient, 0) }.isFailure) return
            sdhmsDeath?.let { old -> runCatching { previous?.token?.unlinkToDeath(old, 0) } }
            sdhmsDeath = recipient
        }
        val next = SdhmsState(token, pid, report.getInt(KEY_SDHMS_MAX_MHZ, SDHMS_UNKNOWN))
        if (previous == next) return
        sdhmsState = next
        if (previous?.pid != pid) checkOrphanedSdhmsRequests(context)
        notifyRuntimeChange(context)
    }

    @Synchronized
    private fun checkOrphanedSdhmsRequests(context: Context, retry: Boolean = false) {
        if (!SdhmsGpuThermalHook.supportsDevice()) return
        val current = sdhmsState ?: return
        if (current.pid <= 0 || !current.token.isBinderAlive) return
        val previous = cleanup
        if (previous?.status == CleanupStatus.CHECKING) return
        if (previous?.pid == current.pid &&
            !(retry && previous.status in setOf(CleanupStatus.ROOT_REQUIRED, CleanupStatus.FAILED))
        ) return
        cleanup = Cleanup(current.pid, CleanupStatus.CHECKING)
        Thread({
            val status = SdhmsGpuOrphanCleanup.run(context, current.pid)
            finishOrphanCheck(context, current.pid, status)
        }, "GameMonitorSdhmsCleanup").start()
    }

    @Synchronized
    private fun finishOrphanCheck(context: Context, pid: Int, status: CleanupStatus) {
        cleanup = Cleanup(pid, status)
        Log.i("GalaxyGameMonitor", "SDHMS orphaned GPU request check: $status")
        checkOrphanedSdhmsRequests(context)
        notifyRuntimeChange(context)
    }

    @Synchronized
    private fun clearSdhms(context: Context, token: IBinder) {
        if (sdhmsState?.token != token) return
        sdhmsState = null
        sdhmsDeath = null
        notifyRuntimeChange(context)
    }

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

    @Synchronized
    fun read(context: Context): Config = preferences(context).let {
        val bypass = it.getBoolean(KEY_BYPASS, false)
        val mode = Mode.entries[it.getInt(KEY_MODE, 0).coerceIn(0, Mode.entries.lastIndex)]
        Config(bypass, mode, bypass && it.getBoolean(KEY_SDHMS, true))
    }

    fun gosConfig(context: Context): Bundle = Bundle().apply {
        putBoolean(KEY_BYPASS, read(context).bypass)
        putBinder(KEY_PROVIDER_TOKEN, providerToken)
    }

    fun sdhmsConfig(context: Context): Bundle = Bundle().apply {
        putBoolean(KEY_SDHMS, read(context).sdhms)
    }

    @Synchronized
    fun update(context: Context, changes: Bundle) {
        val prefs = preferences(context)
        val edit = prefs.edit()
        if (changes.containsKey(KEY_BYPASS) || changes.containsKey(KEY_SDHMS)) {
            val before = read(context)
            val bypass = changes.getBoolean(KEY_BYPASS, before.bypass)
            val override = changes.getBoolean(KEY_SDHMS).takeIf { changes.containsKey(KEY_SDHMS) }
            edit.putBoolean(KEY_BYPASS, bypass)
            edit.putBoolean(KEY_SDHMS, SdhmsGpuPolicy.afterUpdate(before.bypass, before.sdhms, bypass, override))
        }
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
        val sdhms = sdhmsState?.takeIf { it.token.isBinderAlive }
        return Bundle().apply {
            putBoolean(KEY_BYPASS, config.bypass)
            putBoolean(KEY_SDHMS, config.sdhms)
            putBoolean(KEY_SDHMS_CONNECTED, sdhms != null)
            sdhms?.let { session ->
                putInt(KEY_SDHMS_MAX_MHZ, session.ceiling)
                cleanup?.takeIf { it.pid == session.pid }?.let { putInt(KEY_SDHMS_CLEANUP, it.status.ordinal) }
            }
            putInt(KEY_MODE, config.mode.ordinal)
            putBoolean(KEY_GAME, state.game || gos?.game == true)
            putBoolean(KEY_MATCHED, config.bypass && gos?.matched == true)
            putBoolean(KEY_GOS_CONNECTED, gos != null)
            gos?.ceiling?.let { putInt(KEY_GOS_MAX_MHZ, it) }
            putInt(KEY_STATUS, state.status.ordinal)
            state.effective?.let { putInt(KEY_EFFECTIVE, it) }
        }
    }

    fun readRemote(context: Context, refreshSdhms: Boolean = false): Bundle {
        val resolver = context.contentResolver
        if (refreshSdhms) {
            val snapshot = runCatching { DisplaySettings.callRemote(resolver, SDHMS_REFRESH) }.getOrNull()
            if (snapshot?.containsKey(KEY_MODE) == true) return snapshot
        }
        return checkNotNull(DisplaySettings.callRemote(resolver, READ))
    }

    fun updateRemote(context: Context, changes: Bundle): Bundle =
        checkNotNull(DisplaySettings.callRemote(context.contentResolver, UPDATE, changes))

    fun refreshSdhms(context: Context) {
        checkOrphanedSdhmsRequests(context, retry = true)
        if (sdhmsState?.token?.isBinderAlive != true) notifyChange(context, sdhmsRefreshUri)
    }

    fun notifyRuntimeChange(context: Context) = notifyChange(context, runtimeUri)

    @SuppressLint("WrongConstant")
    private fun notifyChange(context: Context, changed: Uri) {
        context.contentResolver.notifyChange(changed, null, ContentResolver.NOTIFY_UPDATE or (1 shl 15))
    }
}
