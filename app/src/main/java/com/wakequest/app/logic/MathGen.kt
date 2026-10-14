package com.wakequest.app.logic

import kotlin.random.Random

data class MathProblem(val text: String, val answer: Int)

object MathGen {
    fun generate(difficulty: Int, rnd: Random = Random.Default): MathProblem =
        when (difficulty.coerceIn(1, 3)) {
            1 -> {
                val a = rnd.nextInt(10, 50)
                val b = rnd.nextInt(10, 50)
                MathProblem("$a + $b", a + b)
            }
            2 -> {
                val a = rnd.nextInt(11, 30)
                val b = rnd.nextInt(3, 10)
                val c = rnd.nextInt(10, 100)
                MathProblem("$a × $b + $c", a * b + c)
            }
            else -> {
                val a = rnd.nextInt(12, 40)
                val b = rnd.nextInt(11, 20)
                val c = rnd.nextInt(100, 500)
                MathProblem("$a × $b + $c", a * b + c)
            }
        }
}

object TextMatch {
    private val nonWord = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String =
        s.lowercase().replace(nonWord, "").replace(spaces, " ").trim()

    fun matches(target: String, input: String): Boolean {
        val t = normalize(target)
        return t.isNotEmpty() && t == normalize(input)
    }
}

object Phrases {
    private val short = listOf(
        "Rise and shine",
        "I am awake now",
        "Today is a good day",
        "Up and at it",
        "No more snoozing",
    )
    private val medium = listOf(
        "I am awake and ready to make today count",
        "My future self will thank me for getting up",
        "Today I choose energy over another nap",
        "The snooze button never built anything",
        "Small wins every morning add up",
    )
    private val long = listOf(
        "Every great morning starts with putting my feet on the floor and standing up",
        "I get up, I show up, and I move forward one step at a time today",
        "Bright light, cold water and a few deep breaths will wake me up for real",
        "The hardest part of the morning is the first minute and I am already past it",
    )

    fun pick(difficulty: Int, rnd: Random = Random.Default): String = when (difficulty.coerceIn(1, 3)) {
        1 -> short.random(rnd)
        2 -> medium.random(rnd)
        else -> long.random(rnd)
    }
}
