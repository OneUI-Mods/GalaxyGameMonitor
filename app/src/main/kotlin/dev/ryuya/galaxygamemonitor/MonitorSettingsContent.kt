package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

internal object MonitorSettingsContent {
    fun create(
        context: Context,
        strings: Resources,
        config: MonitorSettings,
        save: (Bundle) -> Boolean,
        openOrder: (List<MonitorItem>, (List<MonitorItem>) -> Unit) -> Unit,
    ): LinearLayout {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(context, 24)
            setPaddingRelative(side, dp(context, 12), side, dp(context, 8))
        }
        val countLabel = TextView(context).apply {
            text = strings.getString(R.string.monitor_items_per_row_value, config.itemsPerRow)
        }
        content.addView(countLabel)
        content.addView(SeekBar(context).apply {
            min = 1
            max = MonitorItem.entries.size
            progress = config.itemsPerRow
            contentDescription = strings.getString(R.string.monitor_items_per_row)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, count: Int, fromUser: Boolean) {
                    countLabel.text = strings.getString(R.string.monitor_items_per_row_value, count)
                    if (fromUser) save(Bundle().apply { putInt(DisplaySettings.KEY_ITEMS_PER_ROW, count) })
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
        var itemOrder = config.order
        val switches = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val controls = MonitorItem.entries.associateWith { item ->
            makeSwitch(context, strings.getString(item.label), config.shows(item)) { visible ->
                save(Bundle().apply {
                    putInt(DisplaySettings.KEY_ITEM_BIT, item.bit)
                    putBoolean(DisplaySettings.KEY_ITEM_VISIBLE, visible)
                })
            }
        }
        fun arrangeSwitches() {
            switches.removeAllViews()
            for (item in itemOrder) switches.addView(controls.getValue(item))
        }
        content.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = strings.getString(R.string.monitor_order)
            minHeight = dp(context, 48)
            setOnClickListener {
                openOrder(itemOrder) { order ->
                    val saved = save(Bundle().apply {
                        putString(DisplaySettings.KEY_ITEM_ORDER, DisplaySettings.serializeOrder(order))
                    })
                    if (saved) {
                        itemOrder = order
                        arrangeSwitches()
                    }
                }
            }
        })
        content.addView(makeSwitch(context, strings.getString(R.string.monitor_show_in_capture), config.showInCapture) { visible ->
            save(Bundle().apply { putBoolean(DisplaySettings.KEY_SHOW_IN_CAPTURE, visible) })
        })
        arrangeSwitches()
        content.addView(switches)
        content.addView(makeSwitch(context, strings.getString(R.string.peak_relative_toggle), config.peakRelative) { enabled ->
            save(Bundle().apply { putBoolean(DisplaySettings.KEY_PEAK_RELATIVE, enabled) })
        })
        return content
    }

    private fun makeSwitch(context: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(context).apply {
            text = label
            minHeight = dp(context, 48)
            switchPadding = dp(context, 16)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> onChange(enabled) }
        }

    private fun dp(context: Context, dp: Int) =
        (dp * context.resources.displayMetrics.density).roundToInt()
}
