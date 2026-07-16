package com.kosta.glyphbar

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock

private const val TAG = "GlyphBar"

/** Frames per second for procedural playback. 40 is smooth and well under IPC limits. */
private const val PLAYBACK_FPS = 40
private const val FRAME_MS = 1000L / PLAYBACK_FPS

sealed interface GlyphStatus {
    data object Connecting : GlyphStatus
    data object Ready : GlyphStatus
    data class Unsupported(val reason: String) : GlyphStatus
    data class Error(val message: String) : GlyphStatus
}

/**
 * Owns the GlyphManager lifecycle and drives the bar.
 *
 * Everything routes through setFrameColors(IntArray), which the SDK passes
 * straight to the service — it is the only call that exposes per-zone
 * brightness, so it's the single primitive the whole app is built on.
 */
class GlyphController(private val context: Context, private val scope: CoroutineScope) {

    private val _status = MutableStateFlow<GlyphStatus>(GlyphStatus.Connecting)
    val status: StateFlow<GlyphStatus> = _status.asStateFlow()

    /** What the bar is showing right now (post-brightness), for the preview. */
    private val _frame = MutableStateFlow(emptyFrame())
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    /** Master brightness 0f..1f, applied to every write. */
    private val _brightness = MutableStateFlow(1f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    /** Name of the running animation, or null when idle/manual. */
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    /** The user's manual zone selection, kept apart from what patterns display. */
    private val _selection = MutableStateFlow(emptyFrame())
    val selection: StateFlow<Frame> = _selection.asStateFlow()

    private var manager: GlyphManager? = null
    @Volatile private var sessionOpen = false
    private var patternJob: Job? = null

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(component: ComponentName?) {
            val gm = manager ?: return
            try {
                if (!gm.register(Glyph.DEVICE_25111)) {
                    _status.value = GlyphStatus.Error(
                        "Glyph service rejected registration. Check the NothingKey meta-data " +
                            "and the com.nothing.ketchum.permission.ENABLE permission."
                    )
                    return
                }
                gm.openSession()
                sessionOpen = true
                _status.value = GlyphStatus.Ready
            } catch (e: GlyphException) {
                Log.e(TAG, "openSession failed", e)
                _status.value = GlyphStatus.Error(e.message ?: "openSession failed")
            }
        }

        override fun onServiceDisconnected(component: ComponentName?) {
            sessionOpen = false
            _status.value = GlyphStatus.Connecting
        }
    }

    fun connect() {
        if (!Common.is25111()) {
            _status.value = GlyphStatus.Unsupported(
                if (Common.is25111p()) {
                    "This is a Phone (4a) Pro — it has a 13x13 Glyph Matrix, not the 6-zone bar. " +
                        "It needs GlyphMatrixManager instead."
                } else {
                    "This device is not a Nothing Phone (4a), so it has no Glyph Bar."
                }
            )
            return
        }
        manager = GlyphManager.getInstance(context).also { it.init(callback) }
    }

    fun release() {
        stopPattern()
        manager?.let { gm ->
            runCatching {
                if (sessionOpen) gm.closeSession()
                gm.unInit()
            }.onFailure { Log.w(TAG, "release failed", it) }
        }
        sessionOpen = false
        manager = null
    }

    // ------------------------------------------------------------ core write

    /** The one place the hardware is written. Applies master brightness. */
    private fun push(f: Frame) {
        val gm = manager?.takeIf { sessionOpen } ?: return
        val out = f.scaled(_brightness.value)
        try {
            gm.setFrameColors(out)
            _frame.value = out
        } catch (e: GlyphException) {
            Log.e(TAG, "setFrameColors failed", e)
            _status.value = GlyphStatus.Error(e.message ?: "Glyph call failed")
        }
    }

    fun setBrightness(value: Float) {
        _brightness.value = value.coerceIn(0f, 1f)
        // Re-push so the change is visible immediately when idle.
        if (patternJob == null) push(_selection.value)
    }

    private fun stopPattern() {
        patternJob?.cancel()
        patternJob = null
        _playing.value = null
    }

    // ------------------------------------------------------------ manual

    /** Set the bar directly (also becomes the manual selection). */
    fun setFrame(f: Frame) {
        stopPattern()
        _selection.value = f
        push(f)
    }

    /** Toggle a zone between off and full. */
    fun toggleZone(index: Int) {
        val next = _selection.value.copyFrame()
        next[index] = if (next[index] > 0) 0 else MAX_LIGHT
        setFrame(next)
    }

    /** Set one zone's brightness directly (0..MAX_LIGHT). */
    fun setZone(index: Int, level: Int) {
        val next = _selection.value.copyFrame()
        next[index] = level.coerceIn(0, MAX_LIGHT)
        setFrame(next)
    }

