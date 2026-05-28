package com.hrbroadcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeartRateService : LifecycleService(), SensorEventListener {

    companion object {
        const val TAG = "HeartRateService"
        const val CHANNEL_ID = "heart_rate_channel"
        const val NOTIFICATION_ID = 1001
        private const val BLE_UPDATE_INTERVAL_MS = 500L

        var isRunning = false
            private set
        var currentHeartRate: Int = 0
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: ScreenReceiver? = null
    private var sensorManager: SensorManager? = null
    private var lastBleUpdateTime: Long = 0
    private var lastHeartRateTime: Long = 0
    private var hasBroadcastZeroHeartRate: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        setupWakeLock()
        setupScreenReceiver()
        setupSensor()
        startWearingCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    private fun setupSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        heartRateSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Sensor listener registered")
        } ?: Log.w(TAG, "No heart rate sensor found")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_HEART_RATE) {
                val heartRate = it.values[0].toInt()
                if (heartRate > 0) {
                    currentHeartRate = heartRate
                    hasBroadcastZeroHeartRate = false
                    lastHeartRateTime = System.currentTimeMillis()

                    if (HeartRateBleService.isAdvertising) {
                        val now = System.currentTimeMillis()
                        if (now - lastBleUpdateTime >= BLE_UPDATE_INTERVAL_MS) {
                            lastBleUpdateTime = now
                            HeartRateBleService.updateHeartRate(heartRate)
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startWearingCheck() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isRunning) return
                val timeSinceLastHeartRate = System.currentTimeMillis() - lastHeartRateTime
                if (timeSinceLastHeartRate > 5000 && lastHeartRateTime > 0) {
                    if (HeartRateBleService.isAdvertising && !hasBroadcastZeroHeartRate) {
                        HeartRateBleService.updateHeartRate(0)
                        hasBroadcastZeroHeartRate = true
                        Log.d(TAG, "Wearing check: broadcasting heart rate 0")
                    }
                }
                handler.postDelayed(this, 5000)
            }
        })
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HRBroadcast::HeartRateWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)

        lifecycleScope.launch {
            while (isRunning) {
                delay(9 * 60 * 1000)
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(10 * 60 * 1000L)
                    }
                }
            }
        }
    }

    private fun setupScreenReceiver() {
        screenReceiver = ScreenReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("心率监测服务运行中")
            .setSmallIcon(R.drawable.ic_heart)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "心率监测服务"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(this)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        screenReceiver?.let {
            unregisterReceiver(it)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
