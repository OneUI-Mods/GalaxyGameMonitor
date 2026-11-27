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

internal class GpuPerformanceControls(
    context: Context,
    private val strings: Resources,
    showHeading: Boolean = true,
    private val inGamePanel: Boolean = false,
) : LinearLayout(context) {
    private val bypass = Switch(context).apply { text = strings.getString(R.string.gos_gpu_max_bypass) }
    private val gosStatus = TextView(context)
    private val gosCeiling = TextView(context)
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
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
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
        addView(actualCeiling)
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
        refresh()
        startReadings()
    }

    override fun onDetachedFromWindow() {
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        workerThread?.quitSafely()
        workerThread = null
        super.onDetachedFromWindow()
    }

    private fun startReadings() {
        val thread = HandlerThread("GameMonitorGpuSettings", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        workerThread = thread
        val worker = Handler(thread.looper)
        var lastGmuSample: Long? = null
        val sample = object : Runnable {
            override fun run() {
                val ceiling = GpuHardwareReadings.ceiling()
                val now = SystemClock.elapsedRealtime()
                val readGmu = lastGmuSample?.let { now - it >= 12_000L } != false
                val gmuFps = if (readGmu) GpuHardwareReadings.effectiveGmuFps() else null
                if (readGmu) lastGmuSample = now
                post {
                    if (workerThread !== thread) return@post
                    actualCeiling.text = when {
                        ceiling == null -> strings.getString(R.string.gpu_ceiling_unavailable)
                        ceiling.aggregated -> strings.getString(R.string.gpu_ceiling_actual, ceiling.mhz)
                        else -> strings.getString(R.string.gpu_ceiling_shared, ceiling.mhz)
                    }
                    if (readGmu) {
                        hardwareGmuFps = gmuFps
                        renderGmuCurrent()
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

    private fun refresh() = render(runCatching { GpuPerformanceSettings.readRemote(context) }.getOrNull())

    private fun update(changes: Bundle) =
        render(runCatching { GpuPerformanceSettings.updateRemote(context, changes) }.getOrNull())

    private fun render(state: Bundle?) {
        rendering = true
        bypass.isEnabled = state != null
        modeButtons.values.forEach { it.isEnabled = state != null }
        if (state == null) {
            gosStatus.text = strings.getString(R.string.monitor_settings_unavailable)
            gosCeiling.text = strings.getString(R.string.gos_ceiling_unavailable)
            gmuStatus.text = ""
            reportedGmuFps = null
        } else {
            val enabled = state.getBoolean(GpuPerformanceSettings.KEY_BYPASS)
            val game = inGamePanel || state.getBoolean(GpuPerformanceSettings.KEY_GAME)
            val connected = state.getBoolean(GpuPerformanceSettings.KEY_GOS_CONNECTED)
            val requested = state.getInt(GpuPerformanceSettings.KEY_GOS_MAX_MHZ)
            gosCeiling.text = when {
                requested > 0 -> strings.getString(R.string.gos_ceiling_requested, requested)
                connected -> strings.getString(R.string.gos_ceiling_no_request)
                else -> strings.getString(R.string.gos_ceiling_unavailable)
            }
            bypass.isChecked = enabled
            gosStatus.text = strings.getString(when {
                !enabled -> R.string.gos_gpu_max_system
                !connected -> R.string.gos_gpu_max_disconnected
                state.getBoolean(GpuPerformanceSettings.KEY_MATCHED) -> R.string.gos_gpu_max_active
                game -> R.string.gos_gpu_max_waiting
                else -> R.string.gpu_waiting_for_game
            })
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
        renderGmuCurrent()
        rendering = false
    }
}
