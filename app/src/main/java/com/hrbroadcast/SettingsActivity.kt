package com.hrbroadcast

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.hrbroadcast.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "SettingsActivity"
        const val PREFS_NAME = "hr_broadcast_settings"
        const val KEY_AUTO_START = "auto_start_on_boot"

        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)
        binding.autoStartSwitch.isChecked = autoStart

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.autoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "autoStartSwitch: isChecked=$isChecked")
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()

            val component = ComponentName(this, BootReceiver::class.java)
            val newState = if (isChecked) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "BootReceiver component state: ${if (isChecked) "enabled" else "disabled"}")
        }
    }
}
