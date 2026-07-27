package com.kosta.glyphbar

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Persistence-of-vision text.
 *
 * The bar is a 6-pixel column; swing the phone and hold each column for a
 * moment, and your eye integrates the columns into letters hanging in the air.
 *
 * The key idea (Option A): columns are stepped by *position*, not by a timer.
 * The gyroscope's angular rate is integrated into a sweep angle, and we advance
 * one text column for every [radPerColumn] of that angle. So a letter occupies a
 * fixed slice of the swing arc — it comes out the same width whether you flick
 * fast or wave slow — and the swing direction picks whether the columns are
 * painted forwards or reversed, which keeps the message anchored in space and
 * readable on both the out- and back-strokes.
 *
 * The physical ceiling is unchanged: every column is one binder round-trip to
 * the Glyph service. Swing faster than the measured write rate and columns are
 * simply skipped (the text goes gappy) rather than lagging behind — see
 * benchmarkWriteRate() and the on-screen measured Hz.
 */
class PovController(
    private val glyph: GlyphController,
    private val motion: MotionDetector,
) {

    enum class Mode {
        /** Paint from real motion: position-stepped, direction-aware. */
        Swing,

        /** Loop the message on a timer, ignoring the sensors. */
        Continuous,
    }

    /** Radians of sweep per text column. Smaller = tighter text that needs only
     *  a small flick; larger = spread wide across a big swing. */
    var radPerColumn: Float = 0.02f

    /** Per-column hold time for the no-sensor Continuous mode, microseconds. */
    var columnUs: Long = 3000

    /** Minimum |rate| that counts as a swing, in the sensor's units. */
    var threshold: Float = motion.defaultThreshold()

    var mode: Mode = Mode.Swing

    /** Paint on the return stroke too, not just the forward one. */
    var bidirectional: Boolean = true

    /** Flip which swing direction paints forwards — gyro sign convention varies
     *  by device, so if the message reads mirrored/backwards, toggle this. */
    var invert: Boolean = false

    fun start(text: String, level: Int) {
        val columns = PovFont.frames(text, level)
        if (columns.isEmpty()) return

        motion.start()
        glyph.startPov("POV · $text") {
            when (mode) {
                Mode.Continuous -> runContinuous(columns)
                Mode.Swing -> runWand(columns)
            }
        }
    }

    private suspend fun runContinuous(columns: List<Frame>) {
        while (coroutineContext.isActive) {
            glyph.povSweep(columns, columnUs, reversed = false)
            delay(120)
        }
    }

    /**
     * Position-based POV.
     *
     * Idle until a swing crosses [threshold], then anchor the sweep angle at
     * zero and paint whichever column the accumulated angle currently maps to.
     * Column order is chosen from the stroke's direction so col 0 always lands
     * on the same side of the arc — that's what keeps the text spatially stable
     * across the out- and back-strokes. A stroke ends when it slows past the
     * turnaround (or reverses), and the bar blanks so passes don't smear.
     */
    private suspend fun runWand(columns: List<Frame>) {
        val n = columns.size
        var painting = false
        var forward = true      // does this stroke paint columns 0..n-1?
        var lastIdx = -1

        while (coroutineContext.isActive) {
            val signed = if (invert) -motion.rate else motion.rate
            val speed = abs(signed)

            if (!painting) {
                if (speed >= threshold) {
                    forward = signed >= 0f
                    if (bidirectional || forward) {
                        painting = true
                        lastIdx = -1
                        motion.resetAngle()
                    }
                }
            } else {
                val reversed = (signed >= 0f) != forward
                if (speed < threshold * 0.35f || reversed) {
                    // Slowed at the turnaround, or flipped direction: stroke done.
                    painting = false
                    lastIdx = -1
                    glyph.povBlank()
                } else {
                    val idx = (abs(motion.angle) / radPerColumn).toInt()
                    when {
                        idx >= n -> if (lastIdx != n) {
                            // Whole message swept past; hold blank until it ends.
                            glyph.povBlank()
                            lastIdx = n
                        }
                        idx != lastIdx -> {
                            val col = if (forward) idx else n - 1 - idx
                            glyph.povColumn(columns[col])
                            lastIdx = idx
                        }
                    }
                }
            }
            // Poll far faster than the bar can repaint; sub-ms so a fast stroke
            // never overshoots a column before we notice.
            LockSupport.parkNanos(250_000L)
        }
        glyph.povBlank()
    }

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
