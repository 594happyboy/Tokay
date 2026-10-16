package com.happyboy.tokay

import android.app.Application
import android.util.Log
import android.content.Context
import android.webkit.WebView
import com.happyboy.tokay.core.GeckoInstaller
import com.happyboy.tokay.infrastructure.state.GeckoCoreHealthChecker
import com.happyboy.tokay.infrastructure.state.GeckoCoreStateStore
import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.models.requireUsable
import com.happyboy.tokay.domain.policy.RetryConfig
import com.happyboy.tokay.scheduler.PrewarmManager
import com.happyboy.tokay.scheduler.PrewarmUrgency
import com.happyboy.tokay.scheduler.PrewarmStatus
import com.happyboy.tokay.utils.DeviceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全库统一的 logcat 标签（`adb logcat -s Tokay`）。公开是为了让接入方的日志与库日志排在一起，
 * 因此不做成可配置项。
 */
const val LOG_TAG = "Tokay"

/**
 * 库的唯一初始化入口：`init()` 一次之后，订阅 [installState] 拿安装进度，用 `HybridWebView` 显示网页。
 */
object GeckoSdk {
    /** 内核在 `filesDir` 下的根目录名。 */
    const val ROOT_DIR_NAME = "geckoview"

    private var installer: GeckoInstaller? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prewarmJob: Job? = null

    lateinit var application: Application
        private set

    lateinit var rootDir: File
        private set

    private lateinit var metaDir: File
    private lateinit var stateStore: GeckoCoreStateStore

    /** 安装状态流；**未 [init] 时访问会抛 `IllegalStateException`**。 */
    val installState: StateFlow<InstallState>
        get() = installer?.installState
            ?: throw IllegalStateException("GeckoSdk 未初始化，请先调用 init()")

    /** 安装事件流（Started / Downloading / Extracting / Retrying / Completed / Failed）。 */
    val installEvents: Flow<GeckoInstaller.InstallEvent>
        get() = installer?.installEvents
            ?: throw IllegalStateException("GeckoSdk 未初始化，请先调用 init()")

    /**
     * 初始化并开始安装。
     *
     * [kernelSource] 决定内核从哪来、用哪把公钥验签：默认是「构建期注入的清单地址 + 内置**演示**
     * 公钥」，生产必须替换；`publicKeyP256 = null` 表示放弃验签（信任根只剩 TLS，会打 WARN）。
     * 传 `KernelSource.Packaged` 则完全不走 OTA：内核包就在本 APK 的 assets 里，信任根是 APK 签名。
     */
    fun init(
        application: Application,
        retryConfig: RetryConfig = RetryConfig.DEFAULT,
        kernelSource: KernelSource = KernelSource.DEFAULT,
        forceDowngrade: Boolean = false
    ) {
        // Remote 的地址为空 = 接入方没配。在这里立刻响亮失败，而不是等到下载阶段报"装不上内核"——
        // 后者会把"你没配地址"误读成"网络/签名/服务端坏了"。随包模式没有地址可配，不受这条约束。
        kernelSource.requireUsable()

        this.application = application
        this.rootDir = File(application.filesDir, ROOT_DIR_NAME)
        this.metaDir = File(rootDir, "meta")
        this.stateStore = GeckoCoreStateStore(metaDir)

        // 装之前先看本地那一代内核还能不能用（Java 版本代际不匹配就清掉）。
        GeckoCoreHealthChecker.ensureHealthyForCurrentJava(application)

        installer = GeckoInstaller(
            version = BuildConfig.GECKOVIEW_VERSION,
            abi = DeviceUtil.getDeviceAbiTag(),
            rootDir = rootDir,
            retryConfig = retryConfig,
            kernelSource = kernelSource,
            forceDowngrade = forceDowngrade,
            assetManager = application.assets
        )
        setupPrewarmListener()

        installer?.install(retryConfig.enabled)

        Log.d(LOG_TAG, "GeckoSdk 初始化完成，开始安装")
    }

    /** 重新安装（[init] 之后才有意义，未初始化时只打一条 WARN）。 */
    fun install(enableRetry: Boolean = true) {
        val currentInstaller = installer
        if (currentInstaller == null) {
            Log.w(LOG_TAG, "GeckoSdk 未初始化，请先调用 init()")
            return
        }

        currentInstaller.install(enableRetry)
    }

    fun cancel() {
        installer?.cancel()
    }

    /** 内核是否已装好可用（进程内状态）。要问"磁盘上有没有可用的内核"请用 [currentVersion]。 */
    val isInstalled: Boolean
        get() = installer?.isInstalled() ?: false

    /** 当前安装状态快照（本进程内存态）。 */
    val currentState: InstallState
        get() = installer?.installState?.value ?: InstallState.Idle

    /** 当前激活的 Gecko 版本号，来自磁盘元数据 `meta/state.json`，跨进程/冷启动有效。 */
    val currentVersion: String?
        get() = if (!::stateStore.isInitialized) null else stateStore.readState().currentCoreVersion

    /** 初始化预热管理器，建议在主进程的 `Application.onCreate` 里调用一次。 */
    fun initializePrewarm(application: Application) {
        PrewarmManager.initialize(application)
    }

    /**
     * 手动触发一次预热。**按「用户动作」处理**：Runtime、Session、View 一起预热，忽略 30s 冷却，
     * 内核未就绪时兜底预热系统 WebView；电量与内存不足仍会挡下（不是无条件执行）。
     *
     * 安装成功后的自动预热走的是内部的保守档（只保证 Runtime），与本方法不同——那时没人要打开网页，
     * 提前分配 Session/View 只是白占内存。
     */
    fun triggerPrewarm() {
        PrewarmManager.prewarm(PrewarmUrgency.USER_ACTION)
    }

    /**
     * 网页场景临近提示：在"即将进入网页"时调用可减少首次使用 Gecko 的等待。
     *
     * Gecko 已就绪则预热 Gecko；未就绪则只在用户动作触发时预热系统 WebView 兜底。
     */
    fun hintWebSceneSoon(context: Context, hint: WebSceneHint = WebSceneHint.USER_ACTION) {
        val urgency = when (hint) {
            WebSceneHint.PREDICTIVE -> PrewarmUrgency.PREDICTIVE
            WebSceneHint.USER_ACTION -> PrewarmUrgency.USER_ACTION
        }
        PrewarmManager.hintWebSceneSoon(context, urgency)
    }

    /** 当前预热状态摘要，供调试或测试界面展示，不要在核心业务逻辑里依赖。 */
    val prewarmStatus: PrewarmStatus
        get() = PrewarmManager.getStatus()

    /** 取一个已预热好的 WebView（没有可用的返回 null）；用完按正常方式销毁即可。 */
    fun getPrewarmedWebView(): WebView? {
        return PrewarmManager.getWebView()
    }

    fun destroy() {
        prewarmJob?.cancel()
        installer?.destroy()
        installer = null
        Log.d(LOG_TAG, "GeckoSdk 已销毁")
    }

    private fun setupPrewarmListener() {
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            installer?.installState?.collect { state ->
                if (state is InstallState.Success) {
                    Log.d(LOG_TAG, "安装成功，触发预热")
                    PrewarmManager.prewarm()
                }
            }
        }
    }
}

/** 网页场景提示类型，决定预热的积极程度。 */
enum class WebSceneHint {
    /** 预测性提示：列表曝光、预加载等场景，更保守。 */
    PREDICTIVE,

    /** 用户动作提示：用户已明确要进入网页，更积极。 */
    USER_ACTION
}
