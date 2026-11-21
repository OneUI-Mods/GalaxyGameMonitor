package dev.ryuya.galaxygamemonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {
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

        val mode = findViewById<MaterialTextView>(R.id.mode)
        val toggle = findViewById<MaterialSwitch>(R.id.peak_relative)
        toggle.isChecked = DisplaySettings.read(this)
        fun showMode(peakRelative: Boolean) {
            mode.setText(if (peakRelative) R.string.mode_peak_relative else R.string.mode_stock)
        }
        showMode(toggle.isChecked)
        toggle.setOnCheckedChangeListener { _, peakRelative ->
            DisplaySettings.set(this, peakRelative)
            showMode(peakRelative)
        }
    }
}
