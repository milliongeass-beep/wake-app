package com.wakequest.app

import com.wakequest.app.logic.AlarmTime
import com.wakequest.app.logic.ImageMatch
import com.wakequest.app.logic.MathGen
import com.wakequest.app.logic.NoiseGenerator
import com.wakequest.app.logic.Phrases
import com.wakequest.app.logic.ShakeDetector
import com.wakequest.app.logic.SoundType
import com.wakequest.app.logic.StatsMath
import com.wakequest.app.logic.StepDetector
import com.wakequest.app.logic.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AlarmTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, berlin)

    @Test fun laterTodayRingsToday() {
        val now = at(2026, 10, 14, 5, 30) // Wednesday
        assertEquals(at(2026, 10, 14, 7, 0), AlarmTime.nextTrigger(now, 7, 0, emptySet()))
    }

    @Test fun earlierTimeRingsTomorrow() {
        val now = at(2026, 10, 14, 8, 0)
        assertEquals(at(2026, 10, 15, 7, 0), AlarmTime.nextTrigger(now, 7, 0, emptySet()))
    }

    @Test fun exactlyNowRingsTomorrow() {
        val now = at(2026, 10, 14, 7, 0)
        assertEquals(at(2026, 10, 15, 7, 0), AlarmTime.nextTrigger(now, 7, 0, emptySet()))
    }

    @Test fun secondsAfterTheMinuteRingsTomorrow() {
        val now = at(2026, 10, 14, 7, 0, 30)
        assertEquals(at(2026, 10, 15, 7, 0), AlarmTime.nextTrigger(now, 7, 0, emptySet()))
    }

    @Test fun weekdaysSkipTheWeekend() {
        val fridayEvening = at(2026, 10, 16, 20, 0)
        assertEquals(DayOfWeek.FRIDAY, fridayEvening.dayOfWeek)
        val next = AlarmTime.nextTrigger(fridayEvening, 7, 0, setOf(1, 2, 3, 4, 5))
        assertEquals(at(2026, 10, 19, 7, 0), next)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    }

    @Test fun singleWeekdayIsUpToSevenDaysAway() {
        val wedAfter = at(2026, 10, 14, 9, 0)
        assertEquals(at(2026, 10, 21, 7, 0), AlarmTime.nextTrigger(wedAfter, 7, 0, setOf(3)))
    }

    @Test fun springForwardGapStillRings() {
        // 2026-03-29 02:30 does not exist in Berlin; java.time shifts it to 03:30.
        val now = at(2026, 3, 28, 23, 0)
        val next = AlarmTime.nextTrigger(now, 2, 30, emptySet())
        assertEquals(29, next.dayOfMonth)
        assertEquals(3, next.hour)
        assertTrue(next.isAfter(now))
    }

    @Test fun fallBackRingsOnce() {
        val now = at(2026, 10, 24, 23, 0)
        val next = AlarmTime.nextTrigger(now, 7, 0, emptySet())
        assertEquals(at(2026, 10, 25, 7, 0), next)
    }

    @Test fun alwaysInTheFutureForEveryMinuteOfADay() {
        val start = at(2026, 10, 14, 0, 0)
        for (m in 0 until 24 * 60 step 7) {
            val now = start.plusMinutes(m.toLong())
            for (days in listOf(emptySet(), setOf(1, 2, 3, 4, 5), setOf(6, 7), setOf(7))) {
                val next = AlarmTime.nextTrigger(now, 6, 45, days)
                assertTrue(next.isAfter(now))
                assertTrue(next.isBefore(now.plusDays(8)))
                if (days.isNotEmpty()) assertTrue(next.dayOfWeek.value in days)
            }
        }
    }
}

class MissionLogicTest {
    @Test fun mathAnswersAreCorrect() {
        val rnd = Random(42)
        repeat(3000) { i ->
            val d = i % 3 + 1
            val p = MathGen.generate(d, rnd)
            val parts = p.text.split(" ")
            val computed = if (parts.size == 3) parts[0].toInt() + parts[2].toInt()
            else parts[0].toInt() * parts[2].toInt() + parts[4].toInt()
            assertEquals(p.text, computed, p.answer)
            assertTrue(p.answer > 0)
        }
    }

    @Test fun typingIgnoresCaseSpacingAndPunctuation() {
        assertTrue(TextMatch.matches("I get up, I show up!", "i get up i show up"))
        assertTrue(TextMatch.matches("Rise and shine", "  rise   AND shine. "))
        assertFalse(TextMatch.matches("Rise and shine", "rise and shin"))
        assertFalse(TextMatch.matches("Rise and shine", ""))
    }

    @Test fun everyPhraseIsTypeable() {
        for (d in 1..3) repeat(50) {
            val p = Phrases.pick(d)
            assertTrue(TextMatch.matches(p, p.lowercase()))
            assertTrue(TextMatch.normalize(p).isNotEmpty())
        }
    }

    @Test fun shakeNeedsRealForceAndCooldown() {
        val det = ShakeDetector(2.2f)
        assertFalse(det.onSample(0f, 0f, 9.8f, 0))           // resting phone
        assertTrue(det.onSample(25f, 0f, 9.8f, 1000))         // ~2.7 g
        assertFalse(det.onSample(25f, 0f, 9.8f, 1100))        // too soon
        assertTrue(det.onSample(0f, 25f, 9.8f, 1300))
    }

