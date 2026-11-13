package com.example.arrowsbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 64, 40, 40)
        }
        layout.addView(TextView(this).apply {
            text = "ARROWS BOT\n\n1. Enable the accessibility service.\n2. Return here and press Start.\n3. Open the puzzle within 5 seconds.\n4. After the bot pauses, press Start / Next stage only when you are ready.\n\nPress Stop to end automation."
            textSize = 18f
            setPadding(0, 0, 0, 28)
        })

        fun addButton(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 })
        }

        addButton("Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("Start / Next stage") {
            val service = AutoService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Arrows Bot in Accessibility settings first.", Toast.LENGTH_LONG).show()
            } else {
                service.startDelayed()
                Toast.makeText(this, "Starting in 5 seconds. Open the puzzle.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }
        addButton("Stop") {
            AutoService.instance?.stop()
            Toast.makeText(this, "Bot stopped.", Toast.LENGTH_SHORT).show()
        }
        setContentView(layout)
    }
}