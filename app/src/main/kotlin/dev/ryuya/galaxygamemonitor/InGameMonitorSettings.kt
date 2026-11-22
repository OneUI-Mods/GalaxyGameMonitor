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
        stockCard: View,
        stockLabel: TextView,
        private val strings: Resources,
    ) {
        private val stockParams = stockContent.layoutParams
        private val stockScrollHeight = scroll.layoutParams.height
        private val resizeEditor = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitEditorInPanel()
        }
        private val context = ContextThemeWrapper(scroll.context, android.R.style.Theme_Material)
        private val settingsLabel = strings.getString(R.string.monitor_in_game_action)
        private val headerLabel = strings.getString(R.string.monitor_items_heading)
        private val wrapper = object : LinearLayout(context) {
            override fun onVisibilityAggregated(isVisible: Boolean) {
                super.onVisibilityAggregated(isVisible)
                if (!isVisible && editor.visibility == View.VISIBLE) post { if (!isShown) showStock() }
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        private val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = stockCard.background?.constantState?.newDrawable(stockCard.resources)?.mutate()
            backgroundTintList = stockCard.backgroundTintList
        }
        private val editor = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        private val entryArrow = context.getDrawable(android.R.drawable.arrow_down_float)?.mutate()?.apply {
            setTintList(stockLabel.textColors)
        }
        private var goBack: (() -> Unit)? = null
        private val openButton = TextView(context).apply {
            text = settingsLabel
            textSize = 14f
            typeface = stockLabel.typeface
            setTextColor(stockLabel.textColors)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPaddingRelative(dp(18), 0, dp(18), 0)
            compoundDrawablePadding = dp(12)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, entryArrow, null)
            val selectable = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                background = context.getDrawable(selectable.resourceId)
            }
            isFocusable = true
            setOnClickListener {
                val back = goBack
                if (back == null) openSettings() else back()
            }
        }
        private var stockScrollY = 0

        fun attach() {
            card.addView(openButton)
            card.addView(editor)
            scroll.removeView(stockContent)
            wrapper.addView(stockContent, LinearLayout.LayoutParams(MATCH, WRAP))
            wrapper.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
            scroll.addView(wrapper, stockParams)
        }

        fun remove() {
            showStock()
            if (wrapper.parent !== scroll) return
            scroll.removeView(wrapper)
            wrapper.removeView(stockContent)
            scroll.addView(stockContent, stockParams)
        }

        private fun openSettings() {
            val content = runCatching {
                val settings = checkNotNull(DisplaySettings.readRemote(scroll.context))
                MonitorSettingsContent.create(context, strings, settings, ::save, ::showOrder)
            }.onFailure { unavailable("Unable to open in-game monitor settings", it) }.getOrNull() ?: return

            stockScrollY = scroll.scrollY
            stockContent.visibility = View.GONE
            editor.visibility = View.VISIBLE
            settingsView.addOnLayoutChangeListener(resizeEditor)
            fitEditorInPanel()
            showPage(headerLabel, content, ::showStock)
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
            goBack = back
            openButton.text = "‹  $title"
            openButton.contentDescription = strings.getString(R.string.monitor_in_game_back, title)
            openButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
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
            stockContent.visibility = View.VISIBLE
            goBack = null
            openButton.text = settingsLabel
            openButton.contentDescription = null
            openButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, entryArrow, null)
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
