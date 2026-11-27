package dev.ryuya.galaxygamemonitor

import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal class InGameMonitorSettings {
    private val main = Handler(Looper.getMainLooper())
    private val panels = WeakHashMap<ScrollView, WeakReference<Panel>>()
    @Volatile private var closed = false

    fun attachExisting() = onMain {
        if (closed) return@onMain
        for (root in WindowInspector.getGlobalWindowViews()) {
            val params = root.layoutParams as? WindowManager.LayoutParams
            if (params?.title?.contentEquals(WINDOW_TITLE) == true) attach(root)
        }
    }

    fun onWindowAdded(root: View) = onMain { attach(root) }

    fun close() {
        closed = true
        onMain {
            for (panel in panels.values.toList()) panel.get()?.remove()
            panels.clear()
        }
    }

    private fun attach(root: View) {
        if (closed) return
        runCatching {
            val settings = root.findViewById<View>(id(root, "include_settings_sub_menu")) ?: return
            val card = settings.findViewById<View>(id(root, "cl_container2")) ?: return
            val label = card.findViewById<TextView>(id(root, "tv_screenshot_resolution")) ?: return
            val stockContent = card.parent as? ViewGroup ?: return
            val scroll = stockContent.parent as? ScrollView ?: return
            if (panels[scroll]?.get() != null || scroll.parent !== settings || scroll.childCount != 1) return

            val strings = root.context.createPackageContext(BuildConfig.APPLICATION_ID, 0).resources
            val panel = Panel(settings, scroll, stockContent, card, label, strings)
            panel.attach()
            panels[scroll] = WeakReference(panel)
            Log.i(TAG, "In-game Game Booster monitor settings attached")
        }.onFailure { Log.w(TAG, "Could not attach in-game monitor settings", it) }
    }

    private class Panel(
        private val settingsView: View,
        private val scroll: ScrollView,
        private val stockContent: View,
        private val stockCard: View,
        private val stockLabel: TextView,
        private val strings: Resources,
    ) {
        private val stockParams = stockContent.layoutParams
        private val stockScrollHeight = scroll.layoutParams.height
        private val resizeEditor = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitEditorInPanel()
        }
        private val context = ContextThemeWrapper(scroll.context, android.R.style.Theme_Material)
        private val settingsLabel = strings.getString(R.string.monitor_in_game_action)
        private val gpuLabel = strings.getString(R.string.gpu_performance_heading)
        private val headerLabel = strings.getString(R.string.monitor_items_heading)
        private val wrapper = object : LinearLayout(context) {
            override fun onVisibilityAggregated(isVisible: Boolean) {
                super.onVisibilityAggregated(isVisible)
                if (!isVisible && editor.visibility == View.VISIBLE) post { if (!isShown) showStock() }
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        private val editor = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        private var pageEntry: TextView? = null
        private var goBack: (() -> Unit)? = null
        private val monitorEntry = entry(settingsLabel, ::openSettings)
        private val gpuEntry = entry(gpuLabel, ::openGpuSettings)
        private val monitorCard = card(monitorEntry)
        private val gpuCard = card(gpuEntry)
        private var stockScrollY = 0

        fun attach() {
            scroll.removeView(stockContent)
            wrapper.addView(stockContent, LinearLayout.LayoutParams(MATCH, WRAP))
            for (card in listOf(monitorCard, gpuCard)) {
                wrapper.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
            }
            scroll.addView(wrapper, stockParams)
        }

        fun remove() {
            showStock()
            if (wrapper.parent !== scroll) return
            scroll.removeView(wrapper)
            wrapper.removeView(stockContent)
            scroll.addView(stockContent, stockParams)
        }

        private fun card(entry: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = stockCard.background?.constantState?.newDrawable(stockCard.resources)?.mutate()
            backgroundTintList = stockCard.backgroundTintList
            addView(entry)
        }

        private fun entry(label: String, open: (TextView) -> Unit) = TextView(context).apply {
            textSize = 14f
            typeface = stockLabel.typeface
            setTextColor(stockLabel.textColors)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPaddingRelative(dp(18), 0, dp(18), 0)
            compoundDrawablePadding = dp(12)
            resetEntry(this, label)
            val selectable = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                background = context.getDrawable(selectable.resourceId)
            }
            isFocusable = true
            setOnClickListener {
                val back = goBack
                if (pageEntry === this && back != null) back() else open(this)
            }
        }

        private fun resetEntry(entry: TextView, label: String) {
            val arrow = context.getDrawable(android.R.drawable.arrow_down_float)?.mutate()?.apply {
                setTintList(stockLabel.textColors)
            }
            entry.text = label
            entry.contentDescription = null
            entry.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        }

        private fun openSettings(entry: TextView) {
            val content = runCatching {
                val settings = checkNotNull(DisplaySettings.readRemote(scroll.context))
                MonitorSettingsContent.create(context, strings, settings, ::save, ::showOrder)
            }.onFailure { unavailable("Unable to open in-game monitor settings", it) }.getOrNull() ?: return

            openPage(monitorCard, entry, headerLabel, content)
        }

        private fun openGpuSettings(entry: TextView) {
            val controls = GpuPerformanceControls(context, strings, showHeading = false, inGamePanel = true).apply {
                setPaddingRelative(dp(24), dp(12), dp(24), dp(8))
            }
            openPage(gpuCard, entry, gpuLabel, controls)
        }

        private fun openPage(card: LinearLayout, entry: TextView, title: String, content: View) {
            stockScrollY = scroll.scrollY
            stockContent.visibility = View.GONE
            monitorCard.visibility = if (card === monitorCard) View.VISIBLE else View.GONE
            gpuCard.visibility = if (card === gpuCard) View.VISIBLE else View.GONE
            pageEntry = entry
            card.addView(editor)
            editor.visibility = View.VISIBLE
            settingsView.addOnLayoutChangeListener(resizeEditor)
            fitEditorInPanel()
            showPage(title, content, ::showStock)
        }

        private fun fitEditorInPanel() {
            // The stock short list wraps its content. A longer page must stop at the panel's bottom.
            val params = scroll.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val height = settingsView.height - scroll.top - params.bottomMargin
            if (height > 0 && params.height != height) {
                params.height = height
                scroll.layoutParams = params
            }
        }

        private fun showPage(title: String, content: View, back: () -> Unit) {
            val entry = checkNotNull(pageEntry)
            goBack = back
            entry.text = "‹  $title"
            entry.contentDescription = strings.getString(R.string.monitor_in_game_back, title)
            entry.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            editor.removeAllViews()
            editor.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            scroll.scrollTo(0, 0)
        }

        private fun showOrder(order: List<MonitorItem>, saveOrder: (List<MonitorItem>) -> Unit) {
            val settingsPage = editor.getChildAt(0) ?: return
            val orderEditor = MonitorOrderDialog.createEditor(context, strings, order)
            val orderPage = orderEditor.view
            val actions = LinearLayout(context).apply { gravity = Gravity.END }
            val back = { showPage(headerLabel, settingsPage, ::showStock) }
            actions.addView(action(android.R.string.cancel, back))
            actions.addView(action(android.R.string.ok) {
                saveOrder(orderEditor.selectedOrder())
                if (editor.visibility == View.VISIBLE) back()
            })
            orderPage.addView(actions)
            showPage(strings.getString(R.string.monitor_order), orderPage, back)
        }

        private fun showStock() {
            if (editor.visibility != View.VISIBLE) return
            settingsView.removeOnLayoutChangeListener(resizeEditor)
            scroll.layoutParams = scroll.layoutParams.apply { height = stockScrollHeight }
            editor.visibility = View.GONE
            editor.removeAllViews()
            (editor.parent as? ViewGroup)?.removeView(editor)
            stockContent.visibility = View.VISIBLE
            monitorCard.visibility = View.VISIBLE
            gpuCard.visibility = View.VISIBLE
            pageEntry = null
            goBack = null
            resetEntry(monitorEntry, settingsLabel)
            resetEntry(gpuEntry, gpuLabel)
            scroll.post { if (editor.visibility != View.VISIBLE) scroll.scrollTo(0, stockScrollY) }
        }

        private fun save(changes: Bundle): Boolean =
            runCatching { DisplaySettings.updateRemote(scroll.context, changes) }.onFailure {
                showStock()
                unavailable("Unable to save in-game monitor settings", it)
            }.isSuccess

        private fun action(label: Int, click: () -> Unit) =
            Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                setText(label)
                setOnClickListener { click() }
            }

        private fun unavailable(message: String, failure: Throwable) {
            Log.w(TAG, message, failure)
            Toast.makeText(context, strings.getString(R.string.monitor_settings_unavailable), Toast.LENGTH_SHORT).show()
        }

        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    companion object {
        const val WINDOW_TITLE = "GameBooster Main Panel"
        private const val TAG = "GalaxyGameMonitor"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun id(root: View, name: String): Int =
            root.resources.getIdentifier(name, "id", DisplaySettings.GAME_BOOSTER)
    }
}
