package com.happyboy.tokay.core

import com.happyboy.tokay.domain.models.InstallState
import com.happyboy.tokay.domain.policy.RetryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 重试语义的回归护栏：`InstallOrchestrator` 若把异常吞成 `Failed` 并 emit，`RetryPolicy` 就看不到
 * 异常、`Retrying` 永不发出；`GeckoInstaller` 若不反推阶段，下载/校验/解压的失败会全报成 CHECKING。
 * 用假安装流程驱动，不需要真实网络或 7z。
 */
class GeckoInstallerRetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val version = "147.0.20260212191108"
    private val abi = "arm64-v8a"

    /**
     * 延迟设为 0：真实默认配置是指数退避（合计约 6 分钟），单测等不起。
     * 只改延迟与次数，不改被测的重试语义。
     */
    private fun testRetryConfig(maxAttempts: Int) = RetryConfig(
        enabled = true,
        maxAttempts = maxAttempts,
        baseDelayMs = 0L,
        strategy = RetryConfig.RetryStrategy.FIXED,
        maxDelayMs = 0L
    )

    private fun newInstaller(
        maxAttempts: Int,
        installFlow: (String, String, File, File) -> Flow<InstallState>
    ) = GeckoInstaller(
        version = version,
        abi = abi,
        rootDir = tempFolder.root,
        retryConfig = testRetryConfig(maxAttempts),
        installFlowOverride = installFlow
    )

    /**
     * 订阅 `installState` 跑完一次安装，按序返回状态。用 `Dispatchers.Unconfined`：状态同步投递，
     * `StateFlow` 的合并语义不会吞掉中间值，否则「Retrying 出现几次、Failed 在不在之后」就不可靠。
     */
    private fun runInstallAndRecord(
        installer: GeckoInstaller,
        enableRetry: Boolean = true
    ): List<InstallState> = runBlocking {
        val recorded = mutableListOf<InstallState>()
        val job = launch(Dispatchers.Unconfined) {
            installer.installState.collect { recorded += it }
        }
        installer.installWithRetry(enableRetry)
        job.cancel()
        recorded.toList()
    }

    @Test
    fun retryingHappensMaxAttemptsTimesAndFailedComesOnlyAfterExhaustion() {
        val maxAttempts = 3
        var attemptCount = 0

        // 「总是抛异常」的假安装流程：模拟 orchestrator 在下载阶段失败后上抛
        val alwaysFail: (String, String, File, File) -> Flow<InstallState> = { _, _, _, _ ->
            attemptCount++
            flow {
                emit(InstallState.Checking)
                emit(InstallState.Downloading(version, 0.5f, 50L, 100L, 0L, -1L))
                throw IOException("假的下载失败（第 $attemptCount 次尝试）")
            }
        }

        val states = runInstallAndRecord(newInstaller(maxAttempts, alwaysFail))
        val retryings = states.filterIsInstance<InstallState.Retrying>()
        val failed = states.filterIsInstance<InstallState.Failed>()

        // 核心断言：「耗尽后才发 Failed」——把失败处理改回「吞成 Failed 并 emit」必须让它变红。
        val failedIndex = states.indexOfFirst { it is InstallState.Failed }
        val lastRetryingIndex = states.indexOfLast { it is InstallState.Retrying }
        // 「重试确实发生过」是前提，否则「Failed 晚于所有 Retrying」在零重试时空转
        assertEquals(
            "② $maxAttempts 次重试都必须真的发生过（Failed 只在耗尽后才允许出现）",
            maxAttempts,
            retryings.size
        )
        assertEquals("② Failed 必须恰好出现 1 次", 1, failed.size)
        assertTrue(
            "② Failed 必须晚于所有 Retrying（实际 Failed@$failedIndex, 最后一次 Retrying@$lastRetryingIndex）",
            failedIndex > lastRetryingIndex
        )
        assertEquals("② Failed 之后不应再有任何状态", states.size - 1, failedIndex)

        // 重试序列：attemptNumber 从 1 顺序递增、maxAttempts 字段正确、总尝试次数正确
        assertEquals("① attemptNumber 必须从 1 开始顺序递增", (1..maxAttempts).toList(), retryings.map { it.attemptNumber })
        assertTrue("① 每个 Retrying 都要带正确的 maxAttempts", retryings.all { it.maxAttempts == maxAttempts })
        assertEquals("① 首次 + maxAttempts 次重试", maxAttempts + 1, attemptCount)
    }

    @Test
    fun failedPhaseIsTheRealStageNotHardcodedChecking() {
        val cases: List<Pair<String, Pair<List<InstallState>, InstallState.Phase>>> = listOf(
            "检查阶段失败" to (
                listOf<InstallState>(InstallState.Checking) to InstallState.Phase.CHECKING
                ),
            "下载阶段失败" to (
                listOf<InstallState>(
                    InstallState.Checking,
                    InstallState.Downloading(version, 0.5f, 50L, 100L, 0L, -1L)
                ) to InstallState.Phase.DOWNLOADING
                ),
            "校验阶段失败" to (
                listOf<InstallState>(InstallState.Checking, InstallState.Validating) to InstallState.Phase.VALIDATING
                ),
            "解压阶段失败" to (
                listOf<InstallState>(
                    InstallState.Checking,
                    InstallState.Extracting(version, 0.5f, 50L, 100L, "jni/arm64-v8a/libxul.so")
                ) to InstallState.Phase.EXTRACTING
                )
        )

        for ((caseName, case) in cases) {
            val (emitted, expectedPhase) = case
            val fake: (String, String, File, File) -> Flow<InstallState> = { _, _, _, _ ->
                flow {
                    emitted.forEach { emit(it) }
                    throw IOException("$caseName 的假失败")
                }
            }

            val states = runInstallAndRecord(newInstaller(maxAttempts = 2, installFlow = fake))
            val failed = states.filterIsInstance<InstallState.Failed>()

            assertEquals("$caseName：Failed 必须恰好一次", 1, failed.size)
            assertEquals("$caseName：失败阶段必须是真实阶段", expectedPhase, failed.single().phase)
        }
    }

    @Test
    fun disabledRetryAlsoReportsRealPhaseAndEmitsFailedOnlyOnce() {
        var attemptCount = 0
        val downloadingThenFail: (String, String, File, File) -> Flow<InstallState> = { _, _, _, _ ->
            attemptCount++
            flow {
                emit(InstallState.Checking)
                emit(InstallState.Downloading(version, 0.9f, 90L, 100L, 0L, -1L))
                throw IOException("禁用重试时的下载失败")
            }
        }

        val states = runInstallAndRecord(newInstaller(maxAttempts = 3, installFlow = downloadingThenFail), enableRetry = false)

        assertEquals("未启用重试时不应出现 Retrying", 0, states.filterIsInstance<InstallState.Retrying>().size)
        assertEquals("未启用重试时只尝试一次", 1, attemptCount)
        val failed = states.filterIsInstance<InstallState.Failed>()
        assertEquals("Failed 必须恰好一次", 1, failed.size)
        assertEquals(
            "未启用重试的分支同样不许把阶段写死成 CHECKING",
            InstallState.Phase.DOWNLOADING,
            failed.single().phase
        )
    }
}
