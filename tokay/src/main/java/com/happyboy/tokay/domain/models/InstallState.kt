package com.happyboy.tokay.domain.models

/**
 * 安装状态。合法转换：Idle → Checking → Downloading/Validating/Extracting → Success；
 * 任意状态 → Failed/Retrying，Failed/Retrying → Checking；Success 是终态。
 * 非法转换只告警、不抛异常。
 */
sealed class InstallState {

    /** 当前状态关联的 Gecko 版本号；Idle / Checking / Validating 等通用状态为 null。 */
    open val version: String? = null

    object Idle : InstallState()

    object Checking : InstallState()

    /** 下载中。progress 为 0.0-1.0；totalBytes 非正值、speedBps 为 0、estimatedRemainingMs 为 -1 均表示源头未知。 */
    data class Downloading(
        override val version: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long = 0L,
        val estimatedRemainingMs: Long = -1L
    ) : InstallState()

    object Validating : InstallState()

    /** 解压中。progress 为 0.0-1.0；totalBytes 为 -1L、currentFile 为空串表示源头未给出。 */
    data class Extracting(
        override val version: String,
        val progress: Float,
        val extractedBytes: Long,
        val totalBytes: Long,
        val currentFile: String = ""
    ) : InstallState()

    data class Success(
        override val version: String
    ) : InstallState()

    data class Failed(
        override val version: String?,
        val error: Throwable,
        val phase: Phase
    ) : InstallState()

    data class Retrying(
        override val version: String?,
        val attemptNumber: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val lastError: Throwable
    ) : InstallState()

    enum class Phase {
        CHECKING,
        DOWNLOADING,
        VALIDATING,
        EXTRACTING
    }
}

