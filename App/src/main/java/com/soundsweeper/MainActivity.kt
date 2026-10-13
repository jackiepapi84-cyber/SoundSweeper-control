
package com.soundsweeper

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "SoundSweeper Control"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Bluetooth status: Not checked"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        layout.addView(title)
        layout.addView(status)

        setContentView(layout)
    }
}
