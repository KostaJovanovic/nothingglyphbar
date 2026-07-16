package com.kosta.glyphbar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Frame model for the Phone (4a) Glyph Bar.
 *
 * All of this is verified against the SDK's bytecode rather than its docs:
 *  - Glyph.Code_25111.A_1..A_6 are literally array indices 0..5.
 *  - GlyphFrame.Builder.buildChannel(i, v) does channel.set(i, v) into a
 *    list pre-sized to Common.getTargetDeviceGlyphChannelSize() == 6.
 *  - GlyphFrame.DEFAULT_LIGHT == 4000, the value buildChannel(i) implies.
 *  - GlyphManager.setFrameColors(int[]) hands the array straight to the
 *    service, so it is the fastest path for per-zone brightness.
 */
const val ZONE_COUNT = 6

/**
 * Brightness ceiling. The channel value is 12-bit: Glyph Composer's CSV format
 * uses 0..4095, and displayProgress internally spreads its range over 4096 per
 * channel. GlyphFrame.DEFAULT_LIGHT is 4000, but that is the SDK's default for
 * buildChannel(i) — not the maximum.
 */
const val MAX_LIGHT = 4095

/** What the SDK's own single-arg buildChannel(i) writes. */
const val DEFAULT_LIGHT = 4000

/** One instant of the bar: per-zone brightness, index 0 (top) .. 5 (bottom). */
typealias Frame = IntArray

fun frameOf(vararg v: Int): Frame = IntArray(ZONE_COUNT) { v.getOrElse(it) { 0 }.coerceIn(0, MAX_LIGHT) }

fun emptyFrame(): Frame = IntArray(ZONE_COUNT)

fun fullFrame(level: Int = MAX_LIGHT): Frame = IntArray(ZONE_COUNT) { level.coerceIn(0, MAX_LIGHT) }

fun Frame.scaled(factor: Float): Frame =
    IntArray(ZONE_COUNT) { (this[it] * factor).roundToInt().coerceIn(0, MAX_LIGHT) }

fun Frame.copyFrame(): Frame = copyOf()

/** Percent (0..100) per zone, for the on-screen preview. */
fun Frame.asPercents(): List<Int> = map { (it * 100f / MAX_LIGHT).roundToInt().coerceIn(0, 100) }

/**
 * An animation is a pure function of time, not a fixed frame list.
 *
 * Procedural generation keeps custom user animations (which ARE frame lists)
 * and built-ins on one interface, and lets built-ins run at any frame rate
 * without resampling.
 */
fun interface Animation {
    /** @param t seconds since start. Return the bar state at that instant. */
    fun frameAt(t: Float): Frame
}

/** A recorded, editable animation: explicit frames at a fixed step. */
data class Keyframes(
    val frames: List<Frame>,
    val stepMs: Int = 100,
    val loop: Boolean = true,
) : Animation {
    override fun frameAt(t: Float): Frame {
        if (frames.isEmpty()) return emptyFrame()
        val total = frames.size
        var idx = (t * 1000f / stepMs).toInt()
        idx = if (loop) idx.mod(total) else idx.coerceAtMost(total - 1)
        return frames[idx]
    }

    val durationMs: Int get() = frames.size * stepMs
}

// ---------------------------------------------------------------- easing

private fun triangle(x: Float): Float {
    val f = x.mod(1f)
    return if (f < 0.5f) f * 2f else (1f - f) * 2f
}

private fun gauss(d: Float, sigma: Float): Float = exp(-(d * d) / (2f * sigma * sigma))

private fun lvl(x: Float): Int = (x.coerceIn(0f, 1f) * MAX_LIGHT).roundToInt()

/**
 * The built-in animation library.
 *
 * Each is procedural so it's resolution-independent and trivially adjustable
 * by speed. Written against a 6-zone vertical bar specifically — a "comet" on
 * six segments needs a different tail constant than it would on 33.
 */
object Animations {

    /** Smooth sine breathing across all zones. */
    val Breathe = Animation { t ->
        val v = (sin(t * 2f * PI.toFloat() / 2f - PI.toFloat() / 2f) + 1f) / 2f
        fullFrame(lvl(v))
    }

