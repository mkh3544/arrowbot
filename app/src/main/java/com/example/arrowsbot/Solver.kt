package com.example.arrowsbot

import android.graphics.Bitmap
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.max

/** Tunable values for different screen sizes and game themes. */
object Config {
    const val SCALE = 2
    const val TOP = 0.14f
    const val BOTTOM = 0.86f
    const val DIFF = 90
    const val MIN_AREA = 40
    const val CONT_X = 0.5f
    const val CONT_Y = 0.82f
}

object Solver {
    /** Returns a tap point for each detected arrow whose outward path is clear. */
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
        for (y in y0 until y1 step 3) {
            for (x in 0 until width step 3) {
                val key = pixels[y * width + x] and 0xF0F0F0
                histogram[key] = (histogram[key] ?: 0) + 1
            }
        }
        val background = histogram.maxByOrNull { it.value }?.key ?: return emptyList()
        val red = ((background shr 16) and 255) + 8
        val green = ((background shr 8) and 255) + 8
        val blue = (background and 255) + 8

        val ink = BooleanArray(width * height)
        for (y in y0 until y1) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val distance =
                    abs(((color shr 16) and 255) - red) +
                    abs(((color shr 8) and 255) - green) +
                    abs((color and 255) - blue)
                ink[y * width + x] = distance > Config.DIFF
            }
        }

        // Connected-component labeling: each component is treated as a candidate arrow.
        val labels = IntArray(width * height)
        val queue = IntArray(width * height)
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var tail = 0
        for (index in 0 until width * height) {
            if (!ink[index] || labels[index] != 0) continue
            val id = starts.size + 1
            val start = tail
            var head = tail
            queue[tail++] = index
            labels[index] = id
            while (head < tail) {
                val current = queue[head++]
                val x0 = current % width
                val yy0 = current / width
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x0 + dx
                    val ny = yy0 + dy
                    if (nx < 0 || ny < y0 || nx >= width || ny >= y1) continue
                    val neighbor = ny * width + nx
                    if (ink[neighbor] && labels[neighbor] == 0) {
                        labels[neighbor] = id
                        queue[tail++] = neighbor
                    }
                }
            }
            starts.add(start)
            ends.add(tail)
        }

        val maxArea = (y1 - y0) * width / 3
        val valid = BooleanArray(starts.size + 1)
        for (id in 1..starts.size) {
            val area = ends[id - 1] - starts[id - 1]
            valid[id] = area in Config.MIN_AREA..maxArea
        }

        val taps = ArrayList<Point>()
        for (id in 1..starts.size) {
            if (!valid[id]) continue
            val start = starts[id - 1]
            val end = ends[id - 1]
            val area = end - start
            var sumX = 0L
            var sumY = 0L
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            for (k in start until end) {
                val x = queue[k] % width
                val y = queue[k] / width
                sumX += x
                sumY += y
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
            val centerX = sumX.toFloat() / area
            val centerY = sumY.toFloat() / area

            fun farthest(fromX: Float, fromY: Float): Point {
                var best = Point(0, 0)
                var bestDistance = -1f
                for (k in start until end) {
                    val x = queue[k] % width
                    val y = queue[k] / width
                    val distance = (x - fromX) * (x - fromX) + (y - fromY) * (y - fromY)
                    if (distance > bestDistance) {
                        bestDistance = distance
                        best = Point(x, y)
                    }
                }
                return best
            }

            val endpoint1 = farthest(centerX, centerY)
            val endpoint2 = farthest(endpoint1.x.toFloat(), endpoint1.y.toFloat())
            val stroke = max(2f, area.toFloat() / ((maxX - minX) + (maxY - minY) + 1))
            val radius = max(6f, 3 * stroke)

            fun density(point: Point): Int {
                var count = 0
                for (k in start until end) {
                    val dx = queue[k] % width - point.x
                    val dy = queue[k] / width - point.y
                    if (dx * dx + dy * dy <= radius * radius) count++
                }
                return count
            }

            // The arrowhead generally has a denser cluster than its tail.
            val arrowHead = if (density(endpoint1) >= density(endpoint2)) endpoint1 else endpoint2
            var headX = 0f
            var headY = 0f
            var headCount = 0
            for (k in start until end) {
                val x = queue[k] % width
                val y = queue[k] / width
                val dx = x - arrowHead.x
                val dy = y - arrowHead.y
                if (dx * dx + dy * dy <= 9 * radius * radius) {
                    headX += x
                    headY += y
                    headCount++
                }
            }
            if (headCount == 0) continue
            headX /= headCount
            headY /= headCount

            val vx = arrowHead.x - headX
            val vy = arrowHead.y - headY
            val dx: Int
            val dy: Int
            if (abs(vx) >= abs(vy)) {
                dx = if (vx >= 0) 1 else -1
                dy = 0
            } else {
                dx = 0
                dy = if (vy >= 0) 1 else -1
            }

            // Cast a ray from the arrowhead toward the nearest screen edge.
            val halfWidth = (stroke * 0.5f).toInt()
            var x = arrowHead.x
            var y = arrowHead.y
            var blocked = false
            while (true) {
                x += dx
                y += dy
                if (x < 0 || y < 0 || x >= width || y >= height) break
                for (offset in -halfWidth..halfWidth) {
                    val qx = x + if (dy != 0) offset else 0
                    val qy = y + if (dx != 0) offset else 0
                    if (qx !in 0 until width || qy !in 0 until height) continue
                    val other = labels[qy * width + qx]
                    if (other != 0 && other != id && valid[other]) {
                        blocked = true
                        break
                    }
                }
                if (blocked) break
            }
            if (!blocked) {
                taps.add(Point(((arrowHead.x + headX) / 2 * scale).toInt(),
                    ((arrowHead.y + headY) / 2 * scale).toInt()))
            }
        }
        return taps
    }
}