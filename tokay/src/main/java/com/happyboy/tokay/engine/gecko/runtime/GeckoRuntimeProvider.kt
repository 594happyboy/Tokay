package com.happyboy.tokay.engine.gecko.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.happyboy.tokay.utils.ProcessUtils
import org.mozilla.geckoview.BuildConfig
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoRuntimeSettings.HTTPS_ONLY
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** GeckoRuntime 单例提供者；只在主进程初始化（ProcessUtils.isMainProcess），否则直接抛异常。 */
internal object GeckoRuntimeProvider {
    private const val TAG = "GeckoRuntimeProvider"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getOrCreate(context: Context): GeckoRuntime {
        validateMainProcess(context)
        val appContext = context.applicationContext

        return runOnMainThreadBlocking {
            getOrCreateOnMainThread(appContext)
        }
    }

    private fun getOrCreateOnMainThread(context: Context): GeckoRuntime {
        runtime?.let { return it }

        return synchronized(this) {
            runtime ?: createOrGetDefaultRuntime(context).also { runtime = it }
        }
    }
    
    private fun validateMainProcess(context: Context) {
        if (!ProcessUtils.isMainProcess(context)) {
            throw IllegalStateException("GeckoRuntime只能在主进程中创建")
        }
    }

    private fun createOrGetDefaultRuntime(context: Context): GeckoRuntime {
        Log.i(TAG, "获取GeckoRuntime默认单例")
        return GeckoRuntime.getDefault(context).also { runtime ->
            applyRuntimeSettings(runtime.settings)
        }
    }

    private fun applyRuntimeSettings(settings: GeckoRuntimeSettings) {
        settings.setJavaScriptEnabled(true)
        // GeckoView 只有「全放行 / HTTPS_ONLY / HTTPS_ONLY_PRIVATE」三档，没有「全部禁止」；
        // 不放行明文即 HTTPS_ONLY。SDK 资源走 127.0.0.1，回环地址不被 HTTPS-Only 升级。
        settings.setAllowInsecureConnections(HTTPS_ONLY)
        if (BuildConfig.DEBUG) {
            settings.setRemoteDebuggingEnabled(true)
            settings.setConsoleOutputEnabled(true)
        }
    }

    private fun <T> runOnMainThreadBlocking(task: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task()
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<T?>()
        val errorRef = AtomicReference<Throwable?>()

        mainHandler.post {
            try {
                resultRef.set(task())
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        errorRef.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return resultRef.get() as T
    }
}


