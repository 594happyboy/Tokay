package com.happyboy.tokay.scheduler

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/** 预热工具：系统资源检查、时间控制与电池状态监听。 */
internal class PrewarmUtils(private val application: Application) {
    
    companion object {
        private const val TAG = "PrewarmUtils"
        private const val MIN_AVAILABLE_MEMORY_MB = 150L
        private const val MIN_PREWARM_INTERVAL_MS = 30_000L
    }
    
    private var lastPrewarmTime = 0L
    @Volatile private var appInForeground = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.d(TAG, "开始充电，可触发预热")
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = getBatteryLevelFromIntent(intent)
                    if (level > 50) {
                        Log.d(TAG, "电量充足($level%)，可触发预热")
                    }
                }
            }
        }
    }
    
    init {
        registerBatteryReceiver()
    }

    fun canPrewarmNow(ignoreCooldown: Boolean = false): Boolean {
        if (ignoreCooldown) return true

        val now = System.currentTimeMillis()
        val canPrewarm = now - lastPrewarmTime > MIN_PREWARM_INTERVAL_MS
        
        if (!canPrewarm) {
            val remainingTime = (MIN_PREWARM_INTERVAL_MS - (now - lastPrewarmTime)) / 1000
            Log.d(TAG, "预热冷却中，还需等待 ${remainingTime} 秒")
        }
        
        return canPrewarm
    }
    
    fun markPrewarmTime() {
        lastPrewarmTime = System.currentTimeMillis()
        Log.d(TAG, "标记预热时间: $lastPrewarmTime")
    }
    
    fun getLastPrewarmTime(): Long = lastPrewarmTime

    fun hasEnoughMemory(): Boolean {
        return runCatching {
            val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
            (availableMemoryMB > MIN_AVAILABLE_MEMORY_MB).also { hasEnough ->
                if (!hasEnough) {
                    Log.d(TAG, "内存不足: ${availableMemoryMB}MB < ${MIN_AVAILABLE_MEMORY_MB}MB")
                }
            }
        }.getOrElse {
            Log.w(TAG, "检查内存状态失败", it)
            true // 默认允许
        }
    }
    
    fun isAppInForeground(): Boolean {
        return appInForeground
    }

    fun setAppInForeground(isInForeground: Boolean) {
        appInForeground = isInForeground
    }
    
    fun getBatteryLevel(): Int {
        return try {
            val batteryManager = application.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "获取电池电量失败", e)
            50 // 安全默认值
        }
    }
    
    fun isCharging(): Boolean {
        return try {
            val batteryManager = application.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            Log.w(TAG, "检查充电状态失败", e)
            false
        }
    }
    
    private fun getBatteryLevelFromIntent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            getBatteryLevel()
        }
    }
    
    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            application.registerReceiver(batteryReceiver, filter)
            Log.d(TAG, "电池状态监听已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册电池监听失败", e)
        }
    }

    fun cleanup() {
        runCatching {
            application.unregisterReceiver(batteryReceiver)
        }
        Log.d(TAG, "工具类资源已清理")
    }
}
