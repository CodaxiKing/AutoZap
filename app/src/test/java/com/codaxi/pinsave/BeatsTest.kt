package com.codaxi.pinsave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatsTest {
    /** Curva com um pulso a cada [every] segundos. */
    private fun pulses(seconds: Float, every: Float): FloatArray {
        val env = FloatArray((seconds * Beats.FPS).toInt()) { 0.01f }
        var t = every
        while (t < seconds) {
            val i = (t * Beats.FPS).toInt()
            for (k in 0 until 8) if (i + k < env.size) env[i + k] = 0.5f - k * 0.05f
            t += every
        }
        return env
    }

    @Test
    fun findsTheBeats() {
        val peaks = Beats.peaks(pulses(10f, 1f))
        assertEquals(9, peaks.size)
        peaks.forEachIndexed { i, p -> assertTrue(abs(p.first - (i + 1)) < 0.05f) }
    }

    @Test
    fun cutsLandOnBeatsAndAddUpToTheTotal() {
        val peaks = Beats.peaks(pulses(10f, 1f))
        val parts = Beats.segments(peaks, 10f, 0.7f, 1.4f)
        assertEquals(10f, parts.sum(), 0.01f)
        assertEquals(10, parts.size)
        parts.forEach { assertTrue(abs(it - 1f) < 0.06f) }
    }

    @Test
    fun silenceFallsBackToEvenParts() {
        val parts = Beats.segments(emptyList(), 9f, 1.4f, 2.6f)
        assertEquals(9f, parts.sum(), 0.01f)
        assertTrue(parts.all { it >= 1.0f && it <= 2.7f })
        assertTrue(Beats.peaks(FloatArray(0)).isEmpty())
    }

    @Test
    fun shortVideoIsASinglePart() {
        assertEquals(listOf(1.2f), Beats.segments(emptyList(), 1.2f, 0.7f, 1.4f))
    }
}
