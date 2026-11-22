package dev.ryuya.galaxygamemonitor

import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal object MonitorOrderDialog {
    fun show(
        context: Context,
        strings: Resources,
        order: List<MonitorItem>,
        save: (List<MonitorItem>) -> Unit,
    ): AlertDialog {
        val draft = order.toMutableList()
        val width = (48 * context.resources.displayMetrics.density).roundToInt()
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(width / 2, 0, width / 4, 0)
        }
        val buttons = linkedMapOf<MonitorItem, Pair<Button, Button>>()

        fun refreshButtons() {
            for ((index, item) in draft.withIndex()) {
                val (up, down) = buttons.getValue(item)
                up.isEnabled = index > 0
                down.isEnabled = index < draft.lastIndex
            }
        }

        fun move(item: MonitorItem, delta: Int) {
            val from = draft.indexOf(item)
            val destination = from + delta
            if (destination !in draft.indices) return
            draft.removeAt(from)
            draft.add(destination, item)
            val row = rows.getChildAt(from)
            rows.removeViewAt(from)
            rows.addView(row, destination)
            refreshButtons()
        }

        for (item in order) {
            val label = strings.getString(item.label)
            val name = TextView(context).apply {
                text = label
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                gravity = Gravity.CENTER_VERTICAL
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = width
            }
            val up = arrow(context, "↑", strings.getString(R.string.monitor_move_up, label)) { move(item, -1) }
            val down = arrow(context, "↓", strings.getString(R.string.monitor_move_down, label)) { move(item, 1) }
            buttons[item] = up to down
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(up, LinearLayout.LayoutParams(width, width))
            row.addView(down, LinearLayout.LayoutParams(width, width))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshButtons()

        return AlertDialog.Builder(context)
            .setTitle(strings.getString(R.string.monitor_order))
            .setView(ScrollView(context).apply { addView(rows) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> save(draft.toList()) }
            .show()
    }

    private fun arrow(context: Context, glyph: String, label: String, move: () -> Unit) =
        Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = glyph
            textSize = 20f
            contentDescription = label
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { move() }
        }
}
