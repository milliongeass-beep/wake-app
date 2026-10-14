package com.wakequest.app.logic

import kotlin.math.sqrt

/**
 * Photo-mission matcher: compares small blurred thumbnails with shift-tolerant correlation.
 * Works on a grayscale pixel array so it can be unit-tested without Android.
 */
object ImageMatch {
    private const val T = 32          // signature is a 32x32 thumbnail
    private const val MAX_SHIFT = 6   // tolerate ~19% framing difference in any direction

    /** Blurred 32x32 grayscale thumbnail encoded as 2048 hex chars. */
    fun hash(gray: IntArray, w: Int, h: Int): String {
        require(w > 0 && h > 0 && gray.size >= w * h) { "bad image size" }
        val small = blur(resize(gray, w, h, T, T))
        val sb = StringBuilder(T * T * 2)
        for (v in small) {
            val b = v.toInt().coerceIn(0, 255)
            sb.append(HEX[b shr 4]).append(HEX[b and 15])
        }
        return sb.toString()
    }

    /**
     * Best normalised correlation between the two thumbnails over small shifts.
     * Ignores overall brightness/contrast changes. ~1.0 = same view, unrelated views stay low.
     */
    fun similarity(h1: String, h2: String): Double {
        val a = decode(h1) ?: return 0.0
        val b = decode(h2) ?: return 0.0
        var best = 0.0
        for (dy in -MAX_SHIFT..MAX_SHIFT) for (dx in -MAX_SHIFT..MAX_SHIFT) {
            val c = correlation(a, b, dx, dy)
            if (c > best) best = c
        }
        return best
    }

    private const val HEX = "0123456789abcdef"

    private fun decode(s: String): DoubleArray? {
        if (s.length != T * T * 2) return null
        val out = DoubleArray(T * T)
        for (i in out.indices) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = (hi * 16 + lo).toDouble()
        }
        return out
    }

    /** Pearson correlation of a(x, y) with b(x + dx, y + dy) over the overlapping area. */
    private fun correlation(a: DoubleArray, b: DoubleArray, dx: Int, dy: Int): Double {
        val x0 = maxOf(0, -dx); val x1 = minOf(T, T - dx)
        val y0 = maxOf(0, -dy); val y1 = minOf(T, T - dy)
        val n = (x1 - x0) * (y1 - y0)
        if (n <= 0) return 0.0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val va = a[y * T + x]
            val vb = b[(y + dy) * T + (x + dx)]
            sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
        }
        val cov = sab - sa * sb / n
        val varA = saa - sa * sa / n
        val varB = sbb - sb * sb / n
        if (varA < 1e-6 * n || varB < 1e-6 * n) return 0.0
        return cov / kotlin.math.sqrt(varA * varB)
    }

    private fun blur(src: DoubleArray): DoubleArray {
        val out = DoubleArray(src.size)
        for (y in 0 until T) for (x in 0 until T) {
            var s = 0.0; var n = 0
            for (yy in maxOf(0, y - 1)..minOf(T - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(T - 1, x + 1)) {
                s += src[yy * T + xx]; n++
            }
            out[y * T + x] = s / n
        }
        return out
    }

    /** Standard deviation of brightness; very low = black/blank photo. */
    fun contrast(gray: IntArray): Double {
        if (gray.isEmpty()) return 0.0
        val mean = gray.average()
        var s = 0.0
        for (g in gray) s += (g - mean) * (g - mean)
        return sqrt(s / gray.size)
    }

    fun requiredSimilarity(difficulty: Int): Double = when (difficulty.coerceIn(1, 3)) {
        1 -> 0.60
        2 -> 0.70
        else -> 0.80
    }

    /** Area-average downscale to tw x th. */
    private fun resize(gray: IntArray, w: Int, h: Int, tw: Int, th: Int): DoubleArray {
        val out = DoubleArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = maxOf(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * w / tw)
                var sum = 0L
                var n = 0
                for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) {
                    sum += gray[y * w + x]; n++
                }
                out[ty * tw + tx] = if (n == 0) 0.0 else sum.toDouble() / n
            }
        }
        return out
    }
}