    fun allOn() = setFrame(fullFrame())

    fun allOff() = setFrame(emptyFrame())

    // ------------------------------------------------------------ playback

    /**
     * Play an animation until stopped.
     *
     * Driven from our own coroutine rather than the SDK's animate(): that call
     * runs a fixed number of cycles on GlyphManager's single worker thread and
     * ignores interrupts, so it can't be cancelled or looped responsively.
     */
    fun play(name: String, animation: Animation) {
        stopPattern()
        _playing.value = name
        patternJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = (SystemClock.elapsedRealtime() - start) / 1000f
                push(animation.frameAt(t))
                delay(FRAME_MS)
            }
        }
    }

    /** Play a fixed frame list once, then stop. Used by the editor's preview. */
    fun playOnce(name: String, keys: Keyframes, onFinished: () -> Unit = {}) {
        stopPattern()
        _playing.value = name
        patternJob = scope.launch {
            for (f in keys.frames) {
                if (!isActive) break
                push(f)
                delay(keys.stepMs.toLong())
            }
            _playing.value = null
            patternJob = null
            onFinished()
        }
    }

    /** Feed frames from an external source (audio) until cancelled. */
    fun playSource(name: String, source: () -> Frame) {
        stopPattern()
        _playing.value = name
        patternJob = scope.launch {
            while (isActive) {
                push(source())
                delay(FRAME_MS)
            }
        }
    }

    fun stop() {
        stopPattern()
        push(emptyFrame())
        _selection.value = emptyFrame()
    }

    /** True while a pattern is running. */
    val isPlaying: Boolean get() = patternJob != null

    // ------------------------------------------------------------ POV

    /** Measured sustained write rate, Hz. POV is only as good as this number. */
    private val _writeHz = MutableStateFlow(0f)
    val writeHz: StateFlow<Float> = _writeHz.asStateFlow()

    /**
     * Write a frame with no brightness scaling and no state bookkeeping.
     *
     * POV needs the shortest possible path to the service: at a few hundred Hz
     * the StateFlow updates and the IntArray allocation in scaled() cost real
     * time, and every microsecond here is column width in the air.
     */
    private fun pushRaw(gm: GlyphManager, f: Frame): Boolean = try {
        gm.setFrameColors(f)
        true
    } catch (e: GlyphException) {
        false
    }

    /**
     * Blast one POV pass: each column held for columnUs microseconds.
     *
     * Runs on Default (not the main thread) and sleeps with parkNanos rather
     * than delay(), whose granularity is ~1ms at best — far too coarse when a
     * column may only last 2-3ms.
     */
    suspend fun povSweep(columns: List<Frame>, columnUs: Long, reversed: Boolean) {
        val gm = manager?.takeIf { sessionOpen } ?: return
        val order = if (reversed) columns.asReversed() else columns
        val startNs = System.nanoTime()
        var deadline = startNs
        val stepNs = columnUs * 1_000L

        for (f in order) {
            if (!pushRaw(gm, f)) break
            deadline += stepNs
            var now = System.nanoTime()
            while (now < deadline) {
                val left = deadline - now
                // parkNanos under ~50us tends to overshoot; spin the last stretch.
                if (left > 50_000) java.util.concurrent.locks.LockSupport.parkNanos(left - 50_000)
                now = System.nanoTime()
            }
        }
        pushRaw(gm, emptyFrame())

        val elapsedNs = System.nanoTime() - startNs
        if (elapsedNs > 0 && order.isNotEmpty()) {
            _writeHz.value = order.size * 1_000_000_000f / elapsedNs
        }
    }

    /** Measure how fast setFrameColors can actually be driven, in Hz. */
    suspend fun benchmarkWriteRate(samples: Int = 200): Float {
        val gm = manager?.takeIf { sessionOpen } ?: return 0f
        val a = fullFrame(1)
        val b = emptyFrame()
        // Warm up so the first binder transaction doesn't skew the result.
        repeat(10) { pushRaw(gm, if (it % 2 == 0) a else b) }
        val t0 = System.nanoTime()
        repeat(samples) { pushRaw(gm, if (it % 2 == 0) a else b) }
        val dt = System.nanoTime() - t0
        pushRaw(gm, b)
        val hz = if (dt > 0) samples * 1_000_000_000f / dt else 0f
        _writeHz.value = hz
        return hz
    }

    /** Claim the pattern slot for POV, so other tabs' patterns don't fight it. */
    fun startPov(name: String, block: suspend () -> Unit) {
        stopPattern()
        _playing.value = name
        patternJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) { block() }
    }
}
