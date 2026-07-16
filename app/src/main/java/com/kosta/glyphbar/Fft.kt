package com.kosta.glyphbar

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

/**
 * Minimal in-place radix-2 FFT.
 *
 * Needed for microphone input: AudioRecord hands back raw PCM, and unlike the
 * Visualizer there's no system-provided spectrum. Small enough to not warrant
 * a dependency.
 */
object Fft {

    /** Hann window, precomputed per size — avoids recomputing cos() every frame. */
    private val windows = HashMap<Int, FloatArray>()

    fun hann(n: Int): FloatArray = windows.getOrPut(n) {
        FloatArray(n) { 0.5f * (1f - cos(2.0 * PI * it / (n - 1)).toFloat()) }
    }

    /**
     * @param re real input/output, mutated in place. Size must be a power of two.
     * @param im imaginary part, normally zeros on input.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum of real PCM samples. Returns n/2 bins. */
    fun magnitudes(samples: FloatArray): FloatArray {
        val n = samples.size
        val re = samples.copyOf()
        val im = FloatArray(n)
        val w = hann(n)
        for (i in 0 until n) re[i] *= w[i]
        transform(re, im)
        return FloatArray(n / 2) { hypot(re[it], im[it]) }
    }
}
