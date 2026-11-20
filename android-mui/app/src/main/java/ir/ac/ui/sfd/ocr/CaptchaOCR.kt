package ir.ac.ui.sfd.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class CaptchaOCR(context: Context) {
    // Templates map: digit -> 2D List of Int (0 or 1)
    private val templates = mutableMapOf<String, List<List<Int>>>()

    init {
        try {
            context.assets.open("templates.json").use { inputStream ->
                val reader = InputStreamReader(inputStream, "UTF-8")
                val type = object : TypeToken<Map<String, List<List<Int>>>>() {}.type
                val map: Map<String, List<List<Int>>> = Gson().fromJson(reader, type)
                templates.putAll(map)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class Result(val code: String, val confidence: Double)

    fun solve(bitmap: Bitmap): Result? {
        if (templates.isEmpty()) return null

        val w = bitmap.width
        val h = bitmap.height
        if (w < 160 || h < 35) return null

        // 1. Grayscale & Threshold (> 190)
        val thresh = Array(h) { IntArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                thresh[y][x] = if (lum > 190) 1 else 0
            }
        }

        // 2. MaxFilter(3) - 3x3 Morphological Dilation
        val closed = Array(h) { IntArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var maxVal = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx in 0 until w && thresh[ny][nx] == 1) {
                                maxVal = 1
                                break
                            }
                        }
                    }
                    if (maxVal == 1) break
                }
                closed[y][x] = maxVal
            }
        }

        // 3. Digit slots (X ranges)
        val slots = listOf(
            76 to 96,
            97 to 117,
            117 to 138,
            138 to 158
        )

        val predictedDigits = StringBuilder()
        val scores = mutableListOf<Double>()

        for ((x1, x2) in slots) {
            if (x2 > w) continue

            // Candidate 2D slice for y in 8..32 (height 25)
            val candRaw = mutableListOf<List<Int>>()
            for (y in 8 until 33.coerceAtMost(h)) {
                val row = mutableListOf<Int>()
                for (x in x1 until x2) {
                    row.add(closed[y][x])
                }
                candRaw.add(row)
            }

            // Find active bounding box
            val activeY = candRaw.indices.filter { y -> candRaw[y].any { it == 1 } }
            if (activeY.isEmpty()) return null
            val minY = activeY.minOrNull() ?: 0
            val maxY = activeY.maxOrNull() ?: 0

            val activeX = mutableListOf<Int>()
            for (x in 0 until (x2 - x1)) {
                if (candRaw.indices.any { y -> candRaw[y][x] == 1 }) {
                    activeX.add(x)
                }
            }
            if (activeX.isEmpty()) return null
            val minX = activeX.minOrNull() ?: 0
            val maxX = activeX.maxOrNull() ?: 0

            // Trim candidate to bounding box
            val cand = mutableListOf<List<Int>>()
            for (y in minY..maxY) {
                cand.add(candRaw[y].subList(minX, maxX + 1))
            }

            val ch = cand.size
            val cw = if (ch > 0) cand[0].size else 0
            if (ch == 0 || cw == 0) return null

            var bestDigit = "0"
            var bestIou = -1.0

            for ((digit, templ) in templates) {
                val th = templ.size
                val tw = if (th > 0) templ[0].size else 0
                var scoreMax = 0.0

                for (dy in -2..2) {
                    for (dx in -2..2) {
                        var inter = 0
                        var union = 0
                        val maxYBound = maxOf(ch, th)
                        val maxXBound = maxOf(cw, tw)

                        for (y in 0 until maxYBound) {
                            for (x in 0 until maxXBound) {
                                val v1 = if (y in 0 until ch && x in 0 until cw) cand[y][x] else 0
                                val ty = y + dy
                                val tx = x + dx
                                val v2 = if (ty in 0 until th && tx in 0 until tw) templ[ty][tx] else 0

                                if (v1 == 1 && v2 == 1) inter++
                                if (v1 == 1 || v2 == 1) union++
                            }
                        }
                        val iou = if (union > 0) inter.toDouble() / union else 0.0
                        if (iou > scoreMax) {
                            scoreMax = iou
                        }
                    }
                }

                if (scoreMax > bestIou) {
                    bestIou = scoreMax
                    bestDigit = digit
                }
            }

            predictedDigits.append(bestDigit)
            scores.add(bestIou)
        }

        val avgConf = if (scores.isNotEmpty()) scores.average() else 0.0
        return Result(predictedDigits.toString(), avgConf)
    }
}