    /** Single lit zone bouncing top→bottom→top. */
    val Chase = Animation { t ->
        val pos = triangle(t / 1.2f) * (ZONE_COUNT - 1)
        IntArray(ZONE_COUNT) { i -> lvl(gauss(abs(i - pos), 0.5f)) }
    }

    /** Comet with a fading tail, wrapping around. */
    val Comet = Animation { t ->
        val head = (t * 4f).mod(ZONE_COUNT.toFloat())
        IntArray(ZONE_COUNT) { i ->
            var d = head - i
            if (d < 0) d += ZONE_COUNT
            lvl(0.85f.pow(d * 3f))
        }
    }

    /** Travelling sine wave. */
    val Wave = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val phase = t * 3f - i * 0.6f
            lvl((sin(phase) + 1f) / 2f)
        }
    }

    /** Fills up from the bottom, then drains. */
    val Fill = Animation { t ->
        val level = triangle(t / 2f) * ZONE_COUNT
        IntArray(ZONE_COUNT) { i ->
            val zoneFromBottom = ZONE_COUNT - 1 - i
            lvl((level - zoneFromBottom).coerceIn(0f, 1f))
        }
    }

    /** Double-thump heartbeat. */
    val Heartbeat = Animation { t ->
        val x = t.mod(1.2f)
        val a = gauss(x - 0.10f, 0.055f)
        val b = gauss(x - 0.32f, 0.075f)
        fullFrame(lvl(maxOf(a, b * 0.75f)))
    }

    /** Expanding ripple from the centre. */
    val Ripple = Animation { t ->
        val r = (t * 3f).mod(4f)
        IntArray(ZONE_COUNT) { i ->
            val d = abs((i - 2.5f).let { abs(it) } - r)
            lvl(gauss(d, 0.45f))
        }
    }

    /** Hard on/off strobe. */
    val Strobe = Animation { t ->
        if ((t * 8f).toInt() % 2 == 0) fullFrame() else emptyFrame()
    }

    /** Zones light in sequence and stay, then all clear. */
    val Cascade = Animation { t ->
        val x = t.mod(2.1f)
        IntArray(ZONE_COUNT) { i ->
            val on = x > i * 0.25f && x < 1.8f
            if (on) MAX_LIGHT else 0
        }
    }

    /** Two zones converging on the centre and back out. */
    val Converge = Animation { t ->
        val p = triangle(t / 1.5f) * 2.5f
        IntArray(ZONE_COUNT) { i ->
            val top = gauss(abs(i - p), 0.4f)
            val bot = gauss(abs(i - (ZONE_COUNT - 1 - p)), 0.4f)
            lvl(maxOf(top, bot))
        }
    }

    /** Deterministic pseudo-random twinkle (no Random — must be pure in t). */
    val Sparkle = Animation { t ->
        val tick = (t * 12f).toInt()
        IntArray(ZONE_COUNT) { i ->
            // cheap hash → stable per (tick, zone)
            val h = ((tick * 73856093) xor ((i + 1) * 19349663)) and 0x7fffffff
            val phase = (h % 1000) / 1000f
            lvl(if (phase > 0.72f) 1f else 0.03f)
        }
    }

    /** Slow gradient sweep — all zones lit, brightness gradient rolling. */
    val Gradient = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val x = (i / (ZONE_COUNT - 1f)) - (t * 0.5f).mod(1f)
            lvl(1f - abs(x).coerceIn(0f, 1f))
        }
    }

    /** Bar as a 6-bit binary clock of the seconds hand. */
    val BinaryCount = Animation { t ->
        val n = t.toInt() % 64
        IntArray(ZONE_COUNT) { i ->
            if ((n shr (ZONE_COUNT - 1 - i)) and 1 == 1) MAX_LIGHT else 0
        }
    }

    /** Police-style alternating halves. */
    val Alternate = Animation { t ->
        val top = (t * 6f).toInt() % 2 == 0
        IntArray(ZONE_COUNT) { i ->
            val isTop = i < ZONE_COUNT / 2
            if (isTop == top) MAX_LIGHT else 0
        }
    }

    /** Very slow drift, all zones out of phase — ambient. */
    val Ambient = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val v = (sin(t * 0.7f + i * 1.1f) + 1f) / 2f
            lvl(0.06f + v * 0.5f)
        }
    }

    /** Steady solid — useful as a torch / notification hold. */
    val Solid = Animation { fullFrame() }

    // ---------------------------------------------------------- creative

    /** Value noise in 1D — smooth pseudo-random, deterministic in t. */
    private fun noise(x: Float, seed: Int): Float {
        fun h(i: Int): Float {
            var n = (i * 1619 + seed * 31337) and 0x7fffffff
            n = (n shl 13) xor n
            return ((n * (n * n * 15731 + 789221) + 1376312589) and 0x7fffffff) / 2147483647f
        }
        val i = kotlin.math.floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)   // smoothstep
        return h(i) * (1f - u) + h(i + 1) * u
    }

    /** Flame: hot at the bottom, flickering, cooling as it rises. */
    val Fire = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val heightFromBottom = (ZONE_COUNT - 1 - i) / (ZONE_COUNT - 1f)
            val flicker = noise(t * 9f + i * 1.7f, i)
            val base = (1f - heightFromBottom).pow(1.6f)
            lvl((base * (0.55f + 0.45f * flicker) * 1.35f).coerceIn(0f, 1f))
        }
    }

    /** Ball dropped from the top, bouncing with energy loss, then reset. */
    val Bounce = Animation { t ->
        val cycle = 3.4f
        var x = t.mod(cycle)
        // Analytic bounce: track height with restitution per impact.
        var h = 1f
        var v = 0f
        val g = 9.0f
        val e = 0.72f
        var pos = 1f
        var step = 0f
        // Integrate cheaply — 400 steps over the cycle is plenty at 40fps.
        val dt = 0.0085f
        while (step < x) {
            v -= g * dt
            pos += v * dt
            if (pos <= 0f) {
                pos = 0f
                v = -v * e
                if (abs(v) < 0.35f) { pos = 1f; v = 0f }  // re-drop when spent
            }
            step += dt
        }
        val zone = (1f - pos.coerceIn(0f, 1f)) * (ZONE_COUNT - 1)
        IntArray(ZONE_COUNT) { i -> lvl(gauss(abs(i - zone), 0.42f)) }
    }

    /** Raindrops falling down the bar at staggered times. */
    val Rain = Animation { t ->
        val out = FloatArray(ZONE_COUNT)
        for (d in 0 until 3) {
            val speed = 2.2f + d * 0.7f
            val offset = d * 1.31f
            val pos = ((t * speed + offset).mod(ZONE_COUNT + 2f)) - 1f
            for (i in 0 until ZONE_COUNT) {
                val v = gauss(i - pos, 0.38f) * 0.9f
                if (v > out[i]) out[i] = v
            }
        }
        IntArray(ZONE_COUNT) { lvl(out[it]) }
    }

    /** Damped pendulum — swings wide, settles, then gets a fresh push. */
    val Pendulum = Animation { t ->
        val cycle = 5f
        val x = t.mod(cycle)
        val amp = exp(-x * 0.55f)
        val theta = sin(x * 4.2f) * amp
        val pos = (ZONE_COUNT - 1) / 2f + theta * (ZONE_COUNT - 1) / 2f
        IntArray(ZONE_COUNT) { i -> lvl(gauss(abs(i - pos), 0.45f)) }
    }

    /** EKG: flat baseline, then the P-QRS-T spike train. */
    val Pulse = Animation { t ->
        val x = t.mod(1.6f)
        // Sweep a "cursor" down the bar; brightness = trace value at that time.
        val cursor = (x / 1.6f) * ZONE_COUNT
        val spike = when {
            x < 0.30f -> 0.06f
            x < 0.36f -> 0.35f                       // P
            x < 0.40f -> 0.08f
            x < 0.44f -> 1.0f                        // R
            x < 0.48f -> 0.02f                       // S
            x < 0.62f -> 0.05f
            x < 0.74f -> 0.28f                       // T
            else -> 0.05f
        }
        IntArray(ZONE_COUNT) { i ->
            val near = gauss(abs(i - cursor), 0.5f)
            lvl(near * spike)
        }
    }

    /** Two strands twisting past each other. */
    val Helix = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val phase = t * 2.6f + i * 0.85f
            val a = (sin(phase) + 1f) / 2f
            val b = (sin(phase + PI.toFloat()) + 1f) / 2f
            lvl(maxOf(a.pow(3f), b.pow(3f)))
        }
    }

    /** Slow blobs rising and falling, never quite repeating. */
    val LavaLamp = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val a = noise(t * 0.55f + i * 0.42f, 7)
            val b = noise(t * 0.31f - i * 0.27f, 19)
            lvl(((a * b) * 2.4f).coerceIn(0f, 1f))
        }
    }

    /** Radar: a sweep with a phosphor trail that decays. */
    val Radar = Animation { t ->
        val sweep = (t * 2.2f).mod(ZONE_COUNT.toFloat())
        IntArray(ZONE_COUNT) { i ->
            var d = sweep - i
            if (d < 0) d += ZONE_COUNT
            lvl(exp(-d * 1.15f))
        }
    }

    /** SOS in Morse, at the top zone, mirrored down the bar. */
    val Morse = Animation { t ->
        // dot=1u, dash=3u, intra=1u, letter gap=3u, word gap=7u. u = 130ms.
        val u = 0.13f
        val pattern = buildList {
            fun sym(len: Int) { repeat(len) { add(true) }; add(false) }
            repeat(3) { sym(1) }; repeat(2) { add(false) }   // S
            repeat(3) { sym(3) }; repeat(2) { add(false) }   // O
            repeat(3) { sym(1) }; repeat(6) { add(false) }   // S + word gap
        }
        val idx = ((t / u).toInt()).mod(pattern.size)
        if (pattern[idx]) fullFrame() else emptyFrame()
    }

    /** Water sloshing in a tube — level tilts and settles. */
    val Slosh = Animation { t ->
        val tilt = sin(t * 2.1f) * exp(-(t.mod(4f)) * 0.35f) * 2.2f
        val level = ZONE_COUNT / 2f + tilt
        IntArray(ZONE_COUNT) { i ->
            val fromBottom = ZONE_COUNT - 1 - i
            lvl((level - fromBottom).coerceIn(0f, 1f))
        }
    }

    /** TV static — every zone independent white noise. */
    val Static = Animation { t ->
        val tick = (t * 22f).toInt()
        IntArray(ZONE_COUNT) { i ->
            val h = ((tick * 2654435761u.toInt()) xor ((i + 3) * 40503)) and 0x7fffffff
            lvl((h % 1000) / 1000f)
        }
    }

    /** A caterpillar inching along: stretch, then contract. */
    val Inchworm = Animation { t ->
        val cycle = 1.8f
        val x = t.mod(cycle) / cycle
        val base = (t / cycle).toInt().mod(ZONE_COUNT)
        val len = 1f + 2f * triangle(x)
        IntArray(ZONE_COUNT) { i ->
            var d = (i - base).toFloat()
            if (d < 0) d += ZONE_COUNT
            lvl(if (d <= len) 1f - (d / (len + 0.6f)) * 0.55f else 0f)
        }
    }

    /** Warm sunrise: glow climbs from the bottom and fills. */
    val Sunrise = Animation { t ->
        val x = triangle(t / 6f)
        IntArray(ZONE_COUNT) { i ->
            val fromBottom = (ZONE_COUNT - 1 - i) / (ZONE_COUNT - 1f)
            lvl(((x * 1.5f) - fromBottom).coerceIn(0f, 1f).pow(0.7f))
        }
    }

    /** Mirror: symmetric noise blooming out from the centre line. */
    val Rorschach = Animation { t ->
        IntArray(ZONE_COUNT) { i ->
            val m = if (i < ZONE_COUNT / 2) i else ZONE_COUNT - 1 - i
            lvl(noise(t * 1.9f + m * 0.9f, 43).pow(1.4f))
        }
    }

    /** Spring released from compression, oscillating to rest. */
    val Spring = Animation { t ->
        val x = t.mod(3f)
        val d = exp(-x * 1.1f) * cos(x * 11f)
        val level = (0.5f + d * 0.5f) * ZONE_COUNT
        IntArray(ZONE_COUNT) { i ->
            val fromBottom = ZONE_COUNT - 1 - i
            lvl((level - fromBottom).coerceIn(0f, 1f))
        }
    }

    /** Countdown: zones extinguish one by one, then flash. */
    val Countdown = Animation { t ->
        val cycle = ZONE_COUNT + 1.4f
        val x = t.mod(cycle)
        if (x > ZONE_COUNT) {
            return@Animation if (((x - ZONE_COUNT) * 9f).toInt() % 2 == 0) fullFrame() else emptyFrame()
        }
        val gone = x.toInt()
        IntArray(ZONE_COUNT) { i -> if (i >= gone) MAX_LIGHT else 0 }
    }

    /** Two meteors of different speeds crossing the bar. */
    val Meteors = Animation { t ->
        val out = FloatArray(ZONE_COUNT)
        val heads = floatArrayOf((t * 5.5f).mod(9f) - 1.5f, (t * 3.1f + 4f).mod(9f) - 1.5f)
        for (h in heads) {
            for (i in 0 until ZONE_COUNT) {
                val d = h - i
                val v = if (d >= 0) exp(-d * 1.6f) else 0f
                if (v > out[i]) out[i] = v
            }
        }
        IntArray(ZONE_COUNT) { lvl(out[it]) }
    }

    data class Entry(val name: String, val description: String, val animation: Animation)

    val all: List<Entry> = listOf(
        // Simple
        Entry("Breathe", "Smooth sine pulse", Breathe),
        Entry("Chase", "Bouncing scanner", Chase),
        Entry("Comet", "Fading tail, wraps", Comet),
        Entry("Wave", "Travelling sine", Wave),
        Entry("Fill", "Fills and drains", Fill),
        Entry("Heartbeat", "Double thump", Heartbeat),
        Entry("Ripple", "Expands from centre", Ripple),
        Entry("Cascade", "Sequential build-up", Cascade),
        Entry("Converge", "Meet in the middle", Converge),
        Entry("Sparkle", "Random twinkle", Sparkle),
        Entry("Gradient", "Rolling gradient", Gradient),
        Entry("Binary", "6-bit second counter", BinaryCount),
        Entry("Alternate", "Flip-flop halves", Alternate),
        Entry("Ambient", "Slow out-of-phase drift", Ambient),
        Entry("Strobe", "Hard flash", Strobe),
        Entry("Solid", "All on", Solid),
        // Physical / organic
        Entry("Fire", "Flame, hot at the bottom", Fire),
        Entry("Bounce", "Ball with real gravity", Bounce),
        Entry("Rain", "Drops falling", Rain),
        Entry("Pendulum", "Swings and settles", Pendulum),
        Entry("Pulse", "EKG spike train", Pulse),
        Entry("Helix", "Two strands twisting", Helix),
        Entry("Lava", "Slow drifting blobs", LavaLamp),
        Entry("Radar", "Sweep with phosphor trail", Radar),
        Entry("Morse", "SOS, properly timed", Morse),
        Entry("Slosh", "Water tilting in a tube", Slosh),
        Entry("Static", "TV snow", Static),
        Entry("Inchworm", "Stretch and contract", Inchworm),
        Entry("Sunrise", "Warm glow climbing", Sunrise),
        Entry("Rorschach", "Mirrored bloom", Rorschach),
        Entry("Spring", "Damped oscillation", Spring),
        Entry("Countdown", "Zones out, then flash", Countdown),
        Entry("Meteors", "Two crossing streaks", Meteors),
    )
}
