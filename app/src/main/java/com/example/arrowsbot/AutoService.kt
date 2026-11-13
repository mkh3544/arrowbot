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
        @Volatile var statusMessage: String = "Stopped"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var targetGamePackage: String? = null

    private val launcherAndSystemPackages = setOf(
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        statusMessage = "Accessibility connected"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in launcherAndSystemPackages) {
            stopWithStatus("Stopped: game is no longer in the foreground")
            return
        }

        val current = targetGamePackage
        if (current == null) {
            targetGamePackage = pkg
            statusMessage = "Game detected: $pkg"
        } else if (pkg != current) {
            stopWithStatus("Stopped: game is no longer in the foreground")
        }
    }

    override fun onInterrupt() {
        stopWithStatus("Stopped: accessibility interrupted")
    }

    override fun onDestroy() {
        stopWithStatus("Stopped")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startDelayed() {
        stop()
        targetGamePackage = null
        running = true
        statusMessage = "Waiting for the game… open the game now"
        handler.postDelayed({
            if (running) {
                if (targetGamePackage == null) {
                    stopWithStatus("No game detected. Open the game and press Start again.")
                } else {
                    cycle()
                }
            }
        }, 5_000L)
    }

    fun stop() {
        running = false
        targetGamePackage = null
        handler.removeCallbacksAndMessages(null)
        statusMessage = "Stopped by user"
    }

    private fun stopWithStatus(message: String) {
        running = false
        targetGamePackage = null
        handler.removeCallbacksAndMessages(null)
        statusMessage = message
    }

    private fun cycle() {
        if (!running || targetGamePackage == null) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                if (!running) {
                    result.hardwareBuffer.close()
                    return
                }
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }

                if (bitmap == null) {
                    stopWithStatus("Stopped: could not read the game screen")
                    return
                }

                val candidate = try {
                    Solver.findTaps(bitmap).firstOrNull()
                } catch (_: Exception) {
                    null
                } finally {
                    bitmap.recycle()
                }

                if (!running) return
                if (candidate == null) {
                    // No blind/fixed-coordinate tap and no automatic next-level action.
                    stopWithStatus("Paused: no safe move detected or stage finished. Check the board, then press Start / Next stage.")
                    return
                }

                statusMessage = "Making one move, then checking the board again…"
                tap(candidate.x.toFloat(), candidate.y.toFloat())
                handler.postDelayed({
                    if (running) cycle()
                }, 1_000L)
            }

            override fun onFailure(errorCode: Int) {
                stopWithStatus("Stopped: screenshot failed ($errorCode)")
            }
        })
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