    @Test fun walkingIsCountedAndRestingIsNot() {
        val det = StepDetector(1.6f)
        var steps = 0
        // 10 s of walking: ~2 steps per second, 3.5 m/s² bounce, sampled at 50 Hz
        for (i in 0 until 500) {
            val t = i * 20L
            val bounce = 3.5f * sin(2 * PI * 2.0 * t / 1000.0).toFloat()
            if (det.onSample(0.3f, 9.81f + bounce, 0.5f, t)) steps++
        }
        assertTrue("counted $steps", steps in 17..22)

        val still = StepDetector(1.6f)
        var noise = 0
        val rnd = Random(1)
        for (i in 0 until 500) {
            if (still.onSample(rnd.nextFloat() * 0.2f, 9.81f + rnd.nextFloat() * 0.3f, 0f, i * 20L)) noise++
        }
        assertEquals(0, noise)
    }

    @Test fun stdDev() {
        assertNull(StatsMath.stdDev(listOf(1.0)))
        assertEquals(0.0, StatsMath.stdDev(listOf(5.0, 5.0, 5.0))!!, 1e-9)
        assertEquals(1.0, StatsMath.stdDev(listOf(1.0, 3.0))!!, 1e-9)
    }
}

class ImageMatchTest {
    private val w = 64
    private val h = 64

    private fun scene(shiftX: Int = 0, brightness: Int = 0, noise: Int = 0, seed: Int = 0, shiftY: Int = 0): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w + shiftX
            val y = i / w + shiftY
            // a "room": window, door, shelf
            var v = 60
            if (x in 8..24 && y in 6..26) v = 220
            if (x in 40..54 && y in 20..63) v = 110
            if (y in 34..37) v = 170
            v += (x + y) / 4
            (v + brightness + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0).coerceIn(0, 255)
        }
    }

    @Test fun identicalPhotosMatchPerfectly() {
        val a = ImageMatch.hash(scene(), w, h)
        assertEquals(1.0, ImageMatch.similarity(a, a), 1e-9)
        assertEquals(2048, a.length)
    }

    @Test fun sameSpotWithNoiseLightAndSmallShiftPassesNormal() {
        val ref = ImageMatch.hash(scene(), w, h)
        val attempt = ImageMatch.hash(scene(shiftX = 2, brightness = 25, noise = 12, seed = 7), w, h)
        val sim = ImageMatch.similarity(ref, attempt)
        assertTrue("similarity $sim", sim >= ImageMatch.requiredSimilarity(2))
    }

    @Test fun reshootFromASlightlyDifferentPositionPassesHard() {
        val ref = ImageMatch.hash(scene(), w, h)
        val attempt = ImageMatch.hash(scene(shiftX = 7, shiftY = -4, brightness = -30, noise = 10, seed = 2), w, h)
        val sim = ImageMatch.similarity(ref, attempt)
        assertTrue("similarity $sim", sim >= ImageMatch.requiredSimilarity(3))
    }

    @Test fun unrelatedImagesFailEvenOnEasy() {
        val ref = ImageMatch.hash(scene(), w, h)
        val rnd = Random(99)
        val other = IntArray(w * h) { rnd.nextInt(256) }
        val sim = ImageMatch.similarity(ref, ImageMatch.hash(other, w, h))
        assertTrue("similarity $sim", sim < ImageMatch.requiredSimilarity(1))

        val flipped = scene().reversedArray()
        val sim2 = ImageMatch.similarity(ref, ImageMatch.hash(flipped, w, h))
        assertTrue("similarity $sim2", sim2 < ImageMatch.requiredSimilarity(1))
    }

    @Test fun blankPhotoHasNoContrast() {
        assertTrue(ImageMatch.contrast(IntArray(100) { 3 }) < 1.0)
        assertTrue(ImageMatch.contrast(scene()) > 10.0)
    }

    @Test fun badHashesNeverMatch() {
        assertEquals(0.0, ImageMatch.similarity("", ""), 0.0)
        assertEquals(0.0, ImageMatch.similarity("abc", "ab"), 0.0)
        assertEquals(0.0, ImageMatch.similarity("zz", "zz"), 0.0)
    }
}

class NoiseTest {
    @Test fun everySoundIsAudibleAndInRange() {
        for (type in SoundType.entries) {
            val g = NoiseGenerator(type, 44100, seed = 3)
            var sumSq = 0.0
            var min = 1.0
            var max = -1.0
            val n = 44100 * 10
            repeat(n) {
                val v = g.next()
                assertTrue(v in -1.0..1.0)
                sumSq += v * v
                if (v < min) min = v
                if (v > max) max = v
            }
            val rms = sqrt(sumSq / n)
            assertTrue("$type rms $rms", rms > 0.02)
            assertTrue("$type rms $rms", rms < 0.6)
            assertTrue("$type is not flat", max - min > 0.1)
        }
    }

    @Test fun fillProduces16BitSamples() {
        val buf = ShortArray(1024)
        NoiseGenerator(SoundType.RAIN, seed = 1).fill(buf)
        assertTrue(buf.any { it.toInt() != 0 })
    }
}
