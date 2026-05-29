package com.hrbroadcast

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hrbroadcast.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var localHeartRate: Int = 0
    private var localIsWearing: Boolean = false

    companion object {
        private const val TAG = "MainActivity"
        private const val BODY_SENSORS_PERMISSION_REQUEST_CODE = 1001
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1002
    }

    // 广播按钮状态更新
    private val advertisingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HeartRateBleService.ACTION_ADVERTISING_STATE_CHANGED) {
                val isAdvertising = intent.getBooleanExtra(HeartRateBleService.EXTRA_IS_ADVERTISING, false)
                Log.d(TAG, "advertisingReceiver: isAdvertising=$isAdvertising")
                updateBroadcastButton()
                updateHeartRateDisplay()
            }
        }
    }

    // 心率数据更新（来自 HeartRateService）
    private val heartRateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HeartRateService.ACTION_HEART_RATE_UPDATED) {
                val hr = intent.getIntExtra(HeartRateService.EXTRA_HEART_RATE_VALUE, 0)
                val wearing = intent.getBooleanExtra(HeartRateService.EXTRA_IS_WEARING, false)
                localHeartRate = hr
                localIsWearing = wearing
                Log.d(TAG, "heartRateReceiver: hr=$hr, wearing=$wearing")
                if (HeartRateBleService.isAdvertising) {
                    updateHeartRateDisplay()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: Activity created")
        setupBroadcastButton()
        setupSettingsButton()
        checkBodySensorsPermission()
    }

    private fun checkBodySensorsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "BODY_SENSORS permission not granted, requesting")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BODY_SENSORS),
                    BODY_SENSORS_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "BODY_SENSORS permission already granted")
            }
        }
    }

    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
            BODY_SENSORS_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Body sensors permission granted")
                } else {
                    Log.w(TAG, "Body sensors permission denied")
                    Toast.makeText(this, "需要身体传感器权限才能读取心率", Toast.LENGTH_LONG).show()
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

        Log.d(TAG, "startBroadcast: Bluetooth is ready, starting services with heartRate=${HeartRateService.currentHeartRate}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, HeartRateService::class.java))
        } else {
            startService(Intent(this, HeartRateService::class.java))
        }

        val bleIntent = Intent(this, HeartRateBleService::class.java).apply {
            action = HeartRateBleService.ACTION_START_BROADCAST
            putExtra(HeartRateBleService.EXTRA_HEART_RATE, HeartRateService.currentHeartRate)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bleIntent)
        } else {
            startService(bleIntent)
        }

        updateBroadcastButton()
        updateHeartRateDisplay()

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

        updateBroadcastButton()
        updateHeartRateDisplay()
        Log.d(TAG, "stopBroadcast: Services stopped")
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

    private fun updateHeartRateDisplay() {
        if (!HeartRateBleService.isAdvertising) {
            binding.heartRateText.text = getString(R.string.no_heart_rate)
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.unit_text))
            return
        }

        if (localIsWearing && localHeartRate > 0) {
            binding.heartRateText.text = localHeartRate.toString()
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_text))
        } else {
            binding.heartRateText.text = getString(R.string.no_heart_rate)
            binding.heartRateText.setTextColor(ContextCompat.getColor(this, R.color.unit_text))
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        updateBroadcastButton()
        updateHeartRateDisplay()

        // 注册广播状态接收器
        val advFilter = IntentFilter(HeartRateBleService.ACTION_ADVERTISING_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(advertisingReceiver, advFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(advertisingReceiver, advFilter)
        }

        // 注册心率数据接收器
        val hrFilter = IntentFilter(HeartRateService.ACTION_HEART_RATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(heartRateReceiver, hrFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(heartRateReceiver, hrFilter)
        }

        // 从 HeartRateService 读取最新心率（Receiver 可能在上次 onPause 后发送过数据）
        localHeartRate = HeartRateService.currentHeartRate
        updateHeartRateDisplay()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: unregistering receivers")
        try {
            unregisterReceiver(advertisingReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(heartRateReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}
