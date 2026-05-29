package com.hrbroadcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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

class HeartRateService : LifecycleService(), SensorEventListener {

    companion object {
        const val TAG = "HeartRateService"
        const val CHANNEL_ID = "heart_rate_channel"
        const val NOTIFICATION_ID = 1001
        private const val BLE_UPDATE_INTERVAL_MS = 500L
        const val ACTION_HEART_RATE_UPDATED = "com.hrbroadcast.HEART_RATE_UPDATED"
        const val EXTRA_HEART_RATE_VALUE = "heart_rate_value"
        const val EXTRA_IS_WEARING = "is_wearing"

        var isRunning = false
            private set
        var currentHeartRate: Int = 0
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionReceiver: BroadcastReceiver? = null
    private var sensorManager: SensorManager? = null
    private var sensorRegistered = false
    private var lastBleUpdateTime: Long = 0
    private var lastHeartRateTime: Long = 0
    private var hasBroadcastZeroHeartRate: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        initWakeLock()
        registerSensorIfNeeded()
        setupConnectionReceiver()
        startWearingCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HRBroadcast::HeartRateWakeLock"
        )
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L)
                Log.d(TAG, "WakeLock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != HeartRateBleService.ACTION_CONNECTION_STATE_CHANGED) return
                val count = intent.getIntExtra(HeartRateBleService.EXTRA_CONNECTION_COUNT, 0)
                Log.d(TAG, "Connection state changed: count=$count")
                if (count > 0) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
            }
        }
        val filter = IntentFilter(HeartRateBleService.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, filter)
        }
    }

    private fun registerSensorIfNeeded() {
        if (sensorRegistered) return
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        val heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        heartRateSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            sensorRegistered = true
            Log.d(TAG, "Sensor listener registered")
        } ?: Log.w(TAG, "No heart rate sensor found")
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(this)
        sensorRegistered = false
        currentHeartRate = 0
        Log.d(TAG, "Sensor listener unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_HEART_RATE) {
                val heartRate = it.values[0].toInt()
                if (heartRate > 0) {
                    currentHeartRate = heartRate
                    hasBroadcastZeroHeartRate = false
                    lastHeartRateTime = System.currentTimeMillis()
                    broadcastHeartRate(heartRate, true)

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
        handler.post(wearingCheckRunnable)
    }

    private fun broadcastHeartRate(heartRate: Int, isWearing: Boolean) {
        val intent = Intent(ACTION_HEART_RATE_UPDATED).apply {
            putExtra(EXTRA_HEART_RATE_VALUE, heartRate)
            putExtra(EXTRA_IS_WEARING, isWearing)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "broadcastHeartRate: hr=$heartRate, wearing=$isWearing")
    }

    private val wearingCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val timeSinceLastHeartRate = System.currentTimeMillis() - lastHeartRateTime
            if (timeSinceLastHeartRate > 5000 && lastHeartRateTime > 0) {
                if (HeartRateBleService.isAdvertising && !hasBroadcastZeroHeartRate) {
                    HeartRateBleService.updateHeartRate(0)
                    hasBroadcastZeroHeartRate = true
                    broadcastHeartRate(0, true)
                    Log.d(TAG, "Wearing check: broadcasting heart rate 0")
                }
            }
            handler.postDelayed(this, 5000)
        }
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
        handler.removeCallbacks(wearingCheckRunnable)
        unregisterSensor()
        releaseWakeLock()
        connectionReceiver?.let { unregisterReceiver(it) }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
