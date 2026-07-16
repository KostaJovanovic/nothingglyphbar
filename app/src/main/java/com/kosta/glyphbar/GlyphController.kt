package com.kosta.glyphbar

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GlyphBar"

/**
 * The six addressable zones of the Phone (4a) Glyph Bar, top to bottom.
 *
 * The bar has seven visible segments, but only these six white ones are
 * software-addressable. The seventh (red) is the hardware video-recording
 * privacy indicator and is driven by the camera stack — it is deliberately
 * not exposed by the GDK, so no app can impersonate a recording state.
 */
val BAR_ZONES: List<Int> = listOf(
    Glyph.Code_25111.A_1,
    Glyph.Code_25111.A_2,
    Glyph.Code_25111.A_3,
    Glyph.Code_25111.A_4,
    Glyph.Code_25111.A_5,
    Glyph.Code_25111.A_6,
)

sealed interface GlyphStatus {
    data object Connecting : GlyphStatus
    data object Ready : GlyphStatus
    data class Unsupported(val reason: String) : GlyphStatus
    data class Error(val message: String) : GlyphStatus
}

/**
 * Owns the GlyphManager lifecycle and exposes the bar as simple operations.
 *
 * The GDK only serves the foreground app, so this is bound in onCreate and
 * released in onDestroy of the single Activity.
 */
class GlyphController(private val context: Context, private val scope: CoroutineScope) {

    private val _status = MutableStateFlow<GlyphStatus>(GlyphStatus.Connecting)
    val status: StateFlow<GlyphStatus> = _status.asStateFlow()

    /** Which zone indices (0..5) the bar is currently showing. Patterns write this. */
    private val _lit = MutableStateFlow<Set<Int>>(emptySet())
    val lit: StateFlow<Set<Int>> = _lit.asStateFlow()

    /** The user's manual zone selection. Only setZones/toggleZone write this. */
    private var selection: Set<Int> = emptySet()

    private var manager: GlyphManager? = null
    private var sessionOpen = false
    private var patternJob: Job? = null

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(component: ComponentName?) {
            val gm = manager ?: return
            try {
                // register() returns false if the key/permission is rejected.
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
        patternJob?.cancel()
        manager?.let { gm ->
            runCatching {
                if (sessionOpen) gm.closeSession()
                gm.unInit()
            }.onFailure { Log.w(TAG, "release failed", it) }
        }
        sessionOpen = false
        manager = null
    }

    private inline fun withSession(block: (GlyphManager) -> Unit) {
        val gm = manager?.takeIf { sessionOpen } ?: return
        try {
            block(gm)
        } catch (e: GlyphException) {
            Log.e(TAG, "glyph call failed", e)
            _status.value = GlyphStatus.Error(e.message ?: "Glyph call failed")
        }
    }

    /** Cancel any running pattern so manual control isn't fought over. */
    private fun stopPattern() {
        patternJob?.cancel()
        patternJob = null
    }

    private fun frameOf(zoneIndices: Collection<Int>): GlyphFrame? {
        val gm = manager ?: return null
        val builder = gm.glyphFrameBuilder
        zoneIndices.forEach { builder.buildChannel(BAR_ZONES[it]) }
        return builder.build()
    }

    /** Light exactly this set of zones (indices 0..5); empty turns the bar off. */
    fun setZones(zoneIndices: Set<Int>) {
        stopPattern()
        selection = zoneIndices
        applyZones(zoneIndices)
    }

    private fun applyZones(zoneIndices: Set<Int>) {
        withSession { gm ->
            if (zoneIndices.isEmpty()) {
                gm.turnOff()
            } else {
                frameOf(zoneIndices)?.let { gm.toggle(it) }
            }
            _lit.value = zoneIndices
        }
    }

    fun toggleZone(index: Int) {
        // Toggle against the user's selection, not _lit: during a pattern _lit holds
        // the sweep's transient position, so sampling it would toggle against whatever
        // frame happened to be showing.
        val next = selection.toMutableSet().apply { if (!add(index)) remove(index) }
        setZones(next)
    }

    fun allOn() = setZones(BAR_ZONES.indices.toSet())

    fun allOff() = setZones(emptySet())

    /**
     * Breathing animation over the given zones.
     *
     * animate() pushes frames for exactly frame.getCycles() iterations, so 0 means
     * zero breaths rather than "forever". Ask for one breath at a time and repeat
     * from our own cancellable job, which also keeps Stop responsive — the SDK's
     * loop ignores interrupts once it starts.
     */
    fun breathe(zoneIndices: Set<Int> = BAR_ZONES.indices.toSet(), periodMs: Int = 2000) {
        stopPattern()
        patternJob = scope.launch {
            while (isActive) {
                withSession { gm ->
                    val gmb = gm.glyphFrameBuilder
                    zoneIndices.forEach { gmb.buildChannel(BAR_ZONES[it]) }
                    val frame = gmb.buildPeriod(periodMs).buildCycles(1).buildInterval(0).build()
                    gm.animate(frame)
                    _lit.value = zoneIndices
                }
                delay(periodMs.toLong())
            }
        }
    }

    /** Fill the bar as a 0..100 progress meter. */
    fun showProgress(percent: Int, reverse: Boolean = false) {
        stopPattern()
        withSession { gm ->
            val p = percent.coerceIn(0, 100)
            val frame = frameOf(BAR_ZONES.indices.toList()) ?: return@withSession
            gm.displayProgress(frame, p, reverse)
            // Mirror the SDK's mapping so the preview tracks the bar: it spreads
            // percent*250 across the six channels at 4096 each. The boundary segment
            // is lit at partial brightness, which a binary preview can't show.
            val n = ((p * 250) / 4096).coerceIn(0, BAR_ZONES.size)
            val zones = if (reverse) ((BAR_ZONES.size - n) until BAR_ZONES.size).toSet()
            else (0 until n).toSet()
            selection = zones
            _lit.value = zones
        }
    }

    /** Software-driven chase ("knight rider") sweep, since the GDK has no such primitive. */
    fun chase(stepMs: Long = 90) {
        stopPattern()
        patternJob = scope.launch {
            val order = BAR_ZONES.indices.toList()
            val sweep = order + order.reversed().drop(1).dropLast(1)
            while (isActive) {
                for (i in sweep) {
                    if (!isActive) break
                    applyZones(setOf(i))
                    delay(stepMs)
                }
            }
        }
    }

    fun stop() {
        stopPattern()
        allOff()
    }
}
