package dev.ryuya.galaxygamemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal class GameBoosterMonitorSettings(private val toolbarClass: Class<*>) {
    private val main = Handler(Looper.getMainLooper())
    private val actionId = View.generateViewId()
    private val menus = WeakHashMap<Menu, Unit>()
    @Volatile private var closed = false
    @Volatile var activity: WeakReference<Activity>? = null
        private set
    private var dialog: AlertDialog? = null
    private var orderDialog: AlertDialog? = null

    fun attach(screen: Activity) {
        onMain {
            if (closed || screen.isDestroyed || screen.isFinishing) return@onMain
            // The support action bar populates its menu at the first frame after onResume.
            val decor = screen.window.decorView
            decor.postOnAnimation { decor.post { addAction(screen) } }
        }
    }

    private fun addAction(screen: Activity) {
        if (closed || screen.isDestroyed || screen.isFinishing) return
        runCatching {
            val toolbar = findToolbar(screen.window.decorView) ?: return@runCatching
            val menu = toolbarClass.getMethod("getMenu").invoke(toolbar) as? Menu ?: return@runCatching
            activity = WeakReference(screen)
            if (menu.findItem(actionId) != null) return@runCatching
            val strings = screen.createPackageContext(BuildConfig.APPLICATION_ID, 0).resources
            menu.add(Menu.NONE, actionId, Menu.NONE, strings.getString(R.string.monitor_settings_action))
                .setOnMenuItemClickListener {
                    open(screen)
                    true
                }.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            menus[menu] = Unit
            Log.i(TAG, "Game Booster monitor settings action attached")
        }.onFailure { Log.w(TAG, "Game Booster monitor settings unavailable", it) }
    }

    fun close() {
        closed = true
        onMain {
            orderDialog?.dismiss()
            orderDialog = null
            dialog?.dismiss()
            dialog = null
            activity = null
            for (menu in menus.keys.toList()) menu.removeItem(actionId)
            menus.clear()
        }
    }

    private fun open(screen: Activity) {
        if (closed || dialog?.isShowing == true) return
        runCatching { showDialog(screen) }.onFailure {
            Log.w(TAG, "Unable to open monitor settings", it)
            showUnavailable(screen)
        }
    }

    private fun showDialog(screen: Activity) {
        val resources = screen.createPackageContext(BuildConfig.APPLICATION_ID, 0).resources
        val config = DisplaySettings.readRemote(screen) ?: error("Monitor settings provider unavailable")
        val content = LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(screen, 24)
            setPaddingRelative(side, dp(screen, 12), side, dp(screen, 8))
        }
        val countLabel = TextView(screen).apply {
            text = resources.getString(R.string.monitor_items_per_row_value, config.itemsPerRow)
        }
        content.addView(countLabel)
        content.addView(SeekBar(screen).apply {
            min = 1
            max = MonitorItem.entries.size
            progress = config.itemsPerRow
            contentDescription = resources.getString(R.string.monitor_items_per_row)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, count: Int, fromUser: Boolean) {
                    countLabel.text = resources.getString(R.string.monitor_items_per_row_value, count)
                    if (fromUser) save(screen, Bundle().apply { putInt(DisplaySettings.KEY_ITEMS_PER_ROW, count) })
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
        var itemOrder = config.order
        val switches = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
        val controls = MonitorItem.entries.associateWith { item ->
            makeSwitch(switches, resources.getString(item.label), config.shows(item)) { visible ->
                save(screen, Bundle().apply {
                    putInt(DisplaySettings.KEY_ITEM_BIT, item.bit)
                    putBoolean(DisplaySettings.KEY_ITEM_VISIBLE, visible)
                })
            }
        }
        fun arrangeSwitches() {
            switches.removeAllViews()
            for (item in itemOrder) switches.addView(controls.getValue(item))
        }
        content.addView(Button(screen, null, android.R.attr.borderlessButtonStyle).apply {
            text = resources.getString(R.string.monitor_order)
            minHeight = dp(context, 48)
            setOnClickListener {
                orderDialog = MonitorOrderDialog.show(screen, resources, itemOrder) { order ->
                    val saved = save(screen, Bundle().apply {
                        putString(DisplaySettings.KEY_ITEM_ORDER, DisplaySettings.serializeOrder(order))
                    })
                    if (saved) {
                        itemOrder = order
                        arrangeSwitches()
                    }
                }.also { panel ->
                    panel.setOnDismissListener { if (orderDialog === panel) orderDialog = null }
                }
            }
        })
        content.addView(makeSwitch(content, resources.getString(R.string.monitor_show_in_capture), config.showInCapture) { visible ->
            save(screen, Bundle().apply { putBoolean(DisplaySettings.KEY_SHOW_IN_CAPTURE, visible) })
        })
        arrangeSwitches()
        content.addView(switches)
        content.addView(makeSwitch(content, resources.getString(R.string.peak_relative_toggle), config.peakRelative) { enabled ->
            save(screen, Bundle().apply { putBoolean(DisplaySettings.KEY_PEAK_RELATIVE, enabled) })
        })
        val scroll = ScrollView(screen).apply { addView(content) }
        dialog = AlertDialog.Builder(screen)
            .setTitle(resources.getString(R.string.monitor_items_heading))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create().also { panel ->
                panel.setOnDismissListener { if (dialog === panel) dialog = null }
                panel.show()
            }
    }

    private fun makeSwitch(content: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(content.context).apply {
            text = label
            minHeight = dp(context, 48)
            switchPadding = dp(context, 16)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> onChange(enabled) }
        }

    private fun save(screen: Activity, changes: Bundle): Boolean {
        if (closed) return false
        return runCatching { DisplaySettings.updateRemote(screen, changes) }.onFailure {
            Log.w(TAG, "Unable to save monitor settings", it)
            dialog?.dismiss()
            showUnavailable(screen)
        }.isSuccess
    }

    private fun showUnavailable(screen: Activity) {
        val message = runCatching {
            screen.createPackageContext(BuildConfig.APPLICATION_ID, 0).getString(R.string.monitor_settings_unavailable)
        }.getOrNull() ?: return
        Toast.makeText(screen, message, Toast.LENGTH_SHORT).show()
    }

    private fun findToolbar(view: View): View? {
        if (toolbarClass.isInstance(view) && view.isShown) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findToolbar(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private companion object {
        const val TAG = "GalaxyGameMonitor"
        fun dp(context: Context, dp: Int) = (dp * context.resources.displayMetrics.density).roundToInt()
    }
}
