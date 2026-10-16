package com.happyboy.tokay.demo

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.policy.RetryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 演示应用的 Application。
 *
 * `GeckoSdk.init(...)` 必须在任何 `HybridWebView` 构造**之前**（`installState` 未初始化时会抛
 * `IllegalStateException`），随后在主进程调用一次 `GeckoSdk.initializePrewarm(...)`。
 * **只在主进程初始化**：Gecko 的内容子进程同样会走 `Application.onCreate`。
 */
class DemoApplication : Application() {

    companion object {
        private const val TAG = "DemoApplication"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 库里的 `ProcessUtils` 是 `internal`，演示应用拿不到，只能自己来一遍。 */
    private fun getCurrentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName().takeIf { it.isNotBlank() }?.let { return it }
        }
        val pid = Process.myPid()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (process in manager.runningAppProcesses) {
            if (process.pid == pid) {
                return process.processName
            }
        }
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // Gecko 内容子进程也会走到这里，它不该自己再装一遍内核。
        if (packageName != getCurrentProcessName()) return

        initializePrewarm()

        // 内核来源二选一：随包（APK assets 里那份，不需要地址与公钥）或远程 OTA（地址与验签公钥
        // 由构建期注入或调用方显式给出）。两者由同一个构建开关决定，见 demo/build.gradle。
        GeckoSdk.init(
            application = this,
            retryConfig = RetryConfig.DEFAULT,
            kernelSource = if (BuildConfig.PACKAGED_KERNEL) KernelSource.Packaged else KernelSource.DEFAULT,
        )

        observeInstallState()
    }

    private fun observeInstallState() {
        applicationScope.launch {
            GeckoSdk.installState.collect { state ->
                when (state) {
                    is InstallState.Idle -> Log.d(LOG_TAG, "installState: Idle")
                    is InstallState.Checking -> Log.d(LOG_TAG, "installState: Checking")
                    is InstallState.Downloading ->
                        Log.d(LOG_TAG, "installState: Downloading ${(state.progress * 100).toInt()}%")
                    is InstallState.Validating -> Log.d(LOG_TAG, "installState: Validating")
                    is InstallState.Extracting ->
                        Log.d(LOG_TAG, "installState: Extracting ${(state.progress * 100).toInt()}%")
                    is InstallState.Success -> Log.d(LOG_TAG, "installState: Success")
                    is InstallState.Failed ->
                        Log.w(LOG_TAG, "installState: Failed - ${state.error.message}")
                    is InstallState.Retrying ->
                        Log.w(
                            LOG_TAG,
                            "installState: Retrying ${state.attemptNumber}/${state.maxAttempts}"
                        )
                }
            }
        }
    }

    /** 注册 Activity 生命周期回调并准备内部组件。 */
    private fun initializePrewarm() {
        try {
            GeckoSdk.initializePrewarm(this)
            val status = GeckoSdk.prewarmStatus
            Log.i(
                TAG,
                "预热调度器就绪: WebView=${status.webViewReady}, GeckoView=${status.geckoViewReady}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "初始化预热调度器失败", e)
        }
    }
}
