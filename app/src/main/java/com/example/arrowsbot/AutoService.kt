package com.example.arrowsbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent

class AutoService : AccessibilityService() {
    companion object {
        @Volatile var instance: AutoService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var idleCycles = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startDelayed() {
        stop()
        running = true
        idleCycles = 0
        handler.postDelayed({ cycle() }, 5_000L)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun cycle() {
        if (!running) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }

                if (bitmap == null) {
                    scheduleNext(1_200L)
                    return
                }

                val screenWidth = bitmap.width
                val screenHeight = bitmap.height
                val taps = try {
                    Solver.findTaps(bitmap)
                } catch (_: Exception) {
                    emptyList()
                } finally {
                    bitmap.recycle()
                }

                if (!running) return
                if (taps.isEmpty()) {
                    idleCycles++
                    if (idleCycles >= 2) {
                        // Use screenshot dimensions so coordinates match the captured display.
                        tap(screenWidth * Config.CONT_X, screenHeight * Config.CONT_Y)
                        idleCycles = 0
                    }
                    scheduleNext(1_200L)
                } else {
                    idleCycles = 0
                    taps.forEachIndexed { index, point ->
                        handler.postDelayed({
                            if (running) tap(point.x.toFloat(), point.y.toFloat())
                        }, index * 250L)
                    }
                    scheduleNext(taps.size * 250L + 1_200L)
                }
            }

            override fun onFailure(errorCode: Int) {
                scheduleNext(1_500L)
            }
        })
    }

    private fun scheduleNext(delay: Long) {
        if (running) handler.postDelayed({ cycle() }, delay)
    }

    private fun tap(x: Float, y: Float) {
        if (!running) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }
}