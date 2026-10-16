package com.happyboy.tokay.core

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.security.DowngradeVerdict
import com.happyboy.tokay.domain.security.KernelManifestPolicy
import com.happyboy.tokay.domain.security.KernelTrustException
import com.happyboy.tokay.installer.acquisition.AcquisitionEvent
import com.happyboy.tokay.installer.acquisition.KernelPackageAcquirer
import com.happyboy.tokay.installer.extractor.ExtractResult
import com.happyboy.tokay.installer.extractor.SevenZipExtractor
import com.happyboy.tokay.installer.validator.FileValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 安装流程编排：检查 → 防降级 → 取包 → 解压 → 结构校验。
 *
 * 「字节从哪来」在 [packageAcquirer] 里（远程下载 / 随包取 assets），其余步骤与来源无关。
 *
 * [installedVersionProvider] 必须读跨进程事实（`meta/state.json`），不能用 `GeckoSdk.currentState`
 * 的内存态——冷启动时那个值永远是 Idle。返回 null 表示读不到（首次安装），放行。
 */
internal class InstallOrchestrator(
    private val stateManager: InstallStateManager,
    private val packageAcquirer: KernelPackageAcquirer,
    private val extractor: SevenZipExtractor,
    private val validator: FileValidator,
    private val installedVersionProvider: () -> String? = { null },
    private val forceDowngrade: Boolean = false
) {

    fun install(
        geckoJavaVersion: String,
        abiTag: String,
        downloadDir: File,
        extractDir: File
    ): Flow<InstallState> = flow {
        try {
            // Checking 不是可选的：状态机的进入路径只有 Idle/Retrying/Failed → Checking（见
            // `InstallState` 与 `InstallStateManager`）。跳过它，后面的 Extracting/Success 会被守卫
            // 丢掉——内核其实已经装好了，`isSuccess()` 却永远是 false，视图于是永远用系统 WebView。
            // 两种来源都在这一步「确认内核来源可用」：Remote 取清单，Packaged 确认本机 ABI 的 asset 在。
            stateManager.updateState(InstallState.Checking)
            emit(InstallState.Checking)

            // 防降级必须放在「本地已有有效解压结果就短路」**之前**：短路分支会直接发 Success，
            // 而上层收到 Success 就把 currentCoreVersion 改写成这次请求的版本——一份更低版本的清单
            // 于是能静默把已装的更高版本"降"下去。随包模式同样要比：APK 里那份可能比设备上 OTA
            // 装过的版本更旧。
            when (val verdict = KernelManifestPolicy.checkDowngrade(
                manifestVersion = geckoJavaVersion,
                installedVersion = installedVersionProvider(),
                force = forceDowngrade
            )) {
                is DowngradeVerdict.Allowed -> Unit
                is DowngradeVerdict.Rejected -> throw KernelTrustException(verdict.reason)
            }

            Log.d(LOG_TAG, "检查本地文件...")
            val extractValidation = validator.validateExtractedFiles(extractDir)
            if (extractValidation.isValid()) {
                Log.d(LOG_TAG, "SDK 已安装且有效")
                val success = InstallState.Success(version = geckoJavaVersion)
                stateManager.updateState(success)
                emit(success)
                return@flow
            }

            Log.d(LOG_TAG, "获取内核包...")
            var acquiredFile: File? = null
            var temporary = false
            var reusedExisting = false
            packageAcquirer.acquire(geckoJavaVersion, abiTag, downloadDir).collect { event ->
                when (event) {
                    is AcquisitionEvent.Progress -> {
                        stateManager.updateState(event.state)
                        emit(event.state)
                    }

                    is AcquisitionEvent.Acquired -> {
                        acquiredFile = event.file
                        temporary = event.temporary
                        reusedExisting = event.reusedExisting
                    }
                }
            }
            val downloadFile = acquiredFile
                ?: throw IllegalStateException(
                    "取包流程结束却没有交出内核包文件（${packageAcquirer::class.simpleName}）"
                )

            // 复用了已有下载文件时，说明上次解压结果无效：先清掉，避免新旧文件混在一起通过结构校验。
            if (reusedExisting && extractDir.exists()) {
                extractDir.deleteRecursively()
            }

            try {
                Log.d(LOG_TAG, "开始解压...")
                extractor.extract(downloadFile, extractDir).collect { result ->
                    when (result) {
                        is ExtractResult.Progress -> {
                            val state = InstallState.Extracting(
                                version = geckoJavaVersion,
                                progress = result.progress.progress,
                                extractedBytes = result.progress.extractedBytes,
                                totalBytes = result.progress.totalBytes,
                                currentFile = result.progress.currentFileName
                            )
                            stateManager.updateState(state)
                            emit(state)
                        }

                        is ExtractResult.Success -> {
                            Log.d(LOG_TAG, "解压完成: ${result.outputDir.absolutePath}")
                        }

                        is ExtractResult.Failure -> {
                            throw result.error
                        }
                    }
                }

                Log.d(LOG_TAG, "验证解压结果...")
                val finalExtractValidation = validator.validateExtractedFiles(extractDir)
                if (!finalExtractValidation.isValid()) {
                    throw Exception("解压文件验证失败")
                }
            } finally {
                // 随包模式的包只是从 assets 落下来的一份临时副本；失败/取消也一并清掉，
                // 别让 57 MiB 在中转目录里长期躺着（下次安装会覆盖它）。
                if (temporary) deleteTemporaryPackage(downloadFile)
            }

            val success = InstallState.Success(version = geckoJavaVersion)
            stateManager.updateState(success)
            emit(success)
            Log.d(LOG_TAG, "SDK 安装成功")

        } catch (e: Exception) {
            // 失败必须**上抛**，不能在这里收场：本类只产出「进行中」的状态，"这次失败要不要再试"
            // 只有上层的 RetryPolicy 知道。吞成 Failed 并 emit 会让流正常结束，重试永不发生。
            // stateManager 里保留着最后一次进行中的状态，上层据此判失败阶段。
            Log.e(LOG_TAG, "安装失败，上抛给重试策略", e)
            throw e
        }
    }

    private fun deleteTemporaryPackage(file: File) {
        if (file.delete()) {
            Log.d(LOG_TAG, "已删除随包模式的临时内核副本: ${file.absolutePath}")
        } else {
            Log.w(LOG_TAG, "临时内核副本删除失败（下次安装会覆盖）: ${file.absolutePath}")
        }
    }

    /**
     * 由最后一次发出的状态反推失败阶段。
     *
     * internal 而不是 private：失败由上层在重试耗尽后收场，它必须用真实阶段而不是写死的 CHECKING。
     */
    internal fun determineFailurePhase(state: InstallState): InstallState.Phase {
        return when (state) {
            is InstallState.Checking -> InstallState.Phase.CHECKING
            is InstallState.Downloading -> InstallState.Phase.DOWNLOADING
            is InstallState.Validating -> InstallState.Phase.VALIDATING
            is InstallState.Extracting -> InstallState.Phase.EXTRACTING
            else -> InstallState.Phase.CHECKING
        }
    }

    fun cancel() {
        packageAcquirer.cancel()
        extractor.cancel()
        Log.d(LOG_TAG, "安装已取消")
    }
}
