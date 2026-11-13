package com.example.arrowsbot

import android.graphics.Bitmap
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.sqrt

object Config {
    const val SCALE = 2
    const val TOP = 0.08f
    const val BOTTOM = 0.94f
    const val MIN_AREA = 35
    const val COLOR_DISTANCE = 105
}

object Solver {
    private data class Component(
        val id: Int,
        val pixels: IntArray,
        val cx: Float,
        val cy: Float,
        val axisX: Float,
        val axisY: Float,
        val minProjection: Float,
        val maxProjection: Float,
        val meanWidth: Float
    )

    /** Returns at most one high-confidence move. Ambiguous arrows are deliberately ignored. */
    fun findTaps(src: Bitmap): List<Point> {
        val scale = Config.SCALE
        val width = (src.width / scale).coerceAtLeast(1)
        val height = (src.height / scale).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, width, height, true)
        val pixels = IntArray(width * height)
        try {
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (scaled !== src) scaled.recycle()
        }

        val y0 = (height * Config.TOP).toInt().coerceIn(0, height - 1)
        val y1 = (height * Config.BOTTOM).toInt().coerceIn(y0 + 1, height)
        val histogram = HashMap<Int, Int>()
        for (y in y0 until y1 step 2) for (x in 0 until width step 2) {
            val c = pixels[y * width + x]
            val key = (c and 0x00F0F0F0)
            histogram[key] = (histogram[key] ?: 0) + 1
        }
        val bg = histogram.maxByOrNull { it.value }?.key ?: return emptyList()
        val br = (bg shr 16) and 255
        val bgc = (bg shr 8) and 255
        val bb = bg and 255

        val ink = BooleanArray(width * height)
        for (y in y0 until y1) for (x in 0 until width) {
            val c = pixels[y * width + x]
            val d = abs(((c shr 16) and 255) - br) +
                abs(((c shr 8) and 255) - bgc) +
                abs((c and 255) - bb)
            ink[y * width + x] = d >= Config.COLOR_DISTANCE
        }

        val labels = IntArray(width * height)
        val queue = IntArray(width * height)
        val components = ArrayList<Component>()
        var nextComponentId = 1
        for (start in 0 until width * height) {
            if (!ink[start] || labels[start] != 0) continue
            var head = 0
            var tail = 0
            val id = nextComponentId++
            queue[tail++] = start
            labels[start] = id
            while (head < tail) {
                val p = queue[head++]
                val px = p % width
                val py = p / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in 0 until width || ny !in y0 until y1) continue
                    val ni = ny * width + nx
                    if (ink[ni] && labels[ni] == 0) {
                        labels[ni] = id
                        queue[tail++] = ni
                    }
                }
            }
            if (tail < Config.MIN_AREA) continue
            val pts = queue.copyOfRange(0, tail)
            val component = makeComponent(id, pts, width) ?: continue
            components.add(component)
        }
        if (components.isEmpty()) return emptyList()

        // A candidate is accepted only when exactly one end has a clear arrowhead profile.
        for (c in components) {
            val length = c.maxProjection - c.minProjection
            if (length < 10f || c.meanWidth < 1.2f || length / c.meanWidth < 2.0f) continue
            val minHead = headProfile(c, width, fromMin = true)
            val maxHead = headProfile(c, width, fromMin = false)
            if (minHead == maxHead) continue
            val dir = if (minHead) -1f else 1f
            val dx = c.axisX * dir
            val dy = c.axisY * dir
            val tipProjection = if (minHead) c.minProjection else c.maxProjection
            val tipX = c.cx + c.axisX * tipProjection
            val tipY = c.cy + c.axisY * tipProjection

            if (!pathIsClear(tipX, tipY, dx, dy, c, labels, width, height)) continue

            // Tap near the shaft centre, away from the arrowhead and board boundaries.
            val midProjection = (c.minProjection + c.maxProjection) * 0.5f
            val tapX = ((c.cx + c.axisX * midProjection) * scale).toInt()
            val tapY = ((c.cy + c.axisY * midProjection) * scale).toInt()
            return listOf(Point(tapX.coerceIn(1, src.width - 2), tapY.coerceIn(1, src.height - 2)))
        }
        return emptyList()
    }

    private fun makeComponent(id: Int, pts: IntArray, width: Int): Component? {
        var sx = 0.0
        var sy = 0.0
        for (p in pts) { sx += p % width; sy += p / width }
        val cx = (sx / pts.size).toFloat()
        val cy = (sy / pts.size).toFloat()
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (p in pts) {
            val dx = p % width - cx
            val dy = p / width - cy
            xx += dx * dx; yy += dy * dy; xy += dx * dy
        }
        val angle = 0.5 * kotlin.math.atan2(2.0 * xy, xx - yy)
        var ax = kotlin.math.cos(angle).toFloat()
        var ay = kotlin.math.sin(angle).toFloat()
        if (!ax.isFinite() || !ay.isFinite()) return null
        var minP = Float.POSITIVE_INFINITY
        var maxP = Float.NEGATIVE_INFINITY
        var widthSum = 0f
        val bins = HashMap<Int, Int>()
        for (p in pts) {
            val projection = (p % width - cx) * ax + (p / width - cy) * ay
            minP = minOf(minP, projection); maxP = maxOf(maxP, projection)
            val b = kotlin.math.floor(projection).toInt()
            bins[b] = (bins[b] ?: 0) + 1
        }
        widthSum = bins.values.sum().toFloat() / bins.size.coerceAtLeast(1)
        return Component(id, pts, cx, cy, ax, ay, minP, maxP, widthSum)
    }

    private fun headProfile(c: Component, width: Int, fromMin: Boolean): Boolean {
        val len = c.maxProjection - c.minProjection
        fun band(a: Float, b: Float): Float {
            val counts = HashMap<Int, Int>()
            for (p in c.pixels) {
                val projection = (p % width - c.cx) * c.axisX + (p / width - c.cy) * c.axisY
                val t = if (fromMin) (projection - c.minProjection) / len
                    else (c.maxProjection - projection) / len
                if (t >= a && t < b) {
                    val key = kotlin.math.floor(projection).toInt()
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            return if (counts.isEmpty()) 0f else counts.values.average().toFloat()
        }
        val nearTip = band(0.02f, 0.16f)
        val headBase = band(0.16f, 0.34f)
        val shaft = band(0.42f, 0.68f)
        return nearTip > 0f && shaft > 0f &&
            headBase >= nearTip * 1.35f &&
            headBase >= shaft * 1.20f
    }

    private fun pathIsClear(
        tipX: Float, tipY: Float, dx: Float, dy: Float, c: Component,
        labels: IntArray, width: Int, height: Int
    ): Boolean {
        val radius = (c.meanWidth * 0.48f).coerceAtLeast(1.2f)
        var step = 1
        var x = tipX
        var y = tipY
        while (true) {
            x += dx
            y += dy
            if (x < 0f || y < 0f || x >= width || y >= height) return true
            val px = x.toInt()
            val py = y.toInt()
            val perpendicularX = -dy
            val perpendicularY = dx
            var offset = -radius.toInt()
            while (offset <= radius.toInt()) {
                val qx = (px + perpendicularX * offset).toInt()
                val qy = (py + perpendicularY * offset).toInt()
                if (qx in 0 until width && qy in 0 until height) {
                    val other = labels[qy * width + qx]
                    if (other != 0 && other != c.id) return false
                }
                offset++
            }
            step++
            if (step > width + height) return false
        }
    }
}
