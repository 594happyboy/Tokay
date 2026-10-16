package com.happyboy.tokay.utils

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/** 进程工具：用于保证 SDK 只在主进程初始化。 */
internal object ProcessUtils {
    private const val TAG = "ProcessUtils"

    fun isMainProcess(context: Context): Boolean {
        return try {
            val packageName = context.packageName
            val processName = getCurrentProcessName(context)
            val isMain = packageName == processName
            Log.d(TAG, "当前进程: $processName, 主进程: $packageName, 是否主进程: $isMain")
            isMain
        } catch (e: Exception) {
            Log.e(TAG, "检查进程失败", e)
            false
        }
    }

    fun getCurrentProcessName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName().takeIf { it.isNotBlank() }?.let { return it }
            }
            val pid = Process.myPid()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses?.find { it.pid == pid }?.processName
        } catch (e: Exception) {
            Log.e(TAG, "获取进程名失败", e)
            null
        }
    }
}
