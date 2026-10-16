package com.happyboy.tokay.scheduler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.util.Log
import android.view.Choreographer
import android.webkit.WebView
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.happyboy.tokay.GeckoSdk

/** 预热统一入口：在不影响首屏渲染的前提下，尽量提前完成 Gecko 关键组件初始化。 */
internal object PrewarmManager {
    
    private const val TAG = "PrewarmManager"
    
    internal val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prewarmMutex = Mutex()
    private val hasScheduledForegroundPrewarm = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedActivities = AtomicInteger(0)
    
    private lateinit var application: Application
    internal lateinit var prewarmEngine: PrewarmEngine
    internal lateinit var prewarmIntelligence: PrewarmIntelligence
    internal lateinit var prewarmUtils: PrewarmUtils
    
    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            Log.d(TAG, "Activity创建: ${activity.javaClass.simpleName}")
        }
        
        override fun onActivityStarted(activity: Activity) {
            val count = startedActivities.incrementAndGet()
            if (count == 1) {
                prewarmUtils.setAppInForeground(true)
            }
        }
        
        override fun onActivityResumed(activity: Activity) {
            Log.d(TAG, "Activity恢复: ${activity.javaClass.simpleName}")

            // 仅在「进程首次进入前台」时调度一次：下一帧 + 空闲时执行，尽早但不抢首屏。
            scheduleFirstForegroundPrewarmIfNeeded()
        }
        
        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {
            val count = startedActivities.decrementAndGet()
            if (count <= 0) {
                startedActivities.set(0)
                prewarmUtils.setAppInForeground(false)
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    
    fun initialize(app: Application) {
        if (isInitialized.compareAndSet(false, true)) {
            this.application = app
            
            prewarmUtils = PrewarmUtils(app)
            prewarmIntelligence = PrewarmIntelligence(prewarmUtils)
            prewarmEngine = PrewarmEngine(app)
            
            registerActivityLifecycleCallbacks()
            
            Log.i(TAG, "预热管理器初始化完成")
        }
    }
    
    /**
     * 触发预热。[urgency] 决定积极程度：默认 [PrewarmUrgency.PREDICTIVE]（机会性，只保证 Runtime，
     * 受冷却限制）；显式的手动入口传 [PrewarmUrgency.USER_ACTION]（连 Session/View 一起，忽略冷却，
     * 内核未就绪时允许兜底系统 WebView）。两条路径都仍受电量与内存判定约束。
     */
    fun prewarm(urgency: PrewarmUrgency = PrewarmUrgency.PREDICTIVE) {
        if (!isInitialized.get()) {
            Log.w(TAG, "管理器未初始化，跳过预热")
            return
        }
        
        requestPrewarm(trigger = PrewarmTrigger.MANUAL, urgency = urgency)
    }

    /**
     * 网页场景临近提示（补刀触发）：Gecko 已就绪时优先预热 Gecko；未就绪时仅在用户
     * 动作触发下预热系统 WebView 作为兜底。
     */
    fun hintWebSceneSoon(context: Context, urgency: PrewarmUrgency = PrewarmUrgency.USER_ACTION) {
        if (!isInitialized.get()) {
            Log.w(TAG, "管理器未初始化，忽略网页场景提示")
            return
        }
        Log.d(TAG, "网页场景临近提示: ${context.javaClass.simpleName}, urgency=$urgency")
        requestPrewarm(trigger = PrewarmTrigger.USER_HINT, urgency = urgency)
    }

    fun getWebView(): WebView? {
        if (!isInitialized.get()) return null
        
        return prewarmEngine.getWebView()?.also {
            Log.i(TAG, "获取到预热的WebView实例")
        }
    }
    
    fun getGeckoView(): GeckoView? {
        if (!isInitialized.get()) return null
        
        return prewarmEngine.getGeckoView()?.also {
            Log.i(TAG, "获取到预热的GeckoView实例")
        }
    }
    
    fun getGeckoSession(): GeckoSession? {
        return if (isInitialized.get()) {
            prewarmEngine.getGeckoSession()?.also {
                Log.i(TAG, "获取到预热的GeckoSession实例")
            }
        } else null
    }
    
    fun getGeckoRuntime(): GeckoRuntime? {
        return if (isInitialized.get()) prewarmEngine.getGeckoRuntime() else null
    }
    
    fun getStatus(): PrewarmStatus {
        if (!isInitialized.get()) {
            return PrewarmStatus()
        }

        return PrewarmStatus(
            webViewReady = prewarmEngine.isWebViewReady(),
            geckoViewReady = prewarmEngine.isGeckoViewReady(),
            geckoSessionReady = prewarmEngine.isGeckoSessionReady(),
            geckoRuntimeReady = prewarmEngine.isGeckoRuntimeReady(),
            lastPrewarmTime = prewarmUtils.getLastPrewarmTime()
        )
    }
    
    fun destroy() {
        if (isInitialized.compareAndSet(true, false)) {
            runCatching {
                application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
            }
            
            scope.cancel()
            prewarmEngine.cleanup()
            prewarmUtils.cleanup()
            Log.i(TAG, "预热管理器已销毁")
        }
    }
    
    private fun requestPrewarm(trigger: PrewarmTrigger, urgency: PrewarmUrgency) {
        scope.launch {
            prewarmMutex.withLock {
                executeIntelligentPrewarm(trigger = trigger, urgency = urgency)
            }
        }
    }

    private fun scheduleFirstForegroundPrewarmIfNeeded() {
        if (!isInitialized.get()) return
        if (!hasScheduledForegroundPrewarm.compareAndSet(false, true)) return

        mainHandler.post {
            runCatching {
                Choreographer.getInstance().postFrameCallback {
                    Looper.myQueue().addIdleHandler(MessageQueue.IdleHandler {
                        requestPrewarm(trigger = PrewarmTrigger.APP_FOREGROUND, urgency = PrewarmUrgency.PREDICTIVE)
                        false
                    })
                }
            }.onFailure { e ->
                Log.w(TAG, "使用 Choreographer/IdleHandler 调度预热失败，退化为延迟调度", e)
                mainHandler.postDelayed(
                    { requestPrewarm(trigger = PrewarmTrigger.APP_FOREGROUND, urgency = PrewarmUrgency.PREDICTIVE) },
                    300L
                )
            }
        }
    }

    private suspend fun executeIntelligentPrewarm(
        trigger: PrewarmTrigger,
        urgency: PrewarmUrgency
    ) {
        try {
            val geckoReady = GeckoSdk.isInstalled

            if (!prewarmIntelligence.shouldPrewarmNow(urgency)) {
                Log.d(TAG, "当前不适合预热，跳过")
                return
            }
            
            val strategy = prewarmIntelligence.getPrewarmStrategy(urgency)
            Log.d(TAG, "执行预热策略: $strategy (trigger=$trigger, urgency=$urgency, geckoReady=$geckoReady)")
            
            var didWork = false

            if (geckoReady) {
                // Gecko 优先：只要已就绪，永不走 WebView 预热。
                when (strategy) {
                    PrewarmStrategy.GECKO_AGGRESSIVE -> {
                        Log.d(TAG, "积极 Gecko 预热（Runtime + Session/View）")
                        didWork = prewarmEngine.prewarmGeckoView(aggressive = true)
                    }

                    PrewarmStrategy.GECKO_NORMAL -> {
                        Log.d(TAG, "普通 Gecko 预热（Runtime）")
                        didWork = prewarmEngine.prewarmGeckoView(aggressive = false)
                    }

                    PrewarmStrategy.DELAYED_GECKO -> {
                        Log.d(TAG, "延迟 Gecko 预热（等待空闲窗口）")
                        delay(1500)
                        if (prewarmIntelligence.shouldPrewarmNow(urgency)) {
                            didWork = prewarmEngine.prewarmGeckoView(aggressive = false)
                        }
                    }

                    PrewarmStrategy.SKIP -> {
                        Log.d(TAG, "跳过 Gecko 预热：策略=$strategy")
                    }
                }
            } else {
                // Gecko 未就绪：仅在用户动作触发时，预热系统 WebView 作为兜底
                if (urgency == PrewarmUrgency.USER_ACTION) {
                    Log.d(TAG, "Gecko 未就绪，用户动作触发：预热系统 WebView 兜底")
                    didWork = prewarmEngine.prewarmWebView()
                } else {
                    Log.d(TAG, "Gecko 未就绪，预测性触发：跳过预热")
                }
            }

            if (didWork) {
                prewarmUtils.markPrewarmTime()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行智能预热失败", e)
        }
    }
    
    private fun registerActivityLifecycleCallbacks() {
        try {
            application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
            Log.d(TAG, "Activity生命周期监听已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册Activity生命周期监听失败", e)
        }
    }
    
}

data class PrewarmStatus(
    val webViewReady: Boolean = false,
    val geckoViewReady: Boolean = false,
    val geckoSessionReady: Boolean = false,
    val geckoRuntimeReady: Boolean = false,
    val lastPrewarmTime: Long = 0L
)

internal enum class PrewarmTrigger {
    APP_FOREGROUND,
    USER_HINT,
    MANUAL
}

internal enum class PrewarmUrgency {
    PREDICTIVE,
    USER_ACTION
}

internal enum class PrewarmStrategy {
    GECKO_NORMAL,
    GECKO_AGGRESSIVE,
    DELAYED_GECKO,
    SKIP
}
