package com.kosta.glyphbar

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "GlyphAudio"

/**
 * Turns audio into bar frames.
 *
 * Two sources, because of a platform constraint worth spelling out:
 *
 *  - SESSION: a Visualizer bound to OUR OWN MediaPlayer's session. This works.
 *  - MIC: AudioRecord + our own FFT. Reacts to anything audible in the room,
 *    including other apps' music coming out of the speaker.
 *
 * What is NOT here: Visualizer(0), the global output mix. Android 10 closed
 * that to third-party apps for privacy — it returns silence or throws on
 * modern Android regardless of permissions. The original version of this file
 * used it, which is why the Sound tab did nothing at all.
 */
class AudioReactive {

    enum class Mode {
        /** Six log-spaced frequency bands, bass at the bottom. */
        Spectrum,

        /** Whole-mix loudness as a VU meter filling upward. */
        Level,

        /** Bass-only pulse across every zone. */
        Bass,
    }

    var mode: Mode = Mode.Spectrum

    private var visualizer: Visualizer? = null
    private var recorder: AudioRecord? = null
    @Volatile private var micRunning = false

    private val smoothed = FloatArray(ZONE_COUNT)
    private var agc = 0.15f

    @Volatile private var latestFft: FloatArray? = null
    @Volatile private var latestRms: Float = 0f

    val isRunning: Boolean get() = visualizer != null || micRunning

    // ------------------------------------------------------------ session

