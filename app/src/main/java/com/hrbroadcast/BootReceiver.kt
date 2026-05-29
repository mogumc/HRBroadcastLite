package com.hrbroadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            context?.let {
                val hrIntent = Intent(it, HeartRateService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.startForegroundService(hrIntent)
                } else {
                    it.startService(hrIntent)
                }

                val bleIntent = Intent(it, HeartRateBleService::class.java).apply {
                    action = HeartRateBleService.ACTION_START_BROADCAST
                    putExtra(HeartRateBleService.EXTRA_HEART_RATE, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.startForegroundService(bleIntent)
                } else {
                    it.startService(bleIntent)
                }
            }
        }
    }
}
