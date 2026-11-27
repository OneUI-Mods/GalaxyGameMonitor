package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
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
) : LinearLayout(context) {
    private val bypass = Switch(context).apply { text = strings.getString(R.string.gos_gpu_max_bypass) }
    private val gosStatus = TextView(context)
    private val modes = RadioGroup(context).apply { orientation = VERTICAL }
    private val gmuStatus = TextView(context)
    private var rendering = false
    private var observing = false
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
        addView(TextView(context).apply {
            setPaddingRelative(0, spacing, 0, 0)
            text = strings.getString(R.string.gmu_target)
        })
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
            context.contentResolver.registerContentObserver(GpuPerformanceSettings.uri, false, observer)
        }.isSuccess
        refresh()
    }

    override fun onDetachedFromWindow() {
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        super.onDetachedFromWindow()
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
            gmuStatus.text = ""
        } else {
            val enabled = state.getBoolean(GpuPerformanceSettings.KEY_BYPASS)
            val game = state.getBoolean(GpuPerformanceSettings.KEY_GAME)
            bypass.isChecked = enabled
            gosStatus.text = strings.getString(when {
                !enabled -> R.string.gos_gpu_max_system
                state.getBoolean(GpuPerformanceSettings.KEY_MATCHED) -> R.string.gos_gpu_max_active
                game -> R.string.gos_gpu_max_waiting
                else -> R.string.gpu_waiting_for_game
            })
            val mode = Mode.entries.getOrElse(state.getInt(GpuPerformanceSettings.KEY_MODE)) { Mode.SYSTEM }
            modes.check(modeButtons.getValue(mode).id)
            gmuStatus.text = when (Status.entries.getOrElse(state.getInt(GpuPerformanceSettings.KEY_STATUS)) { Status.IDLE }) {
                Status.ACTIVE -> strings.getString(R.string.gmu_effective, state.getInt(GpuPerformanceSettings.KEY_EFFECTIVE))
                Status.WAITING -> strings.getString(R.string.gmu_waiting)
                Status.ROOT_REQUIRED -> strings.getString(R.string.gmu_root_required)
                Status.UNSUPPORTED -> strings.getString(R.string.gmu_unsupported)
                Status.FAILED -> strings.getString(R.string.gmu_failed)
                Status.IDLE -> if (mode == Mode.SYSTEM) "" else strings.getString(R.string.gpu_waiting_for_game)
            }
        }
        rendering = false
    }
}
