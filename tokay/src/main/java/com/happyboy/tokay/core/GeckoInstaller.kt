package com.happyboy.tokay.core

import android.content.res.AssetManager
import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.models.KernelSource
import com.happyboy.tokay.domain.policy.RetryConfig
import com.happyboy.tokay.domain.policy.RetryPolicy
import com.happyboy.tokay.infrastructure.state.GeckoCoreStateStore
import com.happyboy.tokay.installer.acquisition.createKernelPackageAcquirer
import com.happyboy.tokay.installer.extractor.SevenZipExtractor
import com.happyboy.tokay.installer.validator.FileValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/** 安装生命周期的编排者：持有重试语义（见 [install]），并把状态与事件暴露给宿主。 */
class GeckoInstaller(
    private val version: String,
    private val abi: String,
    private val rootDir: File,
    private val retryConfig: RetryConfig = RetryConfig.DEFAULT,
    /**
     * 内核包的来源与信任根。默认是「构建期注入的清单地址 + 内置演示公钥」，演示公钥随仓库
     * 公开，生产必须替换；传 `publicKeyP256 = null` 表示显式放弃验签（信任根只剩 TLS，会打 WARN）。
     * [KernelSource.Packaged] 则是「内核包随 APK」，没有清单与验签。
     */
    kernelSource: KernelSource = KernelSource.DEFAULT,
    /** 显式允许安装低于已安装版本的清单（默认拒绝降级） */
    private val forceDowngrade: Boolean = false,
    /**
     * 安装流程的可替换引用（默认就是真实 orchestrator 的 `install`），存在的唯一理由是**可测性**：
     * `InstallOrchestrator` 是 internal 且 final，替换不掉，而它的失败路径要真实网络/真实 7z 才能触发。
     *
     * 契约：**失败必须抛异常**——把异常吞成 `Failed` 并正常结束，重试就不会发生。
     */
    installFlowOverride: ((
        version: String,
        abi: String,
        downloadDir: File,
        extractDir: File
    ) -> Flow<InstallState>)? = null,
    /**
     * 随包模式读 APK assets 的入口（`Application.assets`）。[KernelSource.Packaged] 下必须给，
     * 否则构造时立刻失败——不静默退化成「没装内核」。
     */
    assetManager: AssetManager? = null
) {

    private val stateManager = InstallStateManager()
    private val extractor = SevenZipExtractor()
    private val validator = FileValidator()
    private val packageAcquirer = createKernelPackageAcquirer(kernelSource, assetManager, validator)
    private val orchestrator = InstallOrchestrator(
        stateManager = stateManager,
        packageAcquirer = packageAcquirer,
        extractor = extractor,
        validator = validator,
        // 防降级要比对的是跨进程事实（meta/state.json），不是本进程内存态。
        installedVersionProvider = { stateStore.readState().currentCoreVersion },
        forceDowngrade = forceDowngrade
    )
    private val retryPolicy = RetryPolicy(retryConfig)

    private val installFlow: (version: String, abi: String, downloadDir: File, extractDir: File) -> Flow<InstallState> =
        installFlowOverride ?: orchestrator::install

    /**
     * 安装流程最近一次发出的状态，用来在失败时反推真实阶段。
     *
     * 不从 `stateManager` 读：那样判阶段就依赖状态机内部实现，而假流程只需遵守「发出状态」这一条
     * 契约。每次尝试开始时重置，避免残留上一次尝试的阶段。
     */
    private var lastEmittedState: InstallState = InstallState.Idle

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var installJob: Job? = null

    private val metaDir = File(rootDir, "meta")
    private val stateStore = GeckoCoreStateStore(metaDir)
    private val downloadDir = File(File(rootDir, "downloads"), version)
    private val extractDir = File(File(File(rootDir, "versions"), version), "libs")

    private val _installEvents = MutableSharedFlow<InstallEvent>(replay = 1)
    val installEvents: Flow<InstallEvent> = _installEvents.asSharedFlow()

    val installState: StateFlow<InstallState> = stateManager.state

    init {
        downloadDir.mkdirs()
        extractDir.mkdirs()
        Log.d(LOG_TAG, "GeckoInstaller 初始化 - 版本: $version, ABI: $abi")
    }

    /**
     * 开始安装。
     *
     * 重试语义在这里持有：安装流程**失败时抛异常**重试才看得到；每次重试前发 [InstallState.Retrying]；
     * **只有重试耗尽后**才发 [InstallState.Failed]。
     */
    fun install(enableRetry: Boolean = retryConfig.enabled) {
        if (stateManager.isInProgress()) {
            Log.w(LOG_TAG, "安装正在进行中，忽略重复请求")
            return
        }

        installJob?.cancel()
        installJob = scope.launch {
            _installEvents.emit(InstallEvent.Started)
            installWithRetry(enableRetry)
        }
    }

    /**
     * 执行安装并在重试耗尽后收场。
     *
     * 从 [install] 拆出来是为了单测能直接驱动：本类作用域绑在 `Dispatchers.Main`，而 JVM 单测里
     * 没有主线程 Looper，`scope.launch` 会直接抛 `IllegalStateException`。
     */
    internal suspend fun installWithRetry(enableRetry: Boolean = retryConfig.enabled) {
        val result = if (enableRetry) {
            retryPolicy.executeWithRetry(
                onRetrying = { attemptNumber, delayMs, lastError ->
                    val retryingState = InstallState.Retrying(
                        version = version,
                        attemptNumber = attemptNumber,
                        maxAttempts = retryConfig.maxAttempts,
                        delayMs = delayMs,
                        lastError = lastError
                    )
                    stateManager.updateState(retryingState)
                    _installEvents.tryEmit(InstallEvent.Retrying(attemptNumber, retryConfig.maxAttempts, lastError))
                }
            ) { attemptNumber ->
                Log.d(LOG_TAG, "开始安装（尝试 $attemptNumber）")
                executeInstall()
            }
        } else {
            runCatching {
                executeInstall()
            }
        }

        val error = result.exceptionOrNull() ?: return
        // 取消不是失败，不发 Failed（否则宿主会看到一次"安装失败"，而其实是它自己取消的）。
        // RetryPolicy 的 catch 是 Exception，CancellationException 也在其中，所以这里要挡一道。
        if (error is CancellationException) throw error
        reportFailure(error)
    }

    /** 重试已耗尽（或未启用重试）：这时才把失败报出去。 */
    private suspend fun reportFailure(e: Throwable) {
        Log.e(LOG_TAG, "安装失败（重试已耗尽）", e)
        val failedState = InstallState.Failed(
            version = version,
            error = e,
            // 必须是真实阶段：写死 CHECKING 会把下载/校验/解压阶段的失败全报成 CHECKING。
            phase = orchestrator.determineFailurePhase(lastEmittedState)
        )
        stateManager.updateState(failedState)
        _installEvents.emit(InstallEvent.Failed(e))
    }

    private suspend fun executeInstall() {
        // 失败阶段只反映"这一次"走到哪
        lastEmittedState = InstallState.Idle
        installFlow(version, abi, downloadDir, extractDir)
            .onEach { state ->
                lastEmittedState = state
                when (state) {
                    is InstallState.Downloading -> {
                        _installEvents.emit(InstallEvent.Downloading(state.progress))
                    }
                    is InstallState.Extracting -> {
                        _installEvents.emit(InstallEvent.Extracting(state.progress))
                    }
                    is InstallState.Success -> {
                        stateStore.updateOnInstallSuccess(version)
                        _installEvents.emit(InstallEvent.Completed)
                    }
                    is InstallState.Failed -> {
                        _installEvents.emit(InstallEvent.Failed(state.error))
                    }
                    else -> {
                    }
                }
            }
            .collect { state ->
                Log.d(LOG_TAG, "收到安装状态: ${state::class.simpleName}")
            }
    }

    fun cancel() {
        installJob?.cancel()
        orchestrator.cancel()
        Log.d(LOG_TAG, "安装已取消")
    }

    fun isInstalled(): Boolean {
        return stateManager.isSuccess()
    }

    fun destroy() {
        scope.coroutineContext.cancelChildren()
        stateManager.reset()
        Log.d(LOG_TAG, "GeckoInstaller 已销毁")
    }

    /** 安装过程中的关键事件，供不做状态机分支的宿主使用。 */
    sealed class InstallEvent {
        object Started : InstallEvent()
        data class Downloading(val progress: Float) : InstallEvent()
        data class Extracting(val progress: Float) : InstallEvent()
        data class Retrying(val attemptNumber: Int, val maxAttempts: Int, val error: Throwable) : InstallEvent()
        object Completed : InstallEvent()
        data class Failed(val error: Throwable) : InstallEvent()
    }
}
