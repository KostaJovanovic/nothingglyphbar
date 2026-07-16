package com.kosta.glyphbar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sign

/**
 * Persistence-of-vision text.
 *
 * The bar is a 6-pixel column; swing the phone and hold each column for a few
 * milliseconds, and your eye integrates the columns into letters hanging in
 * the air.
 *
 * The physical constraint: every column is one binder round-trip to the Glyph
 * service. Column width in the air = swing speed x column duration, so a slow
 * write path means fat letters. Hence benchmarkWriteRate() and the on-screen
 * measured Hz — the honest number, measured on the actual device, rather than
 * a figure I'd be guessing at.
 */
class PovController(
    private val glyph: GlyphController,
    private val motion: MotionDetector,
    private val scope: CoroutineScope,
) {

    enum class Mode {
        /** Fire one pass each time a swing is detected. Direction-aware. */
        Swing,

        /** Loop the message continuously, ignoring the sensors. */
        Continuous,
    }

    /** Per-column hold time, microseconds. 3000us = 3ms. */
    var columnUs: Long = 3000

    /** Swing trigger threshold in the sensor's units. */
    var threshold: Float = motion.defaultThreshold()

    var mode: Mode = Mode.Swing

    /** Mirror alternate passes so text reads forwards on the return stroke. */
    var bidirectional: Boolean = true

    fun start(text: String, level: Int) {
        val columns = PovFont.frames(text, level)
        if (columns.isEmpty()) return

        motion.start()
        glyph.startPov("POV · $text") {
            when (mode) {
                Mode.Continuous -> runContinuous(columns)
                Mode.Swing -> runSwing(columns)
            }
        }
    }

    private suspend fun runContinuous(columns: List<Frame>) {
        while (currentScopeActive()) {
            glyph.povSweep(columns, columnUs, reversed = false)
            delay(120)
        }
    }

    /**
     * Wait for the phone to be moving fast, then paint one pass.
     *
     * After a pass we wait for the swing to decay below half-threshold before
     * re-arming, otherwise a single wave fires several overlapping passes and
     * the text smears.
     */
    private suspend fun runSwing(columns: List<Frame>) {
        var armed = true
        while (currentScopeActive()) {
            val rate = motion.rate
            val speed = abs(rate)

            if (armed && speed >= threshold) {
                val backward = bidirectional && rate.sign < 0
                glyph.povSweep(columns, columnUs, reversed = backward)
                armed = false
            } else if (!armed && speed < threshold * 0.5f) {
                armed = true
            }
            // Poll fast: late detection shows up as the message drifting.
            delay(2)
        }
    }

    private fun currentScopeActive(): Boolean = scope.isActive

    fun stop() {
        motion.stop()
        glyph.stop()
    }

    /** One pass immediately, no sensor — for checking the message on-screen. */
    fun testPass(text: String, level: Int) {
        val columns = PovFont.frames(text, level)
        if (columns.isEmpty()) return
        glyph.startPov("POV test") {
            glyph.povSweep(columns, columnUs, reversed = false)
        }
    }

    fun benchmark(onResult: (Float) -> Unit) {
        glyph.startPov("Benchmark") {
            onResult(glyph.benchmarkWriteRate())
        }
    }
}
