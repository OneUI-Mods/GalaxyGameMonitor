package dev.ryuya.galaxygamemonitor

import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {
    private var refreshSettings: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        refreshSettings?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_screen)) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        val settings = DisplaySettings.read(this)
        var refreshing = false
        val perRowLabel = findViewById<MaterialTextView>(R.id.items_per_row_value)
        val perRow = findViewById<Slider>(R.id.items_per_row)
        perRow.valueFrom = 1f
        perRow.valueTo = MonitorItem.entries.size.toFloat()
        perRow.stepSize = 1f
        perRow.value = settings.itemsPerRow.toFloat()
        perRowLabel.text = getString(R.string.monitor_items_per_row_value, settings.itemsPerRow)
        perRow.addOnChangeListener { _, value, fromUser ->
            val count = value.toInt()
            perRowLabel.text = getString(R.string.monitor_items_per_row_value, count)
            if (fromUser) DisplaySettings.setItemsPerRow(this, count)
        }

        val items = findViewById<LinearLayout>(R.id.monitor_items)
        val itemSwitches = MonitorItem.entries.associateWith { item ->
            val itemSwitch = layoutInflater.inflate(R.layout.monitor_item_switch, items, false) as MaterialSwitch
            itemSwitch.setText(item.label)
            itemSwitch.isChecked = settings.shows(item)
            itemSwitch.setOnCheckedChangeListener { _, visible ->
                if (!refreshing) DisplaySettings.setVisible(this, item, visible)
            }
            itemSwitch
        }
        fun arrangeSwitches(order: List<MonitorItem>) {
            if (order.withIndex().all { (index, item) -> items.getChildAt(index) === itemSwitches[item] }) return
            items.removeAllViews()
            for (item in order) items.addView(itemSwitches.getValue(item))
        }
        arrangeSwitches(settings.order)
        findViewById<MaterialButton>(R.id.reorder_monitor_items).setOnClickListener {
            MonitorOrderDialog.show(this, resources, DisplaySettings.read(this).order) { order ->
                DisplaySettings.setOrder(this, order)
                arrangeSwitches(order)
            }
        }

        val mode = findViewById<MaterialTextView>(R.id.mode)
        val toggle = findViewById<MaterialSwitch>(R.id.peak_relative)
        toggle.isChecked = settings.peakRelative
        fun showMode(peakRelative: Boolean) {
            mode.setText(if (peakRelative) R.string.mode_peak_relative else R.string.mode_kernel_usage)
        }
        showMode(toggle.isChecked)
        toggle.setOnCheckedChangeListener { _, peakRelative ->
            if (!refreshing) DisplaySettings.set(this, peakRelative)
            showMode(peakRelative)
        }
        refreshSettings = {
            val latest = DisplaySettings.read(this)
            refreshing = true
            perRow.value = latest.itemsPerRow.toFloat()
            toggle.isChecked = latest.peakRelative
            arrangeSwitches(latest.order)
            for ((item, itemSwitch) in itemSwitches) itemSwitch.isChecked = latest.shows(item)
            refreshing = false
        }

        val reload = findViewById<MaterialButton>(R.id.reload_game_booster)
        val reloadStatus = findViewById<MaterialTextView>(R.id.reload_status)
        GameBoosterReload.state.observe(this) { state ->
            reload.isEnabled = state != GameBoosterReload.State.RUNNING
            reloadStatus.visibility = if (state == null) View.GONE else View.VISIBLE
            state?.let { reloadStatus.setText(it.message) }
        }
        reload.setOnClickListener {
            val primaryUser = getSystemService(UserManager::class.java)?.isSystemUser == true
            GameBoosterReload.request(primaryUser)
        }
    }
}
