package com.wakequest.app.logic

import kotlin.math.PI
import kotlin.math.cos

enum class SoundType(val title: String, val emoji: String) {
    RAIN("Rain", "🌧️"),
    OCEAN("Ocean waves", "🌊"),
    BROWN("Brown noise", "🟤"),
    PINK("Pink noise", "🌸"),
    WHITE("White noise", "⚪"),
}

/** Procedurally generated sleep sounds — no audio files needed. Output is in [-1, 1]. */
class NoiseGenerator(
    private val type: SoundType,
    private val sampleRate: Int = 44100,
    seed: Long = System.nanoTime(),
) {
    private val rnd = java.util.Random(seed)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0
    private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
    private var brownState = 0.0
    private var drop = 0.0
    private var dropLp = 0.0
    private var t = 0L

    fun next(): Double {
        val white = rnd.nextDouble() * 2 - 1
        val v = when (type) {
            SoundType.WHITE -> white * 0.35
            SoundType.PINK -> pink(white)
            SoundType.BROWN -> brown(white)
            SoundType.RAIN -> {
                val base = pink(white) * 0.55
                if (rnd.nextDouble() < 0.0006) drop = 0.5 + rnd.nextDouble() * 0.5
                drop *= 0.996
                dropLp = dropLp * 0.7 + white * 0.3
                base + dropLp * drop * 0.6
            }
            SoundType.OCEAN -> {
                val period = sampleRate * 8.0
                val swell = 0.5 * (1 - cos(2 * PI * (t % period.toLong()) / period))
                brown(white) * (0.25 + 0.75 * swell)
            }
        }
        t++
        return v.coerceIn(-1.0, 1.0)
    }

    fun fill(buf: ShortArray) {
        for (i in buf.indices) buf[i] = (next() * 32767 * 0.9).toInt().toShort()
    }

    // Paul Kellet's pink-noise filter
    private fun pink(w: Double): Double {
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
        return out * 0.11
    }

    private fun brown(w: Double): Double {
        brownState = (brownState + 0.02 * w) / 1.02
        return brownState * 3.5
    }
}
