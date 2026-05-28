package com.hrbroadcast

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hrbroadcast.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentHeartRate: Int = 0
    private var isWearing: Boolean = false
    private var lastHeartRateTime: Long = 0
    private var isBroadcasting: Boolean = false
    private var bleStateReceiver: BleStateReceiver? = null
    private var hasBroadcastZeroHeartRate: Boolean = false

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: Activity created")

        setupWakeLock()
        setupBroadcastButton()
        checkAndRequestPermissions()
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HRBroadcast::HeartRateWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun setupBroadcastButton() {
        binding.broadcastButton.setOnClickListener {
            Log.d(TAG, "Broadcast button clicked, isBroadcasting=$isBroadcasting")
            if (isBroadcasting) {
                stopBroadcast()
            } else {
                checkBluetoothPermissionsAndStart()
            }
        }
        updateBroadcastButton()
    }

    private fun checkBluetoothPermissionsAndStart() {
        Log.d(TAG, "checkBluetoothPermissionsAndStart: SDK=${Build.VERSION.SDK_INT}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                Log.d(TAG, "BLUETOOTH_ADVERTISE permission not granted")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                Log.d(TAG, "BLUETOOTH_CONNECT permission not granted")
            }
            
            if (permissions.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions: $permissions")
                ActivityCompat.requestPermissions(
                    this,
                    permissions.toTypedArray(),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "All permissions granted, starting broadcast")
                startBroadcast()
            }
        } else {
            Log.d(TAG, "SDK < S, starting broadcast directly")
            startBroadcast()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: requestCode=$requestCode, grantResults=${grantResults.contentToString()}")
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Body sensors permission granted")
                    setupHeartRateSensor()
                } else {
                    Log.w(TAG, "Body sensors permission denied")
                    Toast.makeText(this, "需要身体传感器权限才能测量心率", Toast.LENGTH_LONG).show()
                }
            }
            BLUETOOTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d(TAG, "Bluetooth permissions granted")
                    startBroadcast()
                } else {
                    Log.w(TAG, "Bluetooth permissions denied")
                    Toast.makeText(this, "需要蓝牙权限才能广播心率", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startBroadcast() {
        Log.d(TAG, "startBroadcast: Starting BLE service")
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "startBroadcast: BluetoothAdapter is null")
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "startBroadcast: Bluetooth is not enabled")
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (bluetoothAdapter.bluetoothLeAdvertiser == null) {
            Log.e(TAG, "startBroadcast: BLE advertiser is null")
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "startBroadcast: Bluetooth is ready, starting service with heartRate=$currentHeartRate")
        
        val intent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_START_BROADCAST
            putExtra(HeartRateBleService.EXTRA_HEART_RATE, currentHeartRate)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isBroadcasting = true
        hasBroadcastZeroHeartRate = false
        updateBroadcastButton()
        updateHeartRateDisplay(currentHeartRate, isWearing)
        
        Toast.makeText(this, R.string.broadcasting, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "startBroadcast: Service started, isBroadcasting=$isBroadcasting")
    }

    private fun stopBroadcast() {
        Log.d(TAG, "stopBroadcast: Stopping BLE service")
        
        val intent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_STOP_BROADCAST
        }
        startService(intent)
        
        isBroadcasting = false
        updateBroadcastButton()
        updateHeartRateDisplay(0, false)
        Log.d(TAG, "stopBroadcast: Service stopped")
    }

    private fun updateBroadcastButton() {
        Log.d(TAG, "updateBroadcastButton: isBroadcasting=$isBroadcasting")
        if (isBroadcasting) {
            binding.broadcastButton.text = getString(R.string.stop_broadcast)
            binding.broadcastButton.setBackgroundResource(R.drawable.btn_broadcast_stop)
        } else {
            binding.broadcastButton.text = getString(R.string.start_broadcast)
            binding.broadcastButton.setBackgroundResource(R.drawable.btn_broadcast)
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BODY_SENSORS),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                setupHeartRateSensor()
            }
        } else {
            setupHeartRateSensor()
        }
    }

    private fun setupHeartRateSensor() {
        Log.d(TAG, "setupHeartRateSensor: Starting")
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        
        if (heartRateSensor != null) {
            registerSensorListener()
        }
        
        startWearingCheck()
    }

    private fun registerSensorListener(): Boolean {
        if (heartRateSensor == null) return false
        
        val delays = listOf(
            SensorManager.SENSOR_DELAY_FASTEST,
            SensorManager.SENSOR_DELAY_GAME,
            SensorManager.SENSOR_DELAY_UI,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        for (delay in delays) {
            val registered = sensorManager?.registerListener(
                this,
                heartRateSensor,
                delay
            ) ?: false
            
            if (registered) {
                Log.d(TAG, "registerSensorListener: Success")
                return true
            }
        }
        return false
    }

    private fun startWearingCheck() {
        Thread {
            while (true) {
                Thread.sleep(5000)
                val currentTime = System.currentTimeMillis()
                val timeSinceLastHeartRate = currentTime - lastHeartRateTime
                
                if (timeSinceLastHeartRate > 5000 && isWearing) {
                    isWearing = false
                    runOnUiThread {
                        if (isBroadcasting) {
                            if (!hasBroadcastZeroHeartRate) {
                                updateBleHeartRate(0)
                                hasBroadcastZeroHeartRate = true
                                Log.d(TAG, "Wearing state changed to not wearing, broadcasting heart rate 0")
                            }
                            updateHeartRateDisplay(0, false)
                        }
                    }
                }
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_HEART_RATE) {
                val heartRate = it.values[0].toInt()
                Log.d(TAG, "onSensorChanged: Heart rate = $heartRate")
                if (heartRate > 0) {
                    currentHeartRate = heartRate
                    isWearing = true
                    hasBroadcastZeroHeartRate = false
                    lastHeartRateTime = System.currentTimeMillis()
                    
                    if (isBroadcasting) {
                        updateBleHeartRate(heartRate)
                        runOnUiThread {
                            updateHeartRateDisplay(heartRate, true)
                        }
                    }
                }
            }
        }
    }

    private fun updateBleHeartRate(heartRate: Int) {
        Log.d(TAG, "updateBleHeartRate: $heartRate BPM")
        val intent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_UPDATE_HEART_RATE
            putExtra(HeartRateBleService.EXTRA_HEART_RATE, heartRate)
        }
        startService(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateHeartRateDisplay(heartRate: Int, isWearing: Boolean) {
        if (!isBroadcasting) {
            binding.heartRateText.text = getString(R.string.no_heart_rate)
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.unit_text))
            return
        }
        
        if (isWearing && heartRate > 0) {
            binding.heartRateText.text = heartRate.toString()
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_text))
        } else {
            binding.heartRateText.text = getString(R.string.no_heart_rate)
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.unit_text))
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED) {
            registerSensorListener()
        }
        isBroadcasting = HeartRateBleService.isAdvertising
        updateBroadcastButton()
        updateHeartRateDisplay(currentHeartRate, isWearing)
        registerBleStateReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterBleStateReceiver()
    }

    private fun registerBleStateReceiver() {
        bleStateReceiver = BleStateReceiver()
        val filter = IntentFilter().apply {
            addAction(HeartRateBleService.ACTION_AUTO_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bleStateReceiver, filter)
        }
        Log.d(TAG, "BLE state receiver registered")
    }

    private fun unregisterBleStateReceiver() {
        bleStateReceiver?.let {
            unregisterReceiver(it)
        }
        bleStateReceiver = null
        Log.d(TAG, "BLE state receiver unregistered")
    }

    inner class BleStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HeartRateBleService.ACTION_AUTO_STOP -> {
                    Log.d(TAG, "Auto stop received")
                    isBroadcasting = false
                    updateBroadcastButton()
                    updateHeartRateDisplay(0, false)
                    Toast.makeText(this@MainActivity, "无设备连接，已自动停止广播", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}