    /**
     * Attach to one of our own MediaPlayer sessions.
     * @return null on success, else a readable reason.
     */
    fun startSession(sessionId: Int): String? {
        stop()
        return try {
            val v = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                            wave ?: return
                            var sum = 0f
                            for (b in wave) {
                                val s = (b.toInt() and 0xFF) - 128
                                sum += (s * s).toFloat()
                            }
                            latestRms = sqrt(sum / wave.size) / 128f
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            fft ?: return
                            val bins = fft.size / 2
                            latestFft = FloatArray(bins) { k ->
                                if (k == 0) abs(fft[0].toFloat())
                                else hypot(
                                    fft.getOrElse(2 * k) { 0 }.toFloat(),
                                    fft.getOrElse(2 * k + 1) { 0 }.toFloat(),
                                )
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    true,
                    true,
                )
                enabled = true
            }
            visualizer = v
            null
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer(session) failed", e)
            stop()
            "Couldn't analyse playback: ${e.javaClass.simpleName}. " +
                "Grant microphone permission — the Visualizer needs it even for our own audio."
        }
    }

    // ------------------------------------------------------------ mic

    /** Listen on the microphone. @return null on success, else a reason. */
    fun startMic(): String? {
        stop()
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return "This device reports no usable microphone buffer."

        val fftSize = 1024
        val bufSize = maxOf(minBuf, fftSize * 2 * 2)

        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "Microphone unavailable — permission denied, or another app holds it."
            }
            recorder = rec
            rec.startRecording()
            micRunning = true

            thread(name = "glyph-mic", isDaemon = true) {
                val pcm = ShortArray(fftSize)
                val buf = FloatArray(fftSize)
                while (micRunning) {
                    val n = rec.read(pcm, 0, fftSize)
                    if (n <= 0) continue
                    var sum = 0f
                    for (i in 0 until n) {
                        val s = pcm[i] / 32768f
                        buf[i] = s
                        sum += s * s
                    }
                    for (i in n until fftSize) buf[i] = 0f
                    latestRms = sqrt(sum / n)
                    latestFft = try {
                        Fft.magnitudes(buf)
                    } catch (e: Exception) {
                        Log.e(TAG, "fft failed", e); null
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            stop()
            "Microphone permission not granted."
        } catch (e: Exception) {
            Log.e(TAG, "mic start failed", e)
            stop()
            "Couldn't open the microphone: ${e.javaClass.simpleName}"
        }
    }

    fun stop() {
        micRunning = false
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }.onFailure { Log.w(TAG, "visualizer release failed", it) }
        visualizer = null
        runCatching {
            recorder?.stop()
            recorder?.release()
        }.onFailure { Log.w(TAG, "recorder release failed", it) }
        recorder = null
        latestFft = null
        latestRms = 0f
        smoothed.fill(0f)
    }

    // ------------------------------------------------------------ render

    fun frame(): Frame {
        val target = when (mode) {
            Mode.Spectrum -> spectrumTargets()
            Mode.Level -> levelTargets()
            Mode.Bass -> bassTargets()
        } ?: return emptyFrame()

        // Fast attack, slow release — beats read as hits rather than mush.
        for (i in 0 until ZONE_COUNT) {
            val t = target[i]
            smoothed[i] = if (t > smoothed[i]) {
                smoothed[i] + (t - smoothed[i]) * 0.55f
            } else {
                smoothed[i] + (t - smoothed[i]) * 0.14f
            }
        }
        return IntArray(ZONE_COUNT) { (smoothed[it].coerceIn(0f, 1f) * MAX_LIGHT).roundToInt() }
    }

    private fun spectrumTargets(): FloatArray? {
        val fft = latestFft ?: return null
        val bins = fft.size
        if (bins < 8) return null

        val out = FloatArray(ZONE_COUNT)
        var peak = 0f
        for (z in 0 until ZONE_COUNT) {
            val lo = binEdge(z, bins)
            val hi = binEdge(z + 1, bins).coerceAtLeast(lo + 1)
            var sum = 0f
            for (k in lo until hi.coerceAtMost(bins)) sum += fft[k]
            val avg = sum / (hi - lo)
            val v = ln(1f + avg) / ln(1f + 128f)
            out[z] = v
            if (v > peak) peak = v
        }
        applyAgc(out, peak)
        // Bass at the bottom of the bar reads more naturally.
        return FloatArray(ZONE_COUNT) { out[ZONE_COUNT - 1 - it] }
    }

    /** Log-spaced edges: musical energy bunches low, so linear bands look dead. */
    private fun binEdge(z: Int, bins: Int): Int {
        val frac = z / ZONE_COUNT.toFloat()
        return (bins.toFloat().pow(frac)).toInt().coerceIn(0, bins - 1)
    }

    private fun levelTargets(): FloatArray? {
        val rms = latestRms
        if (rms <= 0f && latestFft == null) return null
        val v = applyAgcScalar(ln(1f + rms * 8f) / ln(9f))
        val out = FloatArray(ZONE_COUNT)
        val lit = v * ZONE_COUNT
        for (z in 0 until ZONE_COUNT) {
            val fromBottom = ZONE_COUNT - 1 - z
            out[z] = (lit - fromBottom).coerceIn(0f, 1f)
        }
        return out
    }

    private fun bassTargets(): FloatArray? {
        val fft = latestFft ?: return null
        val bins = fft.size
        if (bins < 8) return null
        var sum = 0f
        val hi = (bins / 16).coerceAtLeast(2)
        for (k in 1 until hi) sum += fft[k]
        val avg = sum / (hi - 1)
        val v = applyAgcScalar(ln(1f + avg) / ln(1f + 128f))
        return FloatArray(ZONE_COUNT) { v }
    }

    private fun applyAgc(values: FloatArray, peak: Float) {
        agc = if (peak > agc) agc + (peak - agc) * 0.3f else agc + (peak - agc) * 0.02f
        val gain = 1f / agc.coerceAtLeast(0.05f)
        for (i in values.indices) values[i] = (values[i] * gain).coerceIn(0f, 1f)
    }

    private fun applyAgcScalar(v: Float): Float {
        agc = if (v > agc) agc + (v - agc) * 0.3f else agc + (v - agc) * 0.02f
        return (v / agc.coerceAtLeast(0.05f)).coerceIn(0f, 1f)
    }
}
