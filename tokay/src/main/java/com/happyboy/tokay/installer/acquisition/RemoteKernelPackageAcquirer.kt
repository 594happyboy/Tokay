package com.happyboy.tokay.installer.acquisition

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.repository.ConfigRepository
import com.happyboy.tokay.installer.downloader.DownloadResult
import com.happyboy.tokay.installer.downloader.HttpDownloader
import com.happyboy.tokay.installer.validator.FileValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 远程 OTA：取清单（含验签）→ 下载 7z（下载器边下边算 sha256）→ 必要时再校验一次磁盘上已有的文件。
 * 信任链一行都没动，只是从 [com.happyboy.tokay.core.InstallOrchestrator] 挪到这里。
 */
internal class RemoteKernelPackageAcquirer(
    private val configRepository: ConfigRepository,
    private val downloader: HttpDownloader,
    private val validator: FileValidator
) : KernelPackageAcquirer {

    override fun acquire(
        version: String,
        abiTag: String,
        workDir: File
    ): Flow<AcquisitionEvent> = flow {
        Log.d(LOG_TAG, "获取下载配置...")
        val downloadInfo = configRepository.getDownloadInfo(version, abiTag).getOrThrow()

        val downloadFile = File(workDir, downloadInfo.url.substringAfterLast('/'))

        // expectedSha256 一定有值：requireSoInfo 已拒绝 url/sha256 为空的条目（不再是"空就跳过校验"）。
        val needDownload = !checkDownloadFile(downloadFile, downloadInfo.sha256)

        if (needDownload) {
            Log.d(LOG_TAG, "开始下载 SDK...")
            downloader.download(
                url = downloadInfo.url,
                targetFile = downloadFile,
                expectedSha256 = downloadInfo.sha256
            ).collect { result ->
                when (result) {
                    is DownloadResult.Progress -> {
                        emit(
                            AcquisitionEvent.Progress(
                                InstallState.Downloading(
                                    version = version,
                                    progress = result.progress.progress,
                                    downloadedBytes = result.progress.downloadedBytes,
                                    totalBytes = result.progress.totalBytes,
                                    speedBps = result.progress.speedBps,
                                    estimatedRemainingMs = result.progress.estimatedRemainingMs
                                )
                            )
                        )
                    }

                    is DownloadResult.Success -> {
                        Log.d(LOG_TAG, "下载完成: ${result.file.absolutePath}")
                    }

                    is DownloadResult.Failure -> {
                        throw result.error
                    }
                }
            }
        } else {
            Log.d(LOG_TAG, "下载文件已存在且有效，跳过下载")
        }

        // 只在"刚下载过"这一支重复校验一次（下载器已边下边算 sha256）；"复用已有文件"那一支不走这里，
        // 它的有效性已由上面的 checkDownloadFile 判定过。
        if (needDownload) {
            emit(AcquisitionEvent.Progress(InstallState.Validating))

            Log.d(LOG_TAG, "验证下载文件...")
            val validationResult = validator.validateDownloadedFile(
                file = downloadFile,
                expectedSha256 = downloadInfo.sha256
            )

            if (!validationResult.isValid()) {
                throw Exception("文件验证失败")
            }
        }

        emit(
            AcquisitionEvent.Acquired(
                file = downloadFile,
                temporary = false,
                reusedExisting = !needDownload
            )
        )
    }

    override fun cancel() {
        downloader.cancel()
    }

    /** 已有下载文件是否存在且 sha256 有效（有效则跳过下载）。 */
    private suspend fun checkDownloadFile(
        downloadFile: File,
        expectedSha256: String?
    ): Boolean {
        if (!downloadFile.exists()) {
            Log.d(LOG_TAG, "下载文件不存在: ${downloadFile.absolutePath}")
            return false
        }

        val validationResult = validator.validateDownloadedFile(downloadFile, expectedSha256)
        if (validationResult.isValid()) {
            Log.d(LOG_TAG, "下载文件已存在且有效: ${downloadFile.absolutePath}")
            return true
        }

        Log.d(LOG_TAG, "下载文件存在但无效，需要重新下载: ${downloadFile.absolutePath}")
        return false
    }
}
