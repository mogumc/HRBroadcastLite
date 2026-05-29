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
import android.os.Handler
import android.os.Looper
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
    private var currentHeartRate: Int = 0
    private var isWearing: Boolean = false
    private var lastHeartRateTime: Long = 0
    private val wearingCheckHandler = Handler(Looper.getMainLooper())
    @Volatile private var isWearingCheckRunning = false

    private val advertisingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HeartRateBleService.ACTION_ADVERTISING_STATE_CHANGED) {
                val isAdvertising = intent.getBooleanExtra(HeartRateBleService.EXTRA_IS_ADVERTISING, false)
                Log.d(TAG, "advertisingReceiver: isAdvertising=$isAdvertising")
                updateBroadcastButton()
            }
        }
    }

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

        setupBroadcastButton()
        checkAndRequestPermissions()
    }

    private fun setupBroadcastButton() {
        binding.broadcastButton.setOnClickListener {
            Log.d(TAG, "Broadcast button clicked, isAdvertising=${HeartRateBleService.isAdvertising}")
            binding.broadcastButton.isEnabled = false
            if (HeartRateBleService.isAdvertising) {
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
                    updateBroadcastButton()
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
            updateBroadcastButton()
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "startBroadcast: Bluetooth is not enabled")
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            updateBroadcastButton()
            return
        }
        
        if (bluetoothAdapter.bluetoothLeAdvertiser == null) {
            Log.e(TAG, "startBroadcast: BLE advertiser is null")
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            updateBroadcastButton()
            return
        }
        
        Log.d(TAG, "startBroadcast: Bluetooth is ready, starting services with heartRate=$currentHeartRate")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, HeartRateService::class.java))
        } else {
            startService(Intent(this, HeartRateService::class.java))
        }
        
        val bleIntent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_START_BROADCAST
            putExtra(HeartRateBleService.EXTRA_HEART_RATE, currentHeartRate)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bleIntent)
        } else {
            startService(bleIntent)
        }
        
        updateBroadcastButton()
        updateHeartRateDisplay(currentHeartRate, isWearing)

        enableBootReceiver()
        
        Toast.makeText(this, R.string.broadcasting, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "startBroadcast: Service started")
    }

    private fun stopBroadcast() {
        Log.d(TAG, "stopBroadcast: Stopping services")
        
        val intent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_STOP_BROADCAST
        }
        startService(intent)

        stopService(Intent(this, HeartRateService::class.java))

        disableBootReceiver()

        updateBroadcastButton()
        updateHeartRateDisplay(0, false)
        Log.d(TAG, "stopBroadcast: Services stopped")
    }

    private fun enableBootReceiver() {
        val component = android.content.ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "BootReceiver enabled")
    }

    private fun disableBootReceiver() {
        val component = android.content.ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "BootReceiver disabled")
    }

    private fun updateBroadcastButton() {
        val advertising = HeartRateBleService.isAdvertising
        Log.d(TAG, "updateBroadcastButton: isAdvertising=$advertising")
        if (advertising) {
            binding.broadcastButton.text = getString(R.string.stop_broadcast)
            binding.broadcastButton.setBackgroundResource(R.drawable.btn_broadcast_stop)
        } else {
            binding.broadcastButton.text = getString(R.string.start_broadcast)
            binding.broadcastButton.setBackgroundResource(R.drawable.btn_broadcast)
        }
        binding.broadcastButton.isEnabled = true
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
        if (heartRateSensor == null || sensorManager == null) return false
        val registered = sensorManager?.registerListener(
            this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL
        ) ?: false
        if (registered) {
            Log.d(TAG, "registerSensorListener: Success")
        }
        return registered
    }

    private fun startWearingCheck() {
        if (isWearingCheckRunning) return
        isWearingCheckRunning = true
        wearingCheckHandler.post(wearingCheckRunnable)
    }

    private val wearingCheckRunnable = object : Runnable {
        override fun run() {
            if (!isWearingCheckRunning) return

            val currentTime = System.currentTimeMillis()
            val timeSinceLastHeartRate = currentTime - lastHeartRateTime

            if (timeSinceLastHeartRate > 5000 && isWearing) {
                isWearing = false
                if (HeartRateBleService.isAdvertising) {
                    updateHeartRateDisplay(0, false)
                }
            }

            wearingCheckHandler.postDelayed(this, 5000)
        }
    }

    private fun stopWearingCheck() {
        isWearingCheckRunning = false
        wearingCheckHandler.removeCallbacks(wearingCheckRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_HEART_RATE) {
                val heartRate = it.values[0].toInt()
                Log.d(TAG, "onSensorChanged: Heart rate = $heartRate")
                if (heartRate > 0) {
                    currentHeartRate = heartRate
                    isWearing = true
                    lastHeartRateTime = System.currentTimeMillis()

                    if (HeartRateBleService.isAdvertising) {
                        updateHeartRateDisplay(heartRate, true)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateHeartRateDisplay(heartRate: Int, isWearing: Boolean) {
        if (!HeartRateBleService.isAdvertising) {
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
        updateBroadcastButton()
        updateHeartRateDisplay(currentHeartRate, isWearing)

        val filter = IntentFilter(HeartRateBleService.ACTION_ADVERTISING_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(advertisingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(advertisingReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWearingCheck()
        sensorManager?.unregisterListener(this)
        try {
            unregisterReceiver(advertisingReceiver)
        } catch (_: Exception) {}
    }
}
