package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import dev.ryuya.galaxygamemonitor.GpuPerformanceSettings.Mode
import dev.ryuya.galaxygamemonitor.GpuPerformanceSettings.Status
import dev.ryuya.galaxygamemonitor.SdhmsGpuOrphanCleanup.Status as CleanupStatus

internal class GpuPerformanceControls(
    context: Context,
    private val strings: Resources,
    showHeading: Boolean = true,
    private val inGamePanel: Boolean = false,
) : LinearLayout(context) {
    private val bypass = Switch(context).apply { text = strings.getString(R.string.gos_gpu_max_bypass) }
    private val gosStatus = TextView(context)
    private val sdhms = Switch(context).apply { text = strings.getString(R.string.sdhms_gpu_thermal) }
    private val sdhmsStatus = TextView(context)
    private val gosCeiling = TextView(context)
    private val sdhmsCeiling = TextView(context)
    private val sdhmsCleanupStatus = TextView(context).apply { visibility = View.GONE }
    private val actualCeiling = TextView(context)
    private val gmuCurrent = TextView(context)
    private val modes = RadioGroup(context).apply { orientation = VERTICAL }
    private val gmuStatus = TextView(context)
    private var rendering = false
    private var observing = false
    private var workerThread: HandlerThread? = null
    private var reportedGmuFps: Int? = null
    private var hardwareGmuFps: Int? = null
    private val modeButtons = Mode.entries.associateWith { mode ->
        RadioButton(context).apply {
            id = View.generateViewId()
            text = strings.getString(mode.label)
            modes.addView(this)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var refreshPending = false
    private val refreshView = Runnable {
        refreshPending = false
        if (observing) refresh()
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (observing && !refreshPending) refreshPending = main.post(refreshView)
        }
    }

    init {
        orientation = VERTICAL
        val spacing = (16 * resources.displayMetrics.density).toInt()
        if (showHeading) {
            addView(TextView(context).apply {
                setPaddingRelative(0, spacing, 0, 0)
                text = strings.getString(R.string.gpu_performance_heading)
            })
        }
        addView(bypass)
        addView(gosStatus)
        addView(gosCeiling)
        addView(sdhmsCeiling)
        addView(actualCeiling)
        addView(sdhms)
        addView(sdhmsStatus)
        addView(sdhmsCleanupStatus)
        addView(TextView(context).apply {
            setPaddingRelative(0, spacing, 0, 0)
            text = strings.getString(R.string.gmu_target)
        })
        addView(gmuCurrent)
        addView(modes)
        addView(gmuStatus)
        bypass.setOnCheckedChangeListener { _, checked ->
            if (!rendering) update(Bundle().apply { putBoolean(GpuPerformanceSettings.KEY_BYPASS, checked) })
        }
        sdhms.setOnCheckedChangeListener { _, checked ->
            if (!rendering) update(Bundle().apply { putBoolean(GpuPerformanceSettings.KEY_SDHMS, checked) })
        }
        modes.setOnCheckedChangeListener { _, id ->
            val mode = modeButtons.entries.singleOrNull { it.value.id == id }?.key
            if (!rendering && mode != null) update(Bundle().apply { putInt(GpuPerformanceSettings.KEY_MODE, mode.ordinal) })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observing = runCatching {
            context.contentResolver.registerContentObserver(GpuPerformanceSettings.uri, true, observer)
        }.isSuccess
        refresh(refreshSdhms = SdhmsGpuThermalHook.supportsDevice())
        startReadings()
    }

    override fun onDetachedFromWindow() {
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        main.removeCallbacks(refreshView)
        refreshPending = false
        workerThread?.quitSafely()
        workerThread = null
        super.onDetachedFromWindow()
    }

    private fun startReadings() {
        val thread = HandlerThread("GameMonitorGpuSettings", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        workerThread = thread
        val worker = Handler(thread.looper)
        var lastCeiling: GpuHardwareReadings.Ceiling? = null
        var lastGmuSample: Long? = null
        var lastGmuFps: Int? = null
        var first = true
        val sample = object : Runnable {
            override fun run() {
                val ceiling = GpuHardwareReadings.ceiling()
                val now = SystemClock.elapsedRealtime()
                val readGmu = lastGmuSample?.let { now - it >= 12_000L } != false
                val gmuFps = if (readGmu) GpuHardwareReadings.effectiveGmuFps() else null
                val ceilingChanged = first || lastCeiling != ceiling
                val gmuChanged = readGmu && (first || lastGmuFps != gmuFps)
                if (readGmu) {
                    lastGmuSample = now
                    lastGmuFps = gmuFps
                }
                lastCeiling = ceiling
                first = false
                if (ceilingChanged || gmuChanged) {
                    post {
                        if (workerThread !== thread) return@post
                        if (ceilingChanged) actualCeiling.text = when {
                            ceiling == null -> strings.getString(R.string.gpu_ceiling_unavailable)
                            ceiling.aggregated -> strings.getString(R.string.gpu_ceiling_actual, ceiling.mhz)
                            else -> strings.getString(R.string.gpu_ceiling_shared, ceiling.mhz)
                        }
                        if (gmuChanged) {
                            hardwareGmuFps = gmuFps
                            renderGmuCurrent()
                        }
                    }
                }
                worker.postDelayed(this, 2_000L)
            }
        }
        worker.post(sample)
    }

    private fun renderGmuCurrent() {
        val fps = reportedGmuFps ?: hardwareGmuFps
        gmuCurrent.text = if (fps == null) strings.getString(R.string.gmu_current_unavailable)
            else strings.getString(R.string.gmu_effective, fps)
    }

    private fun refresh(refreshSdhms: Boolean = false) =
        render(runCatching { GpuPerformanceSettings.readRemote(context, refreshSdhms) }.getOrNull())

    private fun update(changes: Bundle) =
        render(runCatching { GpuPerformanceSettings.updateRemote(context, changes) }.getOrNull())

    private fun render(state: Bundle?) {
        rendering = true
        bypass.isEnabled = state != null
        val bypassEnabled = state?.getBoolean(GpuPerformanceSettings.KEY_BYPASS) == true
        val sdhmsConnected = state?.getBoolean(GpuPerformanceSettings.KEY_SDHMS_CONNECTED) == true
        sdhms.isEnabled = bypassEnabled && SdhmsGpuThermalHook.supportsDevice()
        val sdhmsRequested = state?.getInt(GpuPerformanceSettings.KEY_SDHMS_MAX_MHZ, GpuPerformanceSettings.SDHMS_UNKNOWN)
        sdhmsCeiling.text = when {
            sdhmsConnected && sdhmsRequested != null && sdhmsRequested > 0 ->
                strings.getString(R.string.sdhms_ceiling_requested, sdhmsRequested)
            sdhmsConnected && sdhmsRequested == GpuPerformanceSettings.SDHMS_NO_REQUEST ->
                strings.getString(R.string.sdhms_ceiling_no_request)
            else -> strings.getString(R.string.sdhms_ceiling_unavailable)
        }
        val cleanup = state?.getInt(GpuPerformanceSettings.KEY_SDHMS_CLEANUP, -1)
            ?.let(CleanupStatus.entries::getOrNull)
        val cleanupLabel = when (cleanup) {
            CleanupStatus.CLEARED -> R.string.sdhms_cleanup_cleared
            CleanupStatus.ROOT_REQUIRED -> R.string.sdhms_cleanup_root_required
            CleanupStatus.FAILED -> R.string.sdhms_cleanup_failed
            else -> null
        }
        sdhmsCleanupStatus.text = cleanupLabel?.let(strings::getString).orEmpty()
        sdhmsCleanupStatus.visibility = if (cleanupLabel == null) View.GONE else View.VISIBLE
        modeButtons.values.forEach { it.isEnabled = state != null }
        if (state == null) {
            gosStatus.text = strings.getString(R.string.monitor_settings_unavailable)
            sdhmsStatus.text = ""
            gosCeiling.text = strings.getString(R.string.gos_ceiling_unavailable)
            gmuStatus.text = ""
            reportedGmuFps = null
        } else {
            val game = inGamePanel || state.getBoolean(GpuPerformanceSettings.KEY_GAME)
            val connected = state.getBoolean(GpuPerformanceSettings.KEY_GOS_CONNECTED)
            val requested = state.getInt(GpuPerformanceSettings.KEY_GOS_MAX_MHZ)
            gosCeiling.text = when {
                requested > 0 -> strings.getString(R.string.gos_ceiling_requested, requested)
                connected -> strings.getString(R.string.gos_ceiling_no_request)
                else -> strings.getString(R.string.gos_ceiling_unavailable)
            }
            bypass.isChecked = bypassEnabled
            sdhms.isChecked = state.getBoolean(GpuPerformanceSettings.KEY_SDHMS)
            sdhmsStatus.text = when {
                !SdhmsGpuThermalHook.supportsDevice() -> strings.getString(R.string.sdhms_gpu_unsupported)
                sdhms.isChecked && !sdhmsConnected ->
                    strings.getString(R.string.sdhms_gpu_disconnected)
                else -> ""
            }
            sdhmsStatus.visibility = if (sdhmsStatus.text.isEmpty()) View.GONE else View.VISIBLE
            val gosStatusLabel = when {
                !bypassEnabled -> null
                !connected -> R.string.gos_gpu_max_disconnected
                state.getBoolean(GpuPerformanceSettings.KEY_MATCHED) -> null
                game -> R.string.gos_gpu_max_waiting
                else -> R.string.gpu_waiting_for_game
            }
            gosStatus.text = gosStatusLabel?.let(strings::getString).orEmpty()
            val mode = Mode.entries.getOrElse(state.getInt(GpuPerformanceSettings.KEY_MODE)) { Mode.SYSTEM }
            modes.check(modeButtons.getValue(mode).id)
            val status = Status.entries.getOrElse(state.getInt(GpuPerformanceSettings.KEY_STATUS)) { Status.IDLE }
            reportedGmuFps = state.getInt(GpuPerformanceSettings.KEY_EFFECTIVE).takeIf { it > 0 }
            gmuStatus.text = when (status) {
                Status.ACTIVE -> ""
                Status.WAITING -> strings.getString(R.string.gmu_waiting)
                Status.BUSY -> strings.getString(R.string.gmu_busy)
                Status.ROOT_REQUIRED -> strings.getString(R.string.gmu_root_required)
                Status.UNSUPPORTED -> strings.getString(R.string.gmu_unsupported)
                Status.FAILED -> strings.getString(R.string.gmu_failed)
                Status.IDLE -> when {
                    mode == Mode.SYSTEM -> ""
                    game -> strings.getString(R.string.gmu_waiting)
                    else -> strings.getString(R.string.gmu_waiting_for_game)
                }
            }
        }
        gosStatus.visibility = if (gosStatus.text.isEmpty()) View.GONE else View.VISIBLE
        renderGmuCurrent()
        rendering = false
    }
}
