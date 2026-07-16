package com.kosta.glyphbar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Detects swings for the POV display.
 *
 * The gyroscope is the right sensor here, not the accelerometer: what matters
 * is angular rate about the phone's long axis, which is what actually sweeps
 * the bar through the air. Gravity contaminates accelerometer readings and
 * would need filtering; gyro Y is already zero-centred. Falls back to the
 * accelerometer if no gyro exists.
 */
class MotionDetector(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasGyro: Boolean get() = gyro != null
    val hasAnySensor: Boolean get() = gyro != null || accel != null

    /** rad/s (gyro) or m/s^2 (accel fallback). Signed: sign = swing direction. */
    @Volatile var rate: Float = 0f
        private set

    /** Peak |rate| seen since the last reset — used to auto-scale the threshold. */
    @Volatile var peak: Float = 0f
        private set

    private var usingGyro = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> e.values[1]          // rotation about the long axis
                Sensor.TYPE_ACCELEROMETER -> e.values[0]      // lateral accel
                else -> return
            }
            // Light smoothing: raw gyro is noisy enough to cause false triggers.
            rate = rate * 0.35f + v * 0.65f
            val a = abs(rate)
            peak = if (a > peak) a else peak * 0.995f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): Boolean {
        val s = gyro ?: accel ?: return false
        usingGyro = s.type == Sensor.TYPE_GYROSCOPE
        // FASTEST: swing detection latency directly becomes text skew.
        return manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        manager.unregisterListener(listener)
        rate = 0f
        peak = 0f
    }

    /** Default trigger threshold in the active sensor's units. */
    fun defaultThreshold(): Float = if (usingGyro) 4.0f else 6.0f

    val unit: String get() = if (usingGyro) "rad/s" else "m/s²"
}
